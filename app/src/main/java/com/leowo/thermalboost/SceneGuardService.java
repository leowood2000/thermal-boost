package com.leowo.thermalboost;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 场景守卫前台服务。
 *
 * 职责：监控 /sys/devices/virtual/thermal/thermal_message/sconfig，
 * 根据所选模式守护 sconfig：无线模式使用 ARVR(9)，有线模式仅在安全条件满足时使用 hp-normal(500)。
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
    public static final String EXTRA_SCENE = "target_scene";
    public static final String PREFS_NAME = "thermal_boost";
    public static final String PREF_SCENE = "selected_scene";
    private static final String PREF_WIRED_OWNED = "wired_scene_owned";
    private static final String PREF_WIRELESS_OWNED = "wireless_scene_owned";
    private static final String CHANNEL_ID = "thermal_boost_guard";
    private static final int NOTIF_ID = 1;

    public static final String SCONFIG_PATH = "/sys/devices/virtual/thermal/thermal_message/sconfig";
    public static final int NORMAL_SCENE = 0;
    public static final int ARVR_SCENE = 9;
    public static final int WIRED_SCENE = 500;

    /** guardEnabled 表示当前模式已选择并由服务守护；wired 模式可能因条件不满足而暂不生效。 */
    public static volatile boolean guardEnabled = false;
    public static volatile int targetScene = NORMAL_SCENE;
    private static final int POLL_INTERVAL_MS = 60_000;
    private static final int RETRY_COUNT = 5;
    private static final int RETRY_DELAY_MS = 200;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean modeCheckRunning = new AtomicBoolean(false);
    private final AtomicBoolean modeCheckAgain = new AtomicBoolean(false);
    private final Object modeLock = new Object();
    private Handler handler;
    private Process inotifyProc;
    private volatile int inotifyPid = -1; // 记录 inotifyd 的 PID，用于精确 kill
    private Thread watcherThread;
    private String originalCgroup = null; // 迁移前的原始 cgroup 路径，关闭时恢复
    private volatile boolean wiredSceneOwned;
    private volatile boolean wirelessSceneOwned;
    private boolean stateReceiverRegistered;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())
                    || Intent.ACTION_SCREEN_ON.equals(intent.getAction())
                    || Intent.ACTION_SCREEN_OFF.equals(intent.getAction()))) {
                scheduleModeCheck();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        targetScene = prefs.getInt(PREF_SCENE, NORMAL_SCENE);
        wiredSceneOwned = prefs.getBoolean(PREF_WIRED_OWNED, false);
        wirelessSceneOwned = prefs.getBoolean(PREF_WIRELESS_OWNED, false);
        registerStateReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            int previousScene;
            synchronized (modeLock) {
                previousScene = targetScene;
                targetScene = NORMAL_SCENE;
                guardEnabled = false;
                saveSelectedScene(NORMAL_SCENE);
            }
            startForegroundCompat();
            final int stopStartId = startId;
            new Thread(() -> {
                restoreOwnedScene(previousScene);
                // Do not tear down a newer mode request that arrived during restore.
                stopSelf(stopStartId);
            }, "guard-stop").start();
            return START_NOT_STICKY;
        }
        int requestedScene = intent != null && intent.hasExtra(EXTRA_SCENE)
                ? intent.getIntExtra(EXTRA_SCENE, NORMAL_SCENE)
                : getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_SCENE, NORMAL_SCENE);
        if (!isSupportedScene(requestedScene)) {
            targetScene = NORMAL_SCENE;
            guardEnabled = false;
            saveSelectedScene(NORMAL_SCENE);
            startForegroundCompat();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        synchronized (modeLock) {
            // An upgrade from the old ARVR-only version can switch before the new
            // guard has had a chance to persist that the active 9 was app-managed.
            if (requestedScene == WIRED_SCENE && guardEnabled && running.get()
                    && targetScene == ARVR_SCENE
                    && readSconfig() == ARVR_SCENE) {
                setWirelessSceneOwned(true);
            }
            targetScene = requestedScene;
            guardEnabled = true;
            saveSelectedScene(requestedScene);
        }
        // 先立即拿到 FGS 身份，避免 startForegroundService() 超时被系统杀
        try {
            startForegroundCompat();
        } catch (Exception e) {
            Log.w(TAG, "startForeground failed", e);
        }
        if (!running.getAndSet(true)) {
            // 在 worker 线程中执行 root/cgroup 操作，不阻塞 onStartCommand
            new Thread(() -> {
                migrateOutOfFreezer(); // 迁移到 uid 级 cgroup，避免 MIUI 冻结导致守卫失效
                // 迁移期间服务可能已被关闭，需检查并回滚迁移
                if (!running.get()) {
                    migrateBackToFreezer();
                    return;
                }
                startGuard();
                // 初次启动时先确保 running=true 后再执行；onStartCommand 中的检查可能抢跑。
                scheduleModeCheck();
            }, "guard-init").start();
        }
        scheduleModeCheck();
        return START_STICKY; // 被系统杀后尝试重建，重建即重新监控
    }

    @Override
    public void onDestroy() {
        running.set(false);
        guardEnabled = false; // 确保非正常停止时也清除全局开关
        stopGuard();
        restoreOwnedWiredSceneOnDestroy();
        migrateBackToFreezer(); // 恢复到原来的 pid 级 cgroup，让 MIUI freezer 重新管控
        if (stateReceiverRegistered) {
            try { unregisterReceiver(stateReceiver); } catch (Exception ignored) {}
            stateReceiverRegistered = false;
        }
        super.onDestroy();
    }

    private void startForegroundCompat() {
        startForeground(NOTIF_ID, buildNotification());
    }

    private void registerStateReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(stateReceiver, filter);
            }
            stateReceiverRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "state receiver registration failed", e);
        }
    }

    private boolean isSupportedScene(int scene) {
        return scene == ARVR_SCENE || scene == WIRED_SCENE;
    }

    private void saveSelectedScene(int scene) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(PREF_SCENE, scene).apply();
    }

    private boolean setWiredSceneOwned(boolean owned) {
        boolean saved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_WIRED_OWNED, owned).commit();
        if (saved) wiredSceneOwned = owned;
        else Log.w(TAG, "could not persist wired scene ownership=" + owned);
        return saved;
    }

    private boolean setWirelessSceneOwned(boolean owned) {
        boolean saved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_WIRELESS_OWNED, owned).commit();
        if (saved) wirelessSceneOwned = owned;
        else Log.w(TAG, "could not persist wireless scene ownership=" + owned);
        return saved;
    }

    private String notificationText() {
        int scene = targetScene;
        if (scene == WIRED_SCENE) {
            return isWiredEligible() ? "正在守护有线 hp-normal (500)" : "有线模式已选择，等待有线供电和亮屏";
        }
        return scene == ARVR_SCENE ? "正在守护无线 ARVR (9)" : "场景守护已停止";
    }

    /** Wired hp-normal is specific to miro and only eligible with USB/AC power and an interactive display. */
    private boolean isWiredEligible() {
        if (!"miro".equalsIgnoreCase(Build.DEVICE)) return false;
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return false;
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        if (plugged != BatteryManager.BATTERY_PLUGGED_USB
                && plugged != BatteryManager.BATTERY_PLUGGED_AC) return false;
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return powerManager != null && powerManager.isInteractive();
    }

    private void scheduleModeCheck() {
        if (!guardEnabled) return;
        if (!modeCheckRunning.compareAndSet(false, true)) {
            modeCheckAgain.set(true);
            return;
        }
        new Thread(() -> {
            try {
                do {
                    modeCheckAgain.set(false);
                    enforceSelectedScene();
                } while (modeCheckAgain.get());
            } finally {
                modeCheckRunning.set(false);
                if (modeCheckAgain.get()) scheduleModeCheck();
            }
        }, "scene-enforce").start();
    }

    /** Serialize all scene writes with mode changes; wired mode never writes outside its eligibility gate. */
    private void enforceSelectedScene() {
        synchronized (modeLock) {
            if (!running.get() || !guardEnabled) return;
            int scene = targetScene;
            int current = readSconfig();
            if (scene == ARVR_SCENE) {
                if (current != ARVR_SCENE) {
                    boolean wrote = writeSconfig(ARVR_SCENE);
                    int afterWrite = readSconfig();
                    if (afterWrite == ARVR_SCENE) {
                        if (wrote) Log.i(TAG, "restored wireless ARVR(9)");
                        // The explicit wireless mode owns 9 even if it was already active.
                        setWirelessSceneOwned(true);
                        setWiredSceneOwned(false);
                    } else {
                        if (afterWrite != ARVR_SCENE) setWirelessSceneOwned(false);
                        if (afterWrite != WIRED_SCENE) setWiredSceneOwned(false);
                    }
                } else {
                    setWirelessSceneOwned(true);
                    setWiredSceneOwned(false);
                }
            } else if (scene == WIRED_SCENE) {
                if (isWiredEligible()) {
                    if (current != WIRED_SCENE && !applyWiredScene()) {
                        // If the previous app-owned ARVR could not be replaced, do not leave it active.
                        clearOwnedScenesWhileWiredWaits();
                    }
                    if (!isWiredEligible()) clearOwnedScenesWhileWiredWaits();
                } else {
                    // A deliberate ARVR -> wired mode change also removes our old ARVR scene.
                    clearOwnedScenesWhileWiredWaits();
                }
            }
        }
        updateForegroundNotification();
    }

    /** The only path that writes hp-normal. Ownership is durable before the gated write. */
    private boolean applyWiredScene() {
        if (!isWiredEligible() || readSconfig() == WIRED_SCENE) return false;
        if (!setWiredSceneOwned(true)) return false;
        // Do not claim an already-active OEM 500 if it appeared before our write.
        if (readSconfig() == WIRED_SCENE) {
            setWiredSceneOwned(false);
            return false;
        }
        // This is the final eligibility check immediately before the sysfs write.
        if (!isWiredEligible()) {
            setWiredSceneOwned(false);
            return false;
        }

        boolean wrote = writeSconfig(WIRED_SCENE);
        int current = readSconfig();
        if (!wrote || current != WIRED_SCENE) {
            setWiredSceneOwned(false);
            return false;
        }
        if (!isWiredEligible()) {
            // If eligibility disappeared while the sysfs write was running, undo our scene now.
            if (writeSconfig(NORMAL_SCENE)) {
                setWiredSceneOwned(false);
                setWirelessSceneOwned(false);
            }
            return false;
        }
        setWirelessSceneOwned(false);
        return wrote || current == WIRED_SCENE;
    }

    /** Clear only scenes this service previously wrote; preserve unrelated OEM-selected scenes. */
    private void clearOwnedScenesWhileWiredWaits() {
        int current = readSconfig();
        if (current == WIRED_SCENE && wiredSceneOwned) {
            if (writeSconfig(NORMAL_SCENE)) {
                setWiredSceneOwned(false);
                setWirelessSceneOwned(false);
            }
        } else if (current == ARVR_SCENE && wirelessSceneOwned) {
            if (writeSconfig(NORMAL_SCENE)) {
                setWirelessSceneOwned(false);
                setWiredSceneOwned(false);
            }
        } else {
            if (current != WIRED_SCENE) setWiredSceneOwned(false);
            if (current != ARVR_SCENE) setWirelessSceneOwned(false);
        }
    }

    /** onDestroy is the final synchronous safety net when the service is gracefully removed. */
    private void restoreOwnedWiredSceneOnDestroy() {
        synchronized (modeLock) {
            int current = readSconfig();
            if (current == WIRED_SCENE && wiredSceneOwned) {
                if (writeSconfig(NORMAL_SCENE)) {
                    setWiredSceneOwned(false);
                    setWirelessSceneOwned(false);
                }
            } else if (current != WIRED_SCENE && wiredSceneOwned) {
                setWiredSceneOwned(false);
            }
        }
    }

    private void restoreOwnedScene(int previousScene) {
        synchronized (modeLock) {
            // A newer START may have superseded this asynchronous stop request.
            if (guardEnabled || targetScene != NORMAL_SCENE) return;
            int current = readSconfig();
            if (current == WIRED_SCENE && wiredSceneOwned) {
                if (writeSconfig(NORMAL_SCENE)) {
                    setWiredSceneOwned(false);
                    setWirelessSceneOwned(false);
                }
            } else if (current == ARVR_SCENE
                    && (previousScene == ARVR_SCENE || wirelessSceneOwned)) {
                // Preserve legacy wireless-off behavior and clear app-owned ARVR transitions.
                if (writeSconfig(NORMAL_SCENE)) {
                    setWirelessSceneOwned(false);
                    setWiredSceneOwned(false);
                }
            } else {
                if (current != WIRED_SCENE) setWiredSceneOwned(false);
                if (current != ARVR_SCENE) setWirelessSceneOwned(false);
            }
        }
    }

    private void updateForegroundNotification() {
        if (!guardEnabled) return;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIF_ID, buildNotification());
    }

    private Notification buildNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "充电加速", NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("充电加速运行中")
                .setContentText(notificationText())
                .setSmallIcon(android.R.drawable.ic_lock_power_off)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN)
                .build();
    }

    private void startGuard() {
        if (!running.get()) return; // 服务已关闭，不启动守卫
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
            // 迁移前记录当前 cgroup 路径（含 pid 子目录），关闭时恢复
            String beforeCgroup = readCgroupPath(myPid);
            // 仅当当前在 pid 子目录时才需要迁移
            if (beforeCgroup == null || !beforeCgroup.contains("pid_")) {
                Log.i(TAG, "already in uid-level cgroup, no migrate needed: " + beforeCgroup);
                return;
            }
            originalCgroup = beforeCgroup;
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

    /** 把进程迁回原来的 pid 级 cgroup，恢复 MIUI freezer 管控 */
    private void migrateBackToFreezer() {
        if (originalCgroup == null) return;
        try {
            int myPid = android.os.Process.myPid();
            String cgroupPath = originalCgroup; // 如 /uid_10479/pid_12345
            // 检查原 pid cgroup 目录是否仍存在
            Process ck = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "test -d /sys/fs/cgroup" + cgroupPath + " && echo ok"});
            BufferedReader br = new BufferedReader(new InputStreamReader(ck.getInputStream()));
            String exists = br.readLine();
            ck.waitFor();
            if ("ok".equals(exists)) {
                Process m = Runtime.getRuntime().exec(new String[]{"su", "-c",
                        "echo " + myPid + " > /sys/fs/cgroup" + cgroupPath + "/cgroup.procs"});
                int mrc = m.waitFor();
                if (mrc == 0) {
                    Log.i(TAG, "migrated back to " + cgroupPath);
                } else {
                    Log.w(TAG, "migrate back failed rc=" + mrc);
                }
            } else {
                // 原 pid cgroup 目录已不存在（进程可能已重建），无需恢复
                Log.i(TAG, "original cgroup dir gone, skip restore: " + cgroupPath);
            }
            originalCgroup = null;
        } catch (Exception e) {
            Log.w(TAG, "migrate back failed", e);
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
        if (!running.get() || !guardEnabled) return;
        for (int i = 0; i < RETRY_COUNT; i++) {
            if (!running.get() || !guardEnabled) return;
            enforceSelectedScene();
            if (!needsEnforcement()) return;
            try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException e) { return; }
        }
    }

    private boolean needsEnforcement() {
        synchronized (modeLock) {
            if (!guardEnabled) return false;
            int current = readSconfig();
            if (targetScene == ARVR_SCENE) return current != ARVR_SCENE;
            if (targetScene == WIRED_SCENE) {
                if (isWiredEligible()) return current != WIRED_SCENE;
                return (current == WIRED_SCENE && wiredSceneOwned)
                        || (current == ARVR_SCENE && wirelessSceneOwned);
            }
            return false;
        }
    }

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!running.get()) return;
            scheduleModeCheck();
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
