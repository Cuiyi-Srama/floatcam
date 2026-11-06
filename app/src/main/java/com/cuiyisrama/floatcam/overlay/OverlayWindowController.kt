package com.cuiyisrama.floatcam.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.graphics.Insets
import android.view.WindowManager
import androidx.camera.view.PreviewView
import com.cuiyisrama.floatcam.data.model.WindowGeometry
import com.cuiyisrama.floatcam.util.RectPx
import com.cuiyisrama.floatcam.util.toPx

/**
 * 统一管理摄像头与（未来）水印两个悬浮窗口。
 * addView / updateViewLayout / removeView 全部 try-catch，异常不崩溃（架构文档 6.5）。
 *
 * 两态（架构文档 1.3）：
 * - Editing：FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL，挂手势处理器（拖动/缩放）
 * - Locked：FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE，目标穿透（实际以 Step 0-A 组 B 实测为准）
 */
class OverlayWindowController(
    private val context: Context,
    /** 手势结束回调：把最新几何（比例模型）通知上层持久化 */
    private val onGeometryChanged: ((xRatio: Float, yRatio: Float, wRatio: Float, hRatio: Float) -> Unit)? = null
) {
    companion object {
        private const val TAG = "FloatCam.Window"
        private const val CAMERA_WIDTH_PX = 240
        private const val CAMERA_HEIGHT_PX = 320
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var cameraWindow: View? = null
    private var cameraParams: WindowManager.LayoutParams? = null
    private var cameraPreviewView: PreviewView? = null
    private var gestureHandler: EditGestureHandler? = null
    private var watermarkWindow: WatermarkOverlayWindow? = null

    /** 关联水印窗口（供 removeAll 统一清理） */
    fun attachWatermarkWindow(watermark: WatermarkOverlayWindow) {
        watermarkWindow = watermark
    }

    /** 摄像头窗口是否可见 */
    var isCameraVisible: Boolean = false
        private set

    /** 当前是否编辑态 */
    var editing: Boolean = true
        private set

    /**
     * 添加摄像头悬浮窗口。窗口内（含透明像素区域）拦截触摸，是矩形窗口固有行为。
     */
    fun addCameraWindow(previewView: PreviewView, geometry: WindowGeometry = WindowGeometry()): Boolean {
        if (cameraWindow != null) {
            Log.w(TAG, "camera window already exists")
            return false
        }
        cameraPreviewView = previewView

        // 配置驱动：用归一化几何 + Insets 夹取计算像素（架构 5）
        val rect = computeGeometry(geometry)
        val params = WindowManager.LayoutParams(
            rect.w,
            rect.h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = rect.x
            y = rect.y
            alpha = geometry.alpha.coerceIn(0.3f, 1f)
        }
        cameraParams = params
        currentGeometry = geometry

        try {
            windowManager.addView(previewView, params)
            cameraWindow = previewView
            isCameraVisible = true
            attachGesture(previewView)
            Log.i(TAG, "camera window added")
            return true
        } catch (e: Exception) {
            // BadTokenException / IllegalArgumentException / SecurityException 全兜底
            Log.e(TAG, "addView failed", e)
            cameraWindow = null
            cameraParams = null
            cameraPreviewView = null
            gestureHandler = null
            isCameraVisible = false
            return false
        }
    }

    /** 当前生效几何（配置驱动） */
    private var currentGeometry: WindowGeometry = WindowGeometry()

    /** 用屏幕尺寸 + Insets 夹取计算窗口像素（架构 5.2 toPx） */
    private fun computeGeometry(geometry: WindowGeometry): RectPx {
        val display = context.resources.displayMetrics
        val screenW = display.widthPixels.coerceAtLeast(1)
        val screenH = display.heightPixels.coerceAtLeast(1)
        // Insets：状态栏/导航栏/挖孔
        var insetsL = 0; var insetsT = 0; var insetsR = 0; var insetsB = 0
        try {
            val metrics = WindowManager.LayoutParams().apply {
                // 用 WindowManager 当前窗口度量
            }
            @Suppress("DEPRECATION")
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val currentInsets = if (android.os.Build.VERSION.SDK_INT >= 30) {
                wm.currentWindowMetrics.windowInsets
            } else null
            if (currentInsets != null) {
                val bars = currentInsets.getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.systemBars() or
                        android.view.WindowInsets.Type.displayCutout()
                )
                insetsL = bars.left; insetsT = bars.top; insetsR = bars.right; insetsB = bars.bottom
            }
        } catch (e: Exception) {
            Log.w(TAG, "insets read failed, fallback 0", e)
        }
        return geometry.toPx(screenW, screenH, insetsL, insetsT, insetsR, insetsB)
    }

    /** 更新窗口几何（旋转/配置变化/恢复时按比例重算，架构 5.1） */
    fun updateCameraGeometry(geometry: WindowGeometry) {
        val window = cameraWindow ?: return
        val params = cameraParams ?: return
        val rect = computeGeometry(geometry)
        params.x = rect.x
        params.y = rect.y
        params.width = rect.w
        params.height = rect.h
        params.alpha = geometry.alpha.coerceIn(0.3f, 1f)
        currentGeometry = geometry
        try {
            windowManager.updateViewLayout(window, params)
        } catch (e: Exception) {
            Log.e(TAG, "updateCameraGeometry failed", e)
        }
    }

    /** 编辑态手势：单指拖动、双指缩放（仅编辑态生效） */
    private fun attachGesture(view: View) {
        // 手势起始基准尺寸（双指按下时记录，缩放按基准 * 因子平滑变化）
        var baseWidth = 0
        var baseHeight = 0

        val handler = EditGestureHandler(
            onMove = { dx, dy ->
                val params = cameraParams ?: return@EditGestureHandler
                params.x += dx
                params.y += dy
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (e: Exception) {
                    Log.e(TAG, "gesture move failed", e)
                }
            },
            onResize = { scale ->
                val params = cameraParams ?: return@EditGestureHandler
                if (baseWidth == 0) return@EditGestureHandler
                val w = (baseWidth * scale).toInt().coerceIn(80, 600)
                val h = (baseHeight * scale).toInt().coerceIn(120, 900)
                params.width = w
                params.height = h
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (e: Exception) {
                    Log.e(TAG, "gesture resize failed", e)
                }
            },
            // #9 修复：双指按下直接记录基准，不再依赖 scale==1f 浮点阈值
            onScaleStart = {
                val params = cameraParams
                if (params != null) {
                    baseWidth = params.width
                    baseHeight = params.height
                }
            },
            onGestureEnd = {
                // 手势结束：按屏幕尺寸换算比例，通知上层持久化（架构附录 C）
                onGeometryChanged?.let { cb ->
                    val params = cameraParams ?: return@EditGestureHandler
                    val display = context.resources.displayMetrics
                    val screenW = display.widthPixels.coerceAtLeast(1)
                    val screenH = display.heightPixels.coerceAtLeast(1)
                    val short = minOf(screenW, screenH).coerceAtLeast(1)
                    cb(
                        (params.x.toFloat() / screenW).coerceIn(0f, 1f),
                        (params.y.toFloat() / screenH).coerceIn(0f, 1f),
                        (params.width.toFloat() / short).coerceIn(0.05f, 1f),
                        (params.height.toFloat() / short).coerceIn(0.05f, 1f)
                    )
                }
            }
        )
        gestureHandler = handler
        view.setOnTouchListener(handler)
    }

    /** 切换两态：Editing（可触摸可手势）/ Locked（FLAG_NOT_TOUCHABLE 目标穿透） */
    fun setEditing(enabled: Boolean) {
        if (editing == enabled) return
        editing = enabled
        val params = cameraParams ?: return
        val window = cameraWindow ?: return
        params.flags = if (enabled) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            windowManager.updateViewLayout(window, params)
            Log.i(TAG, "editing=$enabled")
        } catch (e: Exception) {
            Log.e(TAG, "setEditing failed", e)
        }
    }

    /** 更新窗口透明度（0.0-1.0） */
    fun setCameraAlpha(alpha: Float) {
        val params = cameraParams ?: return
        val window = cameraWindow ?: return
        params.alpha = alpha.coerceIn(0f, 1f)
        try {
            windowManager.updateViewLayout(window, params)
        } catch (e: Exception) {
            Log.e(TAG, "updateViewLayout failed", e)
        }
    }

    /** 更新窗口位置 */
    fun moveCameraTo(x: Int, y: Int) {
        val params = cameraParams ?: return
        val window = cameraWindow ?: return
        params.x = x
        params.y = y
        try {
            windowManager.updateViewLayout(window, params)
        } catch (e: Exception) {
            Log.e(TAG, "moveCameraTo failed", e)
        }
    }

    /** 移除摄像头窗口（不崩溃） */
    fun removeCameraWindow() {
        val window = cameraWindow ?: return
        try {
            windowManager.removeView(window)
        } catch (e: Exception) {
            // 窗口可能已被系统回收，忽略
            Log.w(TAG, "removeView failed (likely already removed)", e)
        }
        cameraWindow = null
        cameraParams = null
        cameraPreviewView = null
        isCameraVisible = false
    }

    /** 移除全部窗口（含摄像头 + 水印，幂等） */
    fun removeAll() {
        removeCameraWindow()
        watermarkWindow?.removeWatermarkWindow()
    }
}