package com.cuiyisrama.floatcam.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor

/**
 * CameraX 绑定/解绑与错误处理（架构文档 6.6）。
 * - SecurityException / CameraUnavailableException / InitializationException
 * - ERROR_CAMERA_IN_USE 视为可恢复：降级隐藏预览并提示
 * - 提供 rebindIfNeeded() 显式重绑，不依赖 PreviewView 自动恢复
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    companion object {
        private const val TAG = "FloatCam.Camera"
    }

    private var camera: Camera? = null
    private var preview: Preview? = null
    private var bound = false

    /** 当前镜头方向：true=前置，false=后置 */
    var useFrontCamera: Boolean = false
        private set

    private val executor: Executor = ContextCompat.getMainExecutor(context)

    /** 设置初始镜头方向（读配置后、绑定前调用） */
    fun setInitialCamera(front: Boolean) {
        useFrontCamera = front
    }

    /** 当前是否已绑定相机 */
    fun isBound(): Boolean = bound

    /** 当前镜头选择器 */
    private fun currentSelector(): CameraSelector =
        if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA

    /**
     * 绑定当前镜头到 PreviewView。成功后回调 onBound。
     */
    fun bindToPreviewView(previewView: PreviewView, onBound: (() -> Unit)? = null) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                preview = Preview.Builder().build()
                preview?.setSurfaceProvider(previewView.surfaceProvider)

                // 先解绑旧的，再绑定（切换镜头/重绑路径）
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner, currentSelector(), preview
                )
                bound = true
                Log.i(TAG, "camera bound, front=$useFrontCamera")
                onBound?.invoke()
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException (camera permission revoked?)", e)
                bound = false
            } catch (e: Exception) {
                // CameraUnavailableException / InitializationException
                Log.e(TAG, "bind failed", e)
                bound = false
            }
        }, executor)
    }

    /**
     * 切换前后置摄像头（架构文档 6.6：先 unbindAll 再 bindToLifecycle）。
     */
    fun switchCamera(previewView: PreviewView, onBound: (() -> Unit)? = null) {
        useFrontCamera = !useFrontCamera
        unbindAll()
        bindToPreviewView(previewView, onBound)
    }

    /**
     * 显式重绑（解锁恢复 / 相机被抢占后恢复）。先 unbindAll 再 bindToPreviewView。
     */
    fun rebindIfNeeded(previewView: PreviewView, onBound: (() -> Unit)? = null) {
        if (bound) {
            // 已绑定：无需重绑
            onBound?.invoke()
            return
        }
        bindToPreviewView(previewView, onBound)
    }

    /** 解绑所有相机，释放资源 */
    fun unbindAll() {
        try {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "unbindAll error", e)
        }
        camera = null
        preview = null
        bound = false
    }

    /** 是否发生相机被抢占（ERROR_CAMERA_IN_USE）——由 CameraX 回调决定，此处留扩展点 */
    fun isCameraInUseError(): Boolean {
        // TODO(批次 2 完善): CameraX 通过 CameraInfo 回调暴露，实际由 Camera 实例的 use case 状态判断
        return false
    }
}