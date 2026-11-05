package com.cuiyisrama.floatcam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.cuiyisrama.floatcam.MainActivity
import com.cuiyisrama.floatcam.camera.CameraController
import com.cuiyisrama.floatcam.data.ConfigRepository
import com.cuiyisrama.floatcam.data.WatermarkImageStore
import com.cuiyisrama.floatcam.data.model.OverlayConfig
import com.cuiyisrama.floatcam.overlay.WatermarkOverlayWindow
import com.cuiyisrama.floatcam.overlay.OverlayWindowController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch

/**
 * 前台服务，持有运行时唯一状态源（对应架构文档 6.3 动态类型切换）。
 *
 * 注意：不继承 LifecycleService——其 onBind 返回 void（内部拦截 Binder）。
 * 改为 Service + 手动实现 LifecycleOwner，供 CameraX bindToLifecycle 使用。
 *
 * FGS 类型策略：
 * - 摄像头窗口可见、相机绑定中 → camera
 * - 摄像头窗口隐藏、相机已 unbind、水印可见 → specialUse（重新 startForeground 切换）
 * - 双窗口均不可见 → 停止服务
 */
class OverlayService : Service(), LifecycleOwner {

    companion object {
        private const val TAG = "FloatCam"
        private const val CHANNEL_ID = "floatcam"
        private const val NOTIF_ID = 1

        /** 通知 Action（action 用字符串；requestCode 用 Int 且唯一） */
        const val ACTION_TOGGLE_EDIT = "floatcam.action.TOGGLE_EDIT"
        const val ACTION_STOP = "floatcam.action.STOP"
        const val REQ_TOGGLE_EDIT = 1001
        const val REQ_STOP = 1002
    }

    private val lifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private var binder: OverlayBinder? = null
    private var cameraActive = false
    private var watermarkVisible = false

    private lateinit var windowController: OverlayWindowController
    private lateinit var cameraController: CameraController
    private lateinit var configRepository: ConfigRepository
    private lateinit var watermarkWindow: WatermarkOverlayWindow
    private lateinit var watermarkStore: WatermarkImageStore
    private var previewView: PreviewView? = null

    /** 运行时唯一状态源（架构附录 C） */
    private val _configState = MutableStateFlow(OverlayConfig())
    val configState: StateFlow<OverlayConfig> = _configState.asStateFlow()
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        windowController = OverlayWindowController(this) { x, y, w, h ->
            // 手势结束：几何变更防抖持久化（架构附录 C）
            updateConfig {
                it.copy(geometry = it.geometry.copy(
                    xRatio = x, yRatio = y, widthRatio = w, heightRatio = h
                ))
            }
        }
        cameraController = CameraController(this, this)
        configRepository = ConfigRepository(this)
        watermarkWindow = WatermarkOverlayWindow(this)
        watermarkStore = WatermarkImageStore(this)

        createNotificationChannel()
        startForegroundCompat()

        // 异步读配置（架构 6.2 第 4 步）：IO 读 DataStore，主线程应用配置+建窗
        serviceScope.launch {
            val config = configRepository.load()
            _configState.value = config
            Handler(Looper.getMainLooper()).post {
                applyConfig(config)
                buildCameraWindow()
            }
        }
        Log.i(TAG, "OverlayService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知 Action 直送（服务存活时 getService；未初始化时普通 Action 忽略，架构文档 9）
        if (intent != null) {
            when (intent.action) {
                ACTION_TOGGLE_EDIT -> {
                    setEditing(!windowController.editing)
                    // 刷新通知文本
                    startForegroundCompat()
                }
                ACTION_STOP -> {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        val b = OverlayBinder(this)
        binder = b
        return b
    }

    /** 应用配置到窗口（几何/alpha/镜头方向/编辑态） */
    private fun applyConfig(config: OverlayConfig) {
        windowController.setCameraAlpha(config.geometry.alpha)
        if (!config.editing) {
            windowController.setEditing(false)
        }
        // 初始镜头方向（绑定前设置，buildCameraWindow 时生效）
        cameraController.setInitialCamera(config.camera.useFrontCamera)
        // 水印恢复（有图且配置可见时显示）
        if (config.watermark.visible && config.watermark.imagePath != null) {
            val bmp = runBlocking { watermarkStore.decodeImage(config.watermark.imagePath) }
            watermarkWindow.addWatermarkWindow(bmp)
            watermarkWindow.setAlpha(config.geometry.alpha)
        }
    }

    /** 更新状态源并防抖持久化（架构附录 C） */
    private fun updateConfig(transform: (OverlayConfig) -> OverlayConfig) {
        val next = transform(_configState.value)
        _configState.value = next
        configRepository.save(next)
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

        // 编辑/锁定切换（getService 直送，不离开当前界面）
        val toggleEdit = PendingIntent.getService(
            this, REQ_TOGGLE_EDIT,
            Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE_EDIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // 退出
        val stop = PendingIntent.getService(
            this, REQ_STOP,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val label = if (windowController.editing) "锁定" else "编辑"

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("浮窗相机")
            .setContentText(if (cameraActive) "摄像头悬浮窗运行中" else "水印悬浮窗运行中")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(0, label, toggleEdit)
            .addAction(0, "退出", stop)
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

    /** 切换编辑/锁定态（通知 Action / UI 调用） */
    fun setEditing(enabled: Boolean) {
        windowController.setEditing(enabled)
        updateConfig { it.copy(editing = enabled) }
    }

    /** 当前是否编辑态 */
    fun isEditing(): Boolean = windowController.editing

    /** 设置摄像头窗口透明度（UI 滑条） */
    fun setCameraAlpha(alpha: Float) {
        windowController.setCameraAlpha(alpha)
        updateConfig { it.copy(geometry = it.geometry.copy(alpha = alpha)) }
    }

    /** 切换前后置摄像头（UI 按钮） */
    fun switchCamera() {
        val view = previewView ?: return
        cameraController.switchCamera(view) {
            onCameraStateChanged(true)
        }
        updateConfig { it.copy(camera = it.camera.copy(useFrontCamera = cameraController.useFrontCamera)) }
    }

    /** 当前是否前置摄像头 */
    fun isFrontCamera(): Boolean = cameraController.useFrontCamera

    /** 水印开关 */
    fun setWatermarkVisible(visible: Boolean, bitmap: android.graphics.Bitmap? = null) {
        if (visible) {
            watermarkWindow.addWatermarkWindow(bitmap)
        } else {
            watermarkWindow.removeWatermarkWindow()
        }
        updateConfig { it.copy(watermark = it.watermark.copy(visible = visible)) }
    }

    /** 导入水印图片并显示 */
    fun setWatermarkImage(uri: android.net.Uri) {
        serviceScope.launch {
            val name = watermarkStore.importImage(uri) ?: return@launch
            val bmp = watermarkStore.decodeImage(name)
            Handler(Looper.getMainLooper()).post {
                watermarkWindow.addWatermarkWindow(bmp)
                updateConfig {
                    it.copy(watermark = it.watermark.copy(visible = true, imagePath = name))
                }
            }
        }
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        // 最后一次立即持久化（架构 6.8 释放顺序）
        configRepository.saveImmediate(_configState.value)
        cameraController.unbindAll()
        windowController.removeAll()
        watermarkWindow.removeWatermarkWindow()
        Log.i(TAG, "OverlayService destroyed")
        super.onDestroy()
    }
}