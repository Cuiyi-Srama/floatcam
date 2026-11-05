package com.cuiyisrama.floatcam.data.model

/**
 * 摄像头窗口几何配置（架构文档 5 坐标模型）。
 * 尺寸用短边归一化比例；位置用屏幕宽高比例（旋转后不越界跳顶）。
 */
data class WindowGeometry(
    val xRatio: Float = 0.15f,      // 位置：屏幕宽比例
    val yRatio: Float = 0.2f,       // 位置：屏幕高比例
    val widthRatio: Float = 0.3f,   // 尺寸：短边比例
    val heightRatio: Float = 0.4f,  // 尺寸：短边比例
    val alpha: Float = 0.54f        // 窗口不透明度（vivo 实测穿透分界点）
) {
    companion object {
        const val DEFAULT_ALPHA = 0.54f
        const val ALPHA_MIN = 0.3f
        const val ALPHA_MAX = 1.0f
        const val RATIO_MIN = 0.05f
        const val RATIO_MAX = 1.0f
    }

    /** 校验并回退（架构 5.4：非法值回退默认） */
    fun sanitized(): WindowGeometry {
        fun validRatio(v: Float) = v.isFinite() && v in RATIO_MIN..RATIO_MAX
        return WindowGeometry(
            xRatio = if (validRatio(xRatio)) xRatio else 0.15f,
            yRatio = if (validRatio(yRatio)) yRatio else 0.2f,
            widthRatio = if (validRatio(widthRatio)) widthRatio else 0.3f,
            heightRatio = if (validRatio(heightRatio)) heightRatio else 0.4f,
            alpha = if (alpha.isFinite() && alpha in ALPHA_MIN..ALPHA_MAX) alpha else DEFAULT_ALPHA
        )
    }
}

/**
 * 摄像头窗口配置（镜头方向等）。
 */
data class CameraWindowConfig(
    val useFrontCamera: Boolean = false,
    val visible: Boolean = true
)

/**
 * 水印窗口配置（批次 7 使用，当前留默认）。
 */
data class WatermarkWindowConfig(
    val visible: Boolean = false,
    val alpha: Float = 0.75f,
    val imagePath: String? = null
)

/**
 * 运行时唯一状态源（架构文档附录 C：OverlayService 持有 MutableStateFlow<OverlayConfig>）。
 * DataStore 仅持久化，不直接驱动窗口。
 */
data class OverlayConfig(
    val camera: CameraWindowConfig = CameraWindowConfig(),
    val watermark: WatermarkWindowConfig = WatermarkWindowConfig(),
    val geometry: WindowGeometry = WindowGeometry(),
    val editing: Boolean = true
) {
    /** 读取校验：非法值回退默认（架构 5.4） */
    fun sanitized(): OverlayConfig = OverlayConfig(
        camera = camera,
        watermark = watermark,
        geometry = geometry.sanitized(),
        editing = editing
    )
}