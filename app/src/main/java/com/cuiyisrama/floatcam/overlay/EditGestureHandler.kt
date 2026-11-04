package com.cuiyisrama.floatcam.overlay

import android.util.Log
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * 编辑态手势处理：单指拖动移动窗口，双指捏合缩放窗口。
 * 对应架构文档附录 A「双窗口手势锁」：ACTION_DOWN 时若已有其他窗口处于手势中则忽略。
 *
 * 仅编辑态生效；锁定态窗口 FLAG_NOT_TOUCHABLE，不进入此处理器。
 */
class EditGestureHandler(
    private val onMove: (dx: Int, dy: Int) -> Unit,
    private val onResize: (scale: Float) -> Unit,
    private val onGestureEnd: () -> Unit
) : View.OnTouchListener {

    companion object {
        private const val TAG = "FloatCam.Gesture"
        private const val MIN_DISTANCE_PX = 10f
        private const val SCALE_MIN = 0.5f
        private const val SCALE_MAX = 3f

        /** 手势锁：跨窗口共享——任一窗口手势中，其他窗口忽略（架构文档附录 A） */
        @Volatile
        var gestureActive: Boolean = false
            private set
    }

    // 拖动状态
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    // 缩放状态
    private var lastSpan = 0f
    private var currentScale = 1f
    private var scaling = false

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 手势锁：已有窗口在手势中则忽略本次
                if (gestureActive) return true
                gestureActive = true
                lastX = event.rawX
                lastY = event.rawY
                dragging = false
                scaling = false
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    // 双指：进入缩放模式，取消拖动
                    dragging = false
                    scaling = true
                    lastSpan = spacing(event)
                    currentScale = 1f
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (scaling && event.pointerCount >= 2) {
                    val span = spacing(event)
                    val delta = span - lastSpan
                    if (span > 0 && lastSpan > 0 && kotlin.math.abs(delta) > MIN_DISTANCE_PX) {
                        val scale = (delta / lastSpan).coerceIn(-0.05f, 0.05f)
                        currentScale = (currentScale + scale).coerceIn(SCALE_MIN, SCALE_MAX)
                        onResize(currentScale)
                    }
                    lastSpan = span
                    return true
                }
                if (!scaling) {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    if (dx != 0f || dy != 0f) {
                        if (!dragging && (kotlin.math.abs(dx) > MIN_DISTANCE_PX || kotlin.math.abs(dy) > MIN_DISTANCE_PX)) {
                            dragging = true
                        }
                        if (dragging) {
                            onMove(dx.toInt(), dy.toInt())
                        }
                    }
                    lastX = event.rawX
                    lastY = event.rawY
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gestureActive = false
                dragging = false
                scaling = false
                onGestureEnd()
                return true
            }
        }
        return true
    }

    private fun spacing(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val x0 = event.getX(0)
        val y0 = event.getY(0)
        val x1 = event.getX(1)
        val y1 = event.getY(1)
        return kotlin.math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
    }
}