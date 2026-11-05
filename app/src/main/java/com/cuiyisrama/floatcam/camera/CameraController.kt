package com.cuiyisrama.floatcam.camera

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.Camera
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
 * - ERROR_CAMERA_IN_USE 视为可恢复：监听 CameraState + CameraManager.AvailabilityCallback
 *   （不轮询，借鉴 LookRoad）；相机被抢占 → 回调 onCameraInUse(true)；恢复可用 → 自动 rebind
 * - 提供 rebindIfNeeded() 显式重绑，不依赖 PreviewView 自动恢复
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    /** 相机占用状态回调：true=被抢占（隐藏预览+提示），false=恢复可用 */
    private val onCameraInUseChanged: (Boolean) -> Unit
) {
    companion object {
        private const val TAG = "FloatCam.Camera"
    }

    private var camera: Camera? = null
    private var preview: Preview? = null
    private var bound = false
    private var previewViewRef: PreviewView? = null

    /** 当前镜头方向：true=前置，false=后置 */
    var useFrontCamera: Boolean = false
        private set

    /** 相机当前是否被占用 */
    var cameraInUse: Boolean = false
        private set

    private val executor: Executor = ContextCompat.getMainExecutor(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // CameraX 状态观察：ERROR_CAMERA_IN_USE
    private val cameraStateObserver = androidx.lifecycle.Observer<androidx.camera.core.CameraInfo.CameraState> { state ->
        if (state != null && state.type == androidx.camera.core.CameraInfo.CameraState.Type.OPENING &&
            state.error?.code == androidx.camera.core.CameraInfo.CameraState.ERROR_CAMERA_IN_USE) {
            Log.w(TAG, "ERROR_CAMERA_IN_USE -> hide preview")
            cameraInUse = true
            onCameraInUseChanged(true)
        }
    }

    // 系统级占用监听（不轮询）
    private val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            super.onCameraAvailable(cameraId)
            // 相机恢复可用：若之前被抢占，自动重绑
            if (cameraInUse) {
                Log.i(TAG, "camera available again -> rebind")
                cameraInUse = false
                onCameraInUseChanged(false)
                val view = previewViewRef
                if (view != null && !bound) {
                    mainHandler.post { bindToPreviewView(view) }
                }
            }
        }

        override fun onCameraUnavailable(cameraId: String) {
            super.onCameraUnavailable(cameraId)
            Log.w(TAG, "camera unavailable: $cameraId")
        }
    }

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
        previewViewRef = previewView
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
                cameraInUse = false
                Log.i(TAG, "camera bound, front=$useFrontCamera")
                // 注册 CameraState 观察
                camera?.cameraInfo?.cameraState?.observe(lifecycleOwner, cameraStateObserver)
                // 注册系统占用监听（幂等：重复注册同一回调会覆盖）
                try {
                    cameraManager.registerAvailabilityCallback(availabilityCallback, mainHandler)
                } catch (e: Exception) {
                    Log.w(TAG, "registerAvailabilityCallback failed", e)
                }
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
        try {
            cameraManager.unregisterAvailabilityCallback(availabilityCallback)
        } catch (e: Exception) {
            Log.w(TAG, "unregister callback error", e)
        }
    }
}