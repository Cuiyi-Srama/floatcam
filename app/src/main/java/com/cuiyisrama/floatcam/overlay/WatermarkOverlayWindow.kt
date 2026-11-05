package com.cuiyisrama.floatcam.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView

/**
 * 水印悬浮窗口（架构文档 8）。
 * 独立于摄像头窗口，可分别开关。ImageView FIT_CENTER，窗口 alpha 与图片透明通道叠加。
 */
class WatermarkOverlayWindow(private val context: Context) {

    companion object {
        private const val TAG = "FloatCam.Watermark"
        private const val WM_WIDTH_PX = 160
        private const val WM_HEIGHT_PX = 160
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var watermarkView: ImageView? = null
    private var watermarkParams: WindowManager.LayoutParams? = null

    /** 水印窗口是否可见 */
    var isVisible: Boolean = false
        private set

    /**
     * 添加水印窗口。图片 Bitmap 由上层解码后传入。
     */
    fun addWatermarkWindow(bitmap: android.graphics.Bitmap?): Boolean {
        if (watermarkView != null) {
            Log.w(TAG, "watermark window already exists")
            return false
        }
        val imageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (bitmap != null) setImageBitmap(bitmap)
        }

        val params = WindowManager.LayoutParams(
            WM_WIDTH_PX,
            WM_HEIGHT_PX,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 600
            alpha = 0.54f
        }
        watermarkParams = params

        try {
            windowManager.addView(imageView, params)
            watermarkView = imageView
            isVisible = true
            Log.i(TAG, "watermark window added")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "addView failed", e)
            watermarkView = null
            watermarkParams = null
            isVisible = false
            return false
        }
    }

    /** 更新水印图片（替换后调用） */
    fun updateWatermarkImage(bitmap: android.graphics.Bitmap?) {
        val view = watermarkView ?: return
        if (bitmap != null) view.setImageBitmap(bitmap)
    }

    /** 更新窗口透明度 */
    fun setAlpha(alpha: Float) {
        val params = watermarkParams ?: return
        val view = watermarkView ?: return
        params.alpha = alpha.coerceIn(0f, 1f)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "updateViewLayout failed", e)
        }
    }

    /** 移除水印窗口（不崩溃） */
    fun removeWatermarkWindow() {
        val view = watermarkView ?: return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "removeView failed (likely already removed)", e)
        }
        watermarkView = null
        watermarkParams = null
        isVisible = false
    }
}