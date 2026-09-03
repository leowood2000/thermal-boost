package com.leowo.thermalboost;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 场景守卫前台服务。
 *
 * 职责：监控 /sys/devices/virtual/thermal/thermal_message/sconfig，
 * 一旦被其他应用改走（!= 9），立即以 root 写回 ARVR(9)。
 *
 * 省电设计：
 *  1. 事件驱动：用 root 起的 inotifyd 监听 sconfig 的 c(modified) 事件，
 *     只有真正的写入才触发动作；无事件时整个服务零轮询、零唤醒。
 *  2. 兜底轮询：仅当 inotify 长时间未收到任何事件（可能被杀/失效）时，
 *     才用 60s 一次的低频轮询兜底，正常情况完全不轮询。
 *  3. 事件去抖：sconfig 被外部改写后，mi_thermald 可能短暂保留旧值，
 *     拉回前做少量重试（最多 5 次、间隔 200ms），失败后交给下个事件。
 */
public class SceneGuardService extends Service {

    private static final String TAG = "SceneGuard";
    public static final String ACTION_START = "com.leowo.thermalboost.START";
    public static final String ACTION_STOP = "com.leowo.thermalboost.STOP";
    private static final String CHANNEL_ID = "thermal_boost_guard";
    private static final int NOTIF_ID = 1;

    public static final String SCONFIG_PATH = "/sys/devices/virtual/thermal/thermal_message/sconfig";
    public static final int ARVR_SCENE = 9;

    /**
     * 全局守护开关。MainActivity 关闭加速时先置 false，
     * 确保 stopService 的异步 onDestroy 执行前，守卫线程不会把 sconfig 拉回。
     */
    public static volatile boolean guardEnabled = false;
    private static final int POLL_INTERVAL_MS = 60_000;
    private static final int RETRY_COUNT = 5;
    private static final int RETRY_DELAY_MS = 200;
    private static final int EVENT_QUIET_WINDOW_MS = 800;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Handler handler;
    private Process inotifyProc;
    private Thread watcherThread;
    private long lastWriteTs = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean fgStarted = false;
        try {
            startForegroundCompat();
            fgStarted = true;
        } catch (Exception e) {
            // 后台重建时可能不允许启动前台服务，降级为普通服务继续监控
            Log.w(TAG, "startForeground failed, degrade to normal service", e);
        }
        if (fgStarted && !running.getAndSet(true)) {
            startGuard();
        } else if (!fgStarted && !running.getAndSet(true)) {
            // 无法前台化时仍然尝试守护（可能被系统杀，但尽力而为）
            startGuard();
        }
        return START_STICKY; // 被系统杀后尝试重建，重建即重新监控
    }

    @Override
    public void onDestroy() {
        running.set(false);
        stopGuard();
        super.onDestroy();
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "充电加速", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("充电加速运行中")
                .setContentText("正在守护 ARVR 充电场景")
                .setSmallIcon(android.R.drawable.ic_lock_power_off)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN)
                .build();
        startForeground(NOTIF_ID, n);
    }

    private void startGuard() {
        stopGuard(); // 清理旧状态
        // 兜底轮询：60s 一次，仅在 inotify 事件完全丢失时兜底
        handler.postDelayed(pollTask, POLL_INTERVAL_MS);
        watcherThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "guard thread started");
                while (running.get()) {
                    inotifyProc = startInotify();
                    if (inotifyProc == null) {
                        // root 不可用或启动失败，靠轮询兜底
                        sleepSafe(5000);
                        continue;
                    }
                    readLoop(inotifyProc);
                    inotifyProc = null;
                    if (running.get()) {
                        sleepSafe(1000); // 事件流结束后短暂等待，防止忙循环
                    }
                }
            }
        }, "scene-guard");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    private void stopGuard() {
        handler.removeCallbacksAndMessages(null);
        if (inotifyProc != null) {
            inotifyProc.destroy();
            inotifyProc = null;
        }
        Thread t = watcherThread;
        if (t != null && t.isAlive()) {
            try { t.join(1500); } catch (InterruptedException ignored) {}
        }
    }

    /**
     * 启动 root inotify：监听 sconfig 的 c(modified) 事件。
     * 用 "-" 作为 PROG 把事件打到 stdout，readLoop 逐行读取。
     */
    private Process startInotify() {
        try {
            // 用 Runtime.exec 而非 ProcessBuilder，避免前台服务上下文中 ProcessBuilder 的权限问题
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "inotifyd - " + SCONFIG_PATH + ":c 2>/dev/null"});
            // 短暂探测：如果立刻退出说明 root 失败
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {}
            if (!p.isAlive()) {
                p.destroy();
                return null;
            }
            return p;
        } catch (Exception e) {
            Log.w(TAG, "startInotify failed", e);
            return null;
        }
    }

    private void readLoop(Process p) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while (running.get()) {
                line = br.readLine();
                if (line == null) break;
                if (line.contains("/sconfig")) {
                    // 事件来了：读到即检查，事件可能被合并，读值最可靠
                    handleEvent();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "readLoop error", e);
        }
    }

    private void handleEvent() {
        // 我们自己在拉回时也会写 sconfig，会产生事件；防回环：冷却窗口
        long now = System.currentTimeMillis();
        if (now - lastWriteTs < EVENT_QUIET_WINDOW_MS) return;
        if (!running.get()) return;
        if (!guardEnabled) return; // 全局开关已关，不再拉回
        // 被改成非 9 → 拉回（带重试）
        for (int i = 0; i < RETRY_COUNT; i++) {
            if (!running.get()) return;
            if (readSconfig() != ARVR_SCENE) {
                if (writeSconfig(ARVR_SCENE)) {
                    lastWriteTs = System.currentTimeMillis();
                    Log.i(TAG, "scene hijacked -> restored to ARVR(9)");
                }
            } else {
                return; // 值已经是对的
            }
            try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException e) { return; }
        }
    }

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
        if (!running.get()) return;
        if (!guardEnabled) return; // 全局开关已关，不再拉回
        if (readSconfig() != ARVR_SCENE) {
            if (writeSconfig(ARVR_SCENE)) {
                lastWriteTs = System.currentTimeMillis();
                Log.i(TAG, "poll restore ARVR");
                }
            }
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private int readSconfig() {
        try (FileInputStream fis = new FileInputStream(SCONFIG_PATH)) {
            byte[] buf = new byte[16];
            int n = fis.read(buf);
            String s = new String(buf, 0, n, "UTF-8").trim();
            return Integer.parseInt(s);
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean writeSconfig(int val) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "echo " + val + " > " + SCONFIG_PATH});
            int rc = p.waitFor();
            return rc == 0;
        } catch (Exception e) {
            Log.w(TAG, "writeSconfig failed", e);
            return false;
        }
    }

    private void sleepSafe(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}