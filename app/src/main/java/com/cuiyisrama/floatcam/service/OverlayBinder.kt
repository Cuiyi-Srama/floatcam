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

    fun updateFoldingState(state: Any?) {
        service.updateFoldingState(state)
    }
}