package com.leowo.thermalboost;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import java.io.*;

public class MainActivity extends Activity {

    private static final String SCONFIG_PATH = "/sys/devices/virtual/thermal/thermal_message/sconfig";
    private static final int ARVR_SCENE = 9;
    private static final int NORMAL_SCENE = 0;

    private boolean boosted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        requestNotificationPermission();

        boosted = readSconfig() == ARVR_SCENE;
        updateUI();

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
        if (boosted) {
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
        boosted = readSconfig() == ARVR_SCENE;
        updateUI();
        Toast.makeText(MainActivity.this,
            boosted ? "已开启加速充电 (ARVR)，场景被改走将自动拉回" : "已恢复默认充电",
            Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "守护服务启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopGuardService() {
        try {
            Intent i = new Intent(this, SceneGuardService.class);
            stopService(i); // 直接停止服务（onDestroy 会清理）
        } catch (Exception ignored) {}
    }

    private void updateUI() {
        TextView tv = findViewById(R.id.statusText);
        TextView limitTv = findViewById(R.id.limitText);
        tv.setText(boosted ? "加速充电: ON" : "加速充电: OFF");
        int limit = readWirelessCtrlLimit();
        limitTv.setText("wireless_ctrl_limit: " + limit + (limit == 0 ? " (不限流)" : " (限流中)"));
        tv.setTextColor(boosted ? 0xFF00C853 : 0xFFE53935);
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
            Toast.makeText(this, "Root 执行失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}