package com.leowo.thermalboost;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String SCONFIG_PATH = "/sys/devices/virtual/thermal/thermal_message/sconfig";
    private static final int NORMAL_SCENE = 0;
    private static final int ARVR_SCENE = 9;
    private static final int WIRED_SCENE = 500;
    private static final int REFRESH_INTERVAL_MS = 5000;

    private volatile int actualScene = -1;
    private volatile int selectedScene = NORMAL_SCENE;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        requestNotificationPermission();
        selectedScene = readSelectedScene();
        setButtonsEnabled(false);
        findViewById(R.id.toggleBtn).setOnClickListener(v -> toggleScene(ARVR_SCENE));
        findViewById(R.id.wiredToggleBtn).setOnClickListener(v -> toggleScene(WIRED_SCENE));

        ioExecutor.execute(() -> {
            final int[] values = readSconfigAndLimit();
            uiHandler.post(() -> {
                actualScene = values[0];
                syncSelectedScene();
                updateUI(values[1]);
                setButtonsEnabled(true);
            });
        });
    }

    /** Android 13+ 请求通知权限（前台服务通知需要） */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private void toggleScene(int requestedScene) {
        syncSelectedScene();
        if (selectedScene != requestedScene && requestedScene == WIRED_SCENE
                && !"miro".equalsIgnoreCase(Build.DEVICE)) {
            Toast.makeText(this, wiredIneligibleReason(), Toast.LENGTH_LONG).show();
            return;
        }

        setButtonsEnabled(false);
        // If Android restored the saved selection but the service is not alive,
        // tapping the selected mode should bring its guard back instead of disabling it.
        final boolean turnOff = selectedScene == requestedScene && SceneGuardService.guardEnabled;
        ioExecutor.execute(() -> {
            if (turnOff) {
                requestServiceStop();
            } else if (!requestServiceStart(requestedScene)) {
                showToast("守护服务启动失败", Toast.LENGTH_LONG);
            } else {
                String name = requestedScene == WIRED_SCENE ? "有线 hp-normal (500)" : "无线 ARVR (9)";
                String suffix = requestedScene == WIRED_SCENE ? "，符合条件时自动应用" : "";
                showToast("已选择" + name + "模式" + suffix, Toast.LENGTH_SHORT);
            }

            try { Thread.sleep(350); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            final int[] values = readSconfigAndLimit();
            uiHandler.post(() -> {
                actualScene = values[0];
                syncSelectedScene();
                updateUI(values[1]);
                setButtonsEnabled(true);
            });
        });
    }

    private boolean requestServiceStart(int scene) {
        try {
            Intent intent = new Intent(this, SceneGuardService.class);
            intent.setAction(SceneGuardService.ACTION_START);
            intent.putExtra(SceneGuardService.EXTRA_SCENE, scene);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void requestServiceStop() {
        try {
            Intent intent = new Intent(this, SceneGuardService.class);
            intent.setAction(SceneGuardService.ACTION_STOP);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
        } catch (Exception e) {
            showToast("无法停止场景守护服务: " + e.getMessage(), Toast.LENGTH_LONG);
        }
    }

    private int readSelectedScene() {
        return getSharedPreferences(SceneGuardService.PREFS_NAME, MODE_PRIVATE)
                .getInt(SceneGuardService.PREF_SCENE, NORMAL_SCENE);
    }

    private void syncSelectedScene() {
        if (SceneGuardService.guardEnabled) {
            selectedScene = SceneGuardService.targetScene;
        } else {
            selectedScene = readSelectedScene();
        }
    }

    private boolean isScreenInteractive() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        return powerManager != null && powerManager.isInteractive();
    }

    private String wiredIneligibleReason() {
        if (!"miro".equalsIgnoreCase(Build.DEVICE)) {
            return "hp-normal (500) 仅针对 Redmi K80 Pro (miro) 实测，不在此设备启用";
        }
        if (!isWiredCharging()) {
            return "有线模式仅在 USB/AC 有线充电时可开启；无线充电请使用 ARVR 模式";
        }
        return "有线模式仅在屏幕亮起时可开启";
    }

    private boolean isWiredCharging() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return false;
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        return plugged == BatteryManager.BATTERY_PLUGGED_USB
                || plugged == BatteryManager.BATTERY_PLUGGED_AC;
    }

    private void setButtonsEnabled(boolean enabled) {
        View wireless = findViewById(R.id.toggleBtn);
        View wired = findViewById(R.id.wiredToggleBtn);
        if (wireless != null) wireless.setEnabled(enabled);
        if (wired != null) wired.setEnabled(enabled);
    }

    private void showToast(String message, int duration) {
        uiHandler.post(() -> Toast.makeText(MainActivity.this, message, duration).show());
    }

    @Override
    protected void onResume() {
        super.onResume();
        uiHandler.postDelayed(refreshTask, REFRESH_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(refreshTask);
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    /** 前台每 5 秒刷新当前 sconfig 与无线限流值。 */
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing() && !ioExecutor.isShutdown()) {
                ioExecutor.execute(() -> {
                    final int[] values = readSconfigAndLimit();
                    if (!ioExecutor.isShutdown()) {
                        uiHandler.post(() -> {
                            if (isFinishing()) return;
                            actualScene = values[0];
                            syncSelectedScene();
                            updateUI(values[1]);
                        });
                    }
                });
            }
            uiHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private void updateUI() {
        updateUI(Integer.MIN_VALUE);
    }

    private void updateUI(int wirelessLimit) {
        TextView statusTv = findViewById(R.id.statusText);
        TextView guardTv = findViewById(R.id.guardText);
        TextView limitTv = findViewById(R.id.limitText);
        TextView wirelessBtn = findViewById(R.id.toggleBtn);
        TextView wiredBtn = findViewById(R.id.wiredToggleBtn);
        if (statusTv == null || guardTv == null || limitTv == null || wirelessBtn == null || wiredBtn == null) return;

        statusTv.setText("当前热控场景：" + sceneName(actualScene));
        statusTv.setTextColor(actualScene == selectedScene && selectedScene != NORMAL_SCENE
                ? 0xFF00C853 : actualScene < 0 ? 0xFFFF9800 : 0xFFAAAAAA);

        boolean serviceActive = SceneGuardService.guardEnabled;
        if (selectedScene == ARVR_SCENE) {
            guardTv.setText(serviceActive ? "已选择无线 ARVR (9) · 场景守护运行中"
                    : "已选择无线 ARVR (9) · 守护服务未运行");
            guardTv.setTextColor(serviceActive ? 0xFF00C853 : 0xFFFF9800);
        } else if (selectedScene == WIRED_SCENE) {
            String state;
            if (!"miro".equalsIgnoreCase(Build.DEVICE)) state = "设备不支持";
            else if (!isWiredCharging()) state = "等待 USB/AC 有线供电";
            else if (!isScreenInteractive()) state = "等待屏幕亮起";
            else if (actualScene == WIRED_SCENE) state = "hp-normal 已生效";
            else state = "等待场景加载";
            guardTv.setText((serviceActive ? "已选择有线 hp-normal (500) · " : "有线 hp-normal (500) 已选择 · ") + state);
            guardTv.setTextColor(serviceActive && actualScene == WIRED_SCENE ? 0xFF00C853 : 0xFFFF9800);
        } else {
            guardTv.setText("场景守护：停止");
            guardTv.setTextColor(0xFFAAAAAA);
        }

        wirelessBtn.setText(selectedScene == ARVR_SCENE ? "关闭无线充电加速 (ARVR)" : "开启无线充电加速 (ARVR)");
        wiredBtn.setText(selectedScene == WIRED_SCENE ? "关闭有线充电加速 (hp-normal)" : "开启有线充电加速 (hp-normal)");

        if (selectedScene == WIRED_SCENE || actualScene == WIRED_SCENE) {
            limitTv.setText("hp-normal 仅限 miro 有线亮屏场景；无线充电在该场景下更早限流");
        } else if (wirelessLimit != Integer.MIN_VALUE) {
            limitTv.setText("wireless_ctrl_limit: " + wirelessLimit
                    + (wirelessLimit == 0 ? " (不限流)" : wirelessLimit < 0 ? " (读取失败)" : " (限流中)"));
        } else if (!ioExecutor.isShutdown()) {
            ioExecutor.execute(() -> {
                final int value = readWirelessCtrlLimit();
                if (!ioExecutor.isShutdown()) {
                    uiHandler.post(() -> {
                        if (!isFinishing() && selectedScene != WIRED_SCENE && actualScene != WIRED_SCENE) {
                            limitTv.setText("wireless_ctrl_limit: " + value
                                    + (value == 0 ? " (不限流)" : value < 0 ? " (读取失败)" : " (限流中)"));
                        }
                    });
                }
            });
        }
    }

    private String sceneName(int scene) {
        if (scene == NORMAL_SCENE) return "默认 (0)";
        if (scene == ARVR_SCENE) return "无线 ARVR (9)";
        if (scene == WIRED_SCENE) return "有线 hp-normal (500)";
        return scene < 0 ? "读取失败" : String.valueOf(scene);
    }

    /** 一次 su 同时读取 sconfig 和无线限流节点，返回 int[2]{sconfig, limit} */
    private int[] readSconfigAndLimit() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "cat " + SCONFIG_PATH + "; cat /sys/devices/platform/soc/soc:mca_charger_thermal/wireless_ctrl_limit"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line1 = br.readLine();
            String line2 = br.readLine();
            p.waitFor();
            int scene = line1 != null ? Integer.parseInt(line1.trim()) : -1;
            int limit = line2 != null ? Integer.parseInt(line2.trim()) : -1;
            return new int[]{scene, limit};
        } catch (Exception e) {
            return new int[]{-1, -1};
        }
    }

    private int readWirelessCtrlLimit() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "cat /sys/devices/platform/soc/soc:mca_charger_thermal/wireless_ctrl_limit"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            p.waitFor();
            return line != null ? Integer.parseInt(line.trim()) : -1;
        } catch (Exception e) {
            return -1;
        }
    }
}
