package com.cuiyisrama.floatcam.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.camera.view.PreviewView

/**
 * 统一管理摄像头与（未来）水印两个悬浮窗口。
 * addView / updateViewLayout / removeView 全部 try-catch，异常不崩溃（架构文档 6.5）。
 */
class OverlayWindowController(
    private val context: Context
) {
    companion object {
        private const val TAG = "FloatCam.Window"
        const val CAMERA_WIDTH_PX = 240
        const val CAMERA_HEIGHT_PX = 320
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var cameraWindow: View? = null
    private var cameraParams: WindowManager.LayoutParams? = null
    private var cameraPreviewView: PreviewView? = null

    /** 摄像头窗口是否可见 */
    var isCameraVisible: Boolean = false
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
            alpha = 0.75f
        }
        cameraParams = params

        try {
            windowManager.addView(previewView, params)
            cameraWindow = previewView
            isCameraVisible = true
            Log.i(TAG, "camera window added")
            return true
        } catch (e: Exception) {
            // BadTokenException / IllegalArgumentException / SecurityException 全兜底
            Log.e(TAG, "addView failed", e)
            cameraWindow = null
            cameraParams = null
            cameraPreviewView = null
            isCameraVisible = false
            return false
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