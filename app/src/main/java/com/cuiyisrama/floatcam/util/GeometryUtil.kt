package com.cuiyisrama.floatcam.util

import com.cuiyisrama.floatcam.data.model.WindowGeometry

/**
 * 坐标换算（架构文档 5.2）。
 * TYPE_APPLICATION_OVERLAY 的 x/y 原点不保证等于内容区域左上角；
 * 系统可能因避开状态栏/导航栏/手势条/DisplayCutout 而静默调整窗口位置。
 * toPx 在基础夹取之上叠加 Insets 夹取。
 */
data class RectPx(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int
)

fun WindowGeometry.toPx(
    screenWidth: Int,
    screenHeight: Int,
    insetsLeft: Int,
    insetsTop: Int,
    insetsRight: Int,
    insetsBottom: Int
): RectPx {
    val short = minOf(screenWidth, screenHeight).coerceAtLeast(1)
    val safeWidth = (screenWidth - insetsLeft - insetsRight).coerceAtLeast(1)
    val safeHeight = (screenHeight - insetsTop - insetsBottom).coerceAtLeast(1)

    val w = (widthRatio * short).toInt().coerceAtLeast(1)
        .coerceAtMost(safeWidth)
    val h = (heightRatio * short).toInt().coerceAtLeast(1)
        .coerceAtMost(safeHeight)

    val safeLeft = insetsLeft
    val safeTop = insetsTop
    val safeRight = screenWidth - insetsRight
    val safeBottom = screenHeight - insetsBottom

    val rawX = (xRatio * screenWidth).toInt()
    val rawY = (yRatio * screenHeight).toInt()

    val x = rawX.coerceIn(safeLeft, (safeRight - w).coerceAtLeast(safeLeft))
    val y = rawY.coerceIn(safeTop, (safeBottom - h).coerceAtLeast(safeTop))
    return RectPx(x, y, w, h)
}