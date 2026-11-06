package com.cuiyisrama.floatcam.service

import android.os.Binder

/**
 * 服务控制接口。configState 为运行时唯一状态源（对应架构文档附录 C）。
 */
class OverlayBinder(
    private val service: OverlayService
) : Binder() {

    /** 当前是否持有相机（决定 FGS 类型 camera vs specialUse） */
    fun isCameraActive(): Boolean = service.isCameraActive()

    /** 当前是否编辑态 */
    fun isEditing(): Boolean = service.isEditing()

    /** 切换编辑/锁定态 */
    fun setEditing(enabled: Boolean) {
        service.setEditing(enabled)
    }

    /** 设置摄像头窗口透明度 */
    fun setCameraAlpha(alpha: Float) {
        service.setCameraAlpha(alpha)
    }

    /** 切换前后置摄像头 */
    fun switchCamera() {
        service.switchCamera()
    }

    /** 当前是否前置摄像头 */
    fun isFrontCamera(): Boolean = service.isFrontCamera()

    /** 水印开关 */
    fun setWatermarkVisible(visible: Boolean, bitmap: android.graphics.Bitmap? = null) {
        service.setWatermarkVisible(visible, bitmap)
    }

    /** 导入水印图片 */
    fun setWatermarkImage(uri: android.net.Uri) {
        service.setWatermarkImage(uri)
    }

    /** 水印当前是否可见 */
    fun isWatermarkVisible(): Boolean = service.isWatermarkVisible()

    fun updateFoldingState(state: androidx.window.layout.FoldingFeature?) {
        service.updateFoldingState(state)
    }
}