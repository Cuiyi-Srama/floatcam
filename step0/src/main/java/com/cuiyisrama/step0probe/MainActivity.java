package com.cuiyisrama.step0probe;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Context;
import android.content.SharedPreferences;

public class MainActivity extends Activity {

    private static final int REQ_CAMERA = 100;
    private static final int REQ_NOTIF = 101;

    private TextView statusText;
    private Button startBtn;
    private Button stopBtn;
    private Button alphaBtn;
    private float currentAlpha = 0.75f;
    private final float[] ALPHAS = {1.0f, 0.8f, 0.75f, 0.7f};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(buildLayout());

        // restore alpha
        SharedPreferences sp = getSharedPreferences("probe", MODE_PRIVATE);
        currentAlpha = sp.getFloat("alpha", 0.75f);

        startBtn = findViewById(1001);
        stopBtn = findViewById(1002);
        alphaBtn = findViewById(1003);
        statusText = findViewById(1004);

        startBtn.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                requestAndStart();
            }
        });
        stopBtn.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                stopService(new Intent(MainActivity.this, ProbeService.class));
                updateStatus("服务已停止");
            }
        });
        alphaBtn.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                cycleAlpha();
            }
        });

        updateStatus("等待启动");
    }

    private android.view.View buildLayout() {
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(48, 96, 48, 96);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);

        statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setPadding(0, 16, 0, 16);
        root.addView(statusText, lp);

        startBtn = new Button(this);
        startBtn.setId(1001);
        startBtn.setText("启动悬浮摄像头");
        root.addView(startBtn, lp);

        stopBtn = new Button(this);
        stopBtn.setId(1002);
        stopBtn.setText("停止");
        root.addView(stopBtn, lp);

        alphaBtn = new Button(this);
        alphaBtn.setId(1003);
        alphaBtn.setText("alpha: " + currentAlpha);
        root.addView(alphaBtn, lp);

        return root;
    }

    private void cycleAlpha() {
        int idx = -1;
        for (int i = 0; i < ALPHAS.length; i++) if (ALPHAS[i] == currentAlpha) idx = i;
        idx = (idx + 1) % ALPHAS.length;
        currentAlpha = ALPHAS[idx];
        getSharedPreferences("probe", MODE_PRIVATE).edit().putFloat("alpha", currentAlpha).apply();
        alphaBtn.setText("alpha: " + currentAlpha);
        // if service running, push new alpha
        Intent i = new Intent(this, ProbeService.class).putExtra("setAlpha", currentAlpha);
        startService(i);
        Toast.makeText(this, "alpha=" + currentAlpha, Toast.LENGTH_SHORT).show();
    }

    private void requestAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), 200);
            updateStatus("请先授权悬浮窗");
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            updateStatus("等待相机授权");
            return;
        }
        ensureNotificationPermission();
        startServiceWithForeground();
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    private void startServiceWithForeground() {
        try {
            Intent i = new Intent(this, ProbeService.class).putExtra("alpha", currentAlpha);
            startForegroundService(i);
            updateStatus("服务已启动，悬浮摄像头应出现");
        } catch (Exception e) {
            updateStatus("启动失败: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 200) {
            if (Settings.canDrawOverlays(this)) requestAndStart();
            else updateStatus("悬浮窗权限未授予");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] results) {
        super.onRequestPermissionsResult(requestCode, perms, results);
        if (requestCode == REQ_CAMERA && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            ensureNotificationPermission();
            startServiceWithForeground();
        }
    }

    private void updateStatus(String s) {
        if (statusText != null) statusText.setText(s);
    }

    private void keepScreenOn() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isWakeLockLevelSupported(PowerManager.SCREEN_BRIGHT_WAKE_LOCK)) {
            // keep simple, rely on user not locking during test
        }
    }
}
