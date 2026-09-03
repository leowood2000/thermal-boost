package com.leowo.thermalboost;

import android.app.Activity;
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

        boosted = readSconfig() == ARVR_SCENE;
        updateUI();

        findViewById(R.id.toggleBtn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (boosted) {
                    execRoot("echo " + NORMAL_SCENE + " > " + SCONFIG_PATH);
                } else {
                    execRoot("echo " + ARVR_SCENE + " > " + SCONFIG_PATH);
                }
                try { Thread.sleep(300); } catch (InterruptedException e) {}
                boosted = readSconfig() == ARVR_SCENE;
                updateUI();
                Toast.makeText(MainActivity.this,
                    boosted ? "已开启加速充电 (ARVR)" : "已恢复默认充电",
                    Toast.LENGTH_SHORT).show();
            }
        });
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
