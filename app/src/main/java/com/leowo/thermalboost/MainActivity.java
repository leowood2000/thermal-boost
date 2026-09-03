package com.leowo.thermalboost;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String SCONFIG_PATH = "/sys/devices/virtual/thermal/thermal_message/sconfig";
    private static final int ARVR_SCENE = 9;
    private static final int NORMAL_SCENE = 0;

    private boolean boosted = false;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        requestNotificationPermission();

        // 异步读取初始状态，避免阻塞主线程
        ioExecutor.execute(() -> {
            boolean isArvr = readSconfig() == ARVR_SCENE;
            uiHandler.post(() -> {
                boosted = isArvr;
                updateUI();
            });
        });

        findViewById(R.id.toggleBtn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle();
            }
        });
    }

    /** Android 13+ 请求通知权限（前台服务通知需要） */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }
    }

    private void toggle() {
        final boolean wasBoosted = boosted;
        // 立即禁用按钮，防止快速重复点击
        findViewById(R.id.toggleBtn).setEnabled(false);
        ioExecutor.execute(() -> {
            if (wasBoosted) {
                // 关闭加速：先禁用守护（防止 inotify 拉回），再停服务，再恢复 Normal
                SceneGuardService.guardEnabled = false;
                stopGuardService();
                execRoot("echo " + NORMAL_SCENE + " > " + SCONFIG_PATH);
            } else {
                // 开启加速：先写 ARVR，再启用守护并启动服务
                execRoot("echo " + ARVR_SCENE + " > " + SCONFIG_PATH);
                SceneGuardService.guardEnabled = true;
                startGuardService();
            }
            try { Thread.sleep(300); } catch (InterruptedException e) {}
            final boolean isBoosted = readSconfig() == ARVR_SCENE;
            uiHandler.post(() -> {
                boosted = isBoosted;
                updateUI();
                findViewById(R.id.toggleBtn).setEnabled(true);
                Toast.makeText(MainActivity.this,
                    boosted ? "已开启加速充电 (ARVR)，场景被改走将自动拉回" : "已恢复默认充电",
                    Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void startGuardService() {
        try {
            Intent i = new Intent(this, SceneGuardService.class);
            i.setAction(SceneGuardService.ACTION_START);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            uiHandler.post(() -> Toast.makeText(this, "守护服务启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    private void stopGuardService() {
        try {
            Intent i = new Intent(this, SceneGuardService.class);
            stopService(i); // 直接停止服务（onDestroy 会清理）
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdown();
        super.onDestroy();
    }

    private void updateUI() {
        TextView tv = findViewById(R.id.statusText);
        TextView limitTv = findViewById(R.id.limitText);
        TextView guardTv = findViewById(R.id.guardText);
        // 区分「场景 = ARVR」和「守卫运行中」
        boolean guardRunning = SceneGuardService.guardEnabled;
        if (boosted && guardRunning) {
            tv.setText("加速充电: ON");
            tv.setTextColor(0xFF00C853);
        } else if (boosted && !guardRunning) {
            tv.setText("场景: ARVR (守卫未运行)");
            tv.setTextColor(0xFFFF9800);
        } else {
            tv.setText("加速充电: OFF");
            tv.setTextColor(0xFFE53935);
        }
        guardTv.setText("守卫: " + (guardRunning ? "运行中" : "停止"));
        guardTv.setTextColor(guardRunning ? 0xFF00C853 : 0xFFAAAAAA);
        // 异步读取 wireless_ctrl_limit
        ioExecutor.execute(() -> {
            final int limit = readWirelessCtrlLimit();
            uiHandler.post(() -> {
                limitTv.setText("wireless_ctrl_limit: " + limit + (limit == 0 ? " (不限流)" : " (限流中)"));
            });
        });
    }

    private int readSconfig() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + SCONFIG_PATH});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            p.waitFor();
            return line != null ? Integer.parseInt(line.trim()) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private int readWirelessCtrlLimit() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat /sys/devices/platform/soc/soc:mca_charger_thermal/wireless_ctrl_limit"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            p.waitFor();
            return line != null ? Integer.parseInt(line.trim()) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private void execRoot(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.waitFor();
        } catch (Exception e) {
            uiHandler.post(() -> Toast.makeText(this, "Root 执行失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }
}