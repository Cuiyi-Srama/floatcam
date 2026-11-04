package com.cuiyisrama.floatcam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleService
import com.cuiyisrama.floatcam.MainActivity
import com.cuiyisrama.floatcam.camera.CameraController
import com.cuiyisrama.floatcam.overlay.OverlayWindowController

/**
 * 前台服务，持有运行时唯一状态源（对应架构文档 6.3 动态类型切换）。
 *
 * FGS 类型策略：
 * - 摄像头窗口可见、相机绑定中 → camera
 * - 摄像头窗口隐藏、相机已 unbind、水印可见 → specialUse（重新 startForeground 切换）
 * - 双窗口均不可见 → 停止服务
 */
class OverlayService : LifecycleService() {

    companion object {
        private const val TAG = "FloatCam"
        private const val CHANNEL_ID = "floatcam"
        private const val NOTIF_ID = 1
    }

    private var binder: OverlayBinder? = null
    private var cameraActive = false
    private var watermarkVisible = false

    private lateinit var windowController: OverlayWindowController
    private lateinit var cameraController: CameraController
    private var previewView: PreviewView? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat()

        windowController = OverlayWindowController(this)
        cameraController = CameraController(this, this)

        buildCameraWindow()
        Log.i(TAG, "OverlayService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TODO(批次 2+): 处理通知 Action 与生命周期事件（SCREEN_OFF/ON、onTaskRemoved）
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        val b = OverlayBinder(this)
        binder = b
        return b
    }

    /** 构建摄像头悬浮窗并绑定相机（架构文档 6.2 第 4 步） */
    private fun buildCameraWindow() {
        val view = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        previewView = view

        val added = windowController.addCameraWindow(view)
        if (!added) {
            Log.e(TAG, "camera window add failed -> controlled stop")
            stopSelf()
            return
        }
        // 窗口添加成功后才绑定相机，避免人为拉长 while-in-use 窗口期
        cameraController.bindToPreviewView(view) {
            onCameraStateChanged(true)
        }
    }

    /** 有相机 → camera 类型；仅水印 → specialUse 类型 */
    private fun startForegroundCompat() {
        val notif = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val type = if (cameraActive) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                startForeground(NOTIF_ID, notif, type)
            } else if (Build.VERSION.SDK_INT >= 29) {
                // API 29-33 无 SPECIAL_USE 常量，用 MANIFEST 声明的类型（低版本行为不做强承诺）
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
        }
    }

    /** 相机开关变化后调用，动态切换 FGS 类型（再次调用 startForeground） */
    private fun refreshForegroundType() {
        if (Build.VERSION.SDK_INT < 29) return
        val notif = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val type = if (cameraActive) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                startForeground(NOTIF_ID, notif, type)
            } else {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
            }
        } catch (e: Exception) {
            Log.e(TAG, "refreshForegroundType failed", e)
        }
    }

    private fun buildNotification(): Notification {
        val launch = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("浮窗相机")
            .setContentText(if (cameraActive) "摄像头悬浮窗运行中" else "水印悬浮窗运行中")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "浮窗相机", NotificationManager.IMPORTANCE_LOW
        )
        ch.description = "浮窗相机前台服务"
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun isCameraActive(): Boolean = cameraActive

    fun updateFoldingState(state: Any?) {
        // TODO(批次 9): FoldingFeature 保守夹取，与 Insets 机制分离
    }

    /** 相机开关（CameraController 回调） */
    fun onCameraStateChanged(active: Boolean) {
        if (cameraActive == active) return
        cameraActive = active
        refreshForegroundType()
    }

    override fun onDestroy() {
        cameraController.unbindAll()
        windowController.removeAll()
        Log.i(TAG, "OverlayService destroyed")
        super.onDestroy()
    }
}