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

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Handler handler;
    private Process inotifyProc;
    private volatile int inotifyPid = -1; // 记录 inotifyd 的 PID，用于精确 kill
    private Thread watcherThread;

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
        guardEnabled = true; // 服务启动/重建时始终启用守护（进程被杀后静态变量会重置）
        // 先立即拿到 FGS 身份，避免 startForegroundService() 超时被系统杀
        boolean fgStarted = false;
        try {
            startForegroundCompat();
            fgStarted = true;
        } catch (Exception e) {
            // 后台重建时可能不允许启动前台服务，降级为普通服务继续监控
            Log.w(TAG, "startForeground failed, degrade to normal service", e);
        }
        if (!running.getAndSet(true)) {
            // 在 worker 线程中执行 root/cgroup 操作，不阻塞 onStartCommand
            new Thread(() -> {
                migrateOutOfFreezer(); // 迁移到 uid 级 cgroup，避免 MIUI 冻结导致守卫失效
                startGuard();
            }, "guard-init").start();
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

    /**
     * 把自身进程从 pid 级 cgroup 迁移到 uid 级 cgroup，避开 MIUI 的进程冻结机制。
     *
     * MIUI 通过给 /sys/fs/cgroup/uid_<uid>/pid_<pid>/cgroup.freeze 写 1 来冻结后台
     * 进程，冻结后 App 所有线程（含 scene-guard）都无法执行，守卫失效。
     * 迁移到 uid 级 cgroup 后，MIUI 的冻结动作（针对 pid 子目录）将找不到目标。
     */
    private void migrateOutOfFreezer() {
        try {
            int myPid = android.os.Process.myPid();
            // 从 /proc/self/cgroup 解析 uid 目录名（如 uid_10479）
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "cat /proc/" + myPid + "/cgroup | grep '^0::' | sed 's#^0::/##; s#/pid_.*##'"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String uidDir = br.readLine();
            int rc = p.waitFor();
            if (rc != 0 || uidDir == null || uidDir.isEmpty() || !uidDir.startsWith("uid_")) {
                Log.w(TAG, "migrate: cannot parse uid dir: " + uidDir + " rc=" + rc);
                return;
            }
            // 迁移前记录当前 cgroup 路径
            String beforeCgroup = readCgroupPath(myPid);
            // 迁移自身 pid 到 uid 级 cgroup
            Process m = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "echo " + myPid + " > /sys/fs/cgroup/" + uidDir + "/cgroup.procs"});
            int mrc = m.waitFor();
            if (mrc != 0) {
                Log.w(TAG, "cgroup migrate failed rc=" + mrc);
                return;
            }
            // readback 验证：读迁移后的 cgroup 路径，确认已离开 pid 子目录
            String afterCgroup = readCgroupPath(myPid);
            if (afterCgroup != null && !afterCgroup.contains("pid_")) {
                Log.i(TAG, "migrated to " + uidDir + " (before=" + beforeCgroup + " after=" + afterCgroup + ")");
            } else {
                Log.w(TAG, "migrate readback failed: still in pid subdir: " + afterCgroup);
            }
        } catch (Exception e) {
            Log.w(TAG, "migrate failed", e);
        }
    }

    /** 读取 /proc/<pid>/cgroup 中 v2 路径（0:: 开头那行） */
    private String readCgroupPath(int pid) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "cat /proc/" + pid + "/cgroup | grep '^0::'"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            p.waitFor();
            // 格式: 0::/uid_10479/pid_12345 或 0::/uid_10479
            if (line != null) {
                return line.substring(3).trim(); // 去掉 "0::" 前缀
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void stopGuard() {
        handler.removeCallbacksAndMessages(null);
        // 精确 kill 记录的 inotifyd PID，避免 pkill 误杀其他 inotifyd 进程
        if (inotifyPid > 0) {
            try {
                Runtime.getRuntime().exec(new String[]{"su", "-c", "kill " + inotifyPid}).waitFor();
            } catch (Exception ignored) {}
            inotifyPid = -1;
        }
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
            // 用 exec 让 su 的 shell 直接替换为 inotifyd，避免残留中间 shell/僵尸进程导致管道阻塞
            // 用 sh -c 包裹：先启动 inotifyd 并打印其 PID 到 stderr，再 exec 替换
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "exec inotifyd - " + SCONFIG_PATH + ":c 2>/dev/null"});
            // 短暂探测：如果立刻退出说明 root 失败
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {}
            if (!p.isAlive()) {
                p.destroy();
                return null;
            }
            // 记录 inotifyd 的 PID 用于精确 kill
            try {
                Process pg = Runtime.getRuntime().exec(new String[]{"su", "-c",
                        "pgrep -f 'inotifyd - " + SCONFIG_PATH + "'"});
                BufferedReader br = new BufferedReader(new InputStreamReader(pg.getInputStream()));
                String pidLine = br.readLine();
                pg.waitFor();
                if (pidLine != null) {
                    inotifyPid = Integer.parseInt(pidLine.trim());
                    Log.i(TAG, "inotifyd pid=" + inotifyPid);
                }
            } catch (Exception ignored) {}
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
        if (!running.get()) return;
        if (!guardEnabled) return; // 全局开关已关，不再拉回
        // 被改成非 9 → 拉回（带重试）
        // 自己写 9 会再次触发 inotify 事件，但下次 readSconfig() == 9 就 return，天然不会死循环
        for (int i = 0; i < RETRY_COUNT; i++) {
            if (!running.get()) return;
            if (readSconfig() != ARVR_SCENE) {
                if (writeSconfig(ARVR_SCENE)) {
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