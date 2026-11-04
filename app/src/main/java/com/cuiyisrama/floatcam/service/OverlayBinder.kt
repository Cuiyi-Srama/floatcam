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

    fun updateFoldingState(state: Any?) {
        service.updateFoldingState(state)
    }
}