package com.cuiyisrama.step0probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Collections;

public class ProbeService extends Service {

    private static final String TAG = "Step0Probe";
    private static final String CHANNEL_ID = "probe";
    private static final int NOTIF_ID = 1;

    private WindowManager windowManager;
    private TextureView textureView;
    private TextView alphaLabel;
    private WindowManager.LayoutParams windowParams;
    private float alpha = 0.75f;

    private CameraManager cameraManager;
    private String cameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewBuilder;
    private HandlerThread bgThread;
    private Handler bgHandler;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        bgThread = new HandlerThread("probe-cam");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());

        createNotificationChannel();
        startForegroundCompat();

        buildOverlayWindow();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (intent.hasExtra("setAlpha")) {
                alpha = intent.getFloatExtra("setAlpha", 0.75f);
                updateWindowAlpha();
            }
            if (intent.hasExtra("alpha")) {
                alpha = intent.getFloatExtra("alpha", 0.75f);
                updateWindowAlpha();
            }
        }
        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Step0Probe", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("FloatCam Step 0 probe");
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(ch);
    }

    private void startForegroundCompat() {
        Notification notif = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            } else {
                startForeground(NOTIF_ID, notif);
            }
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
            stopSelf();
        }
    }

    private Notification buildNotification() {
        Intent launch = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Step0Probe")
                .setContentText("悬浮摄像头探针运行中")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void buildOverlayWindow() {
        textureView = new TextureView(this);
        alphaLabel = new TextView(this);
        alphaLabel.setText("alpha=" + alpha);
        alphaLabel.setTextSize(14);
        alphaLabel.setBackgroundColor(0x80000000);
        alphaLabel.setTextColor(0xFFFFFFFF);
        alphaLabel.setPadding(8, 4, 8, 4);

        // container: relative layout wrapping texture + label
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        FrameLayout.LayoutParams texLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        container.addView(textureView, texLp);
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        container.addView(alphaLabel, labelLp);

        windowParams = new WindowManager.LayoutParams(
                240, 320,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        windowParams.gravity = Gravity.TOP | Gravity.START;
        windowParams.x = 60;
        windowParams.y = 200;
        windowParams.alpha = alpha;

        try {
            windowManager.addView(container, windowParams);
            Log.i(TAG, "overlay added, alpha=" + alpha);
        } catch (Exception e) {
            Log.e(TAG, "addView failed", e);
            stopSelf();
            return;
        }

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                openCamera();
            }
            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) {
            }
            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                closeCamera();
                return true;
            }
            @Override
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {
            }
        });
    }

    private void updateWindowAlpha() {
        if (windowParams != null) {
            windowParams.alpha = alpha;
            View container = getWindowContainer();
            try {
                if (container != null) windowManager.updateViewLayout(container, windowParams);
            } catch (Exception e) {
                Log.e(TAG, "updateViewLayout failed", e);
            }
            if (alphaLabel != null) alphaLabel.setText("alpha=" + alpha);
        }
    }

    private View getWindowContainer() {
        // container is first (and only) view added; we keep reference via textureView.getParent()
        if (textureView != null && textureView.getParent() instanceof View) {
            return (View) textureView.getParent();
        }
        return null;
    }

    private void openCamera() {
        try {
            cameraId = pickBackCamera();
            if (cameraId == null) {
                Log.e(TAG, "no back camera");
                return;
            }
            if (checkSelfPermission(android.Manifest.permission.CAMERA) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "no camera permission in service");
                return;
            }
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    startPreview();
                }
                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }
                @Override
                public void onError(CameraDevice camera, int error) {
                    Log.e(TAG, "camera error " + error);
                    camera.close();
                    cameraDevice = null;
                }
            }, bgHandler);
        } catch (Exception e) {
            Log.e(TAG, "openCamera failed", e);
        }
    }

    private String pickBackCamera() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics cc = cameraManager.getCameraCharacteristics(id);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "pickBackCamera failed", e);
        }
        // fallback: first camera
        try {
            return cameraManager.getCameraIdList()[0];
        } catch (Exception e) {
            return null;
        }
    }

    private void startPreview() {
        if (cameraDevice == null || textureView == null) return;
        try {
            Surface surface = new Surface(textureView.getSurfaceTexture());
            previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(surface);
            cameraDevice.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                captureSession.setRepeatingRequest(previewBuilder.build(), null, bgHandler);
                            } catch (Exception e) {
                                Log.e(TAG, "setRepeating failed", e);
                            }
                        }
                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Log.e(TAG, "configure failed");
                        }
                    }, bgHandler);
        } catch (Exception e) {
            Log.e(TAG, "startPreview failed", e);
        }
    }

    private void closeCamera() {
        try {
            if (captureSession != null) { captureSession.close(); captureSession = null; }
            if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
        } catch (Exception e) {
            Log.e(TAG, "closeCamera error", e);
        }
    }

    @Override
    public void onDestroy() {
        closeCamera();
        try {
            if (textureView != null) {
                View parent = getWindowContainer();
                if (parent != null) windowManager.removeView(parent);
            }
        } catch (Exception e) {
            Log.e(TAG, "removeView error", e);
        }
        if (bgThread != null) bgThread.quitSafely();
        super.onDestroy();
    }
}