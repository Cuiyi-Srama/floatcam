package com.cuiyisrama.floatcam.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.camera.view.PreviewView

/**
 * 统一管理摄像头与（未来）水印两个悬浮窗口。
 * addView / updateViewLayout / removeView 全部 try-catch，异常不崩溃（架构文档 6.5）。
 *
 * 两态（架构文档 1.3）：
 * - Editing：FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL，挂手势处理器（拖动/缩放）
 * - Locked：FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE，目标穿透（实际以 Step 0-A 组 B 实测为准）
 */
class OverlayWindowController(
    private val context: Context
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

    /** 摄像头窗口是否可见 */
    var isCameraVisible: Boolean = false
        private set

    /** 当前是否编辑态 */
    var editing: Boolean = true
        private set

    /**
     * 添加摄像头悬浮窗口。窗口内（含透明像素区域）拦截触摸，是矩形窗口固有行为。
     */
    fun addCameraWindow(previewView: PreviewView): Boolean {
        if (cameraWindow != null) {
            Log.w(TAG, "camera window already exists")
            return false
        }
        cameraPreviewView = previewView

        val params = WindowManager.LayoutParams(
            CAMERA_WIDTH_PX,
            CAMERA_HEIGHT_PX,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 200
            alpha = 0.54f
        }
        cameraParams = params

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
                // scale=1f 时记录基准（双指刚按下）；之后按基准 * 因子平滑缩放
                if (scale <= 1.0001f && scale >= 0.9999f && baseWidth == 0) {
                    baseWidth = params.width
                    baseHeight = params.height
                    return@EditGestureHandler
                }
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
            onGestureEnd = {
                // TODO(批次 6): 手势结束触发 DataStore 800ms 防抖持久化
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

    /** 移除全部窗口 */
    fun removeAll() {
        removeCameraWindow()
        // TODO(批次 7): removeWatermarkWindow()
    }
}