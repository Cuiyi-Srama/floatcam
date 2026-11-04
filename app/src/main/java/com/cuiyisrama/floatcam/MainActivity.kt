package com.cuiyisrama.floatcam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuiyisrama.floatcam.service.OverlayBinder
import com.cuiyisrama.floatcam.service.OverlayService

class MainActivity : ComponentActivity() {

    private var bound = false
    private var binder: OverlayBinder? = null
    private var isServiceRunning by mutableStateOf(false)
    private var isEditing by mutableStateOf(true)
    private var cameraAlpha by mutableFloatStateOf(0.7f)
    private var isFrontCamera by mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as? OverlayBinder
            bound = true
            isServiceRunning = true
            // 读取当前状态
            isEditing = binder?.isEditing() ?: true
            isFrontCamera = binder?.isFrontCamera() ?: false
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            bound = false
            isServiceRunning = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        isRunning = isServiceRunning,
                        isEditing = isEditing,
                        isFrontCamera = isFrontCamera,
                        cameraAlpha = cameraAlpha,
                        onStart = { startOverlay() },
                        onStop = { stopOverlay() },
                        onToggleEdit = { toggleEdit() },
                        onAlphaChange = { setAlpha(it) },
                        onSwitchCamera = { switchCamera() }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        probeService()
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    /** 探测服务是否存活：不带 BIND_AUTO_CREATE（flags=0），避免误启动服务 */
    private fun probeService() {
        try {
            val ok = bindService(
                Intent(this, OverlayService::class.java),
                connection,
                0  // 不带 BIND_AUTO_CREATE：服务未运行时不会创建
            )
            if (!ok) {
                isServiceRunning = false
                return
            }
        } catch (_: Exception) {
            isServiceRunning = false
        }
    }

    private fun startOverlay() {
        // 权限前置（三关顺序检查链）
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
            )
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 100)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }
        try {
            startForegroundService(Intent(this, OverlayService::class.java))
            isServiceRunning = true
            // 启动后主动重新绑定，确保 binder 非 null（否则滑条/按钮失效）
            probeService()
        } catch (_: Exception) {
            isServiceRunning = false
        }
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
        isServiceRunning = false
    }

    /** 编辑/锁定切换（App 内控制，不依赖悬浮球） */
    private fun toggleEdit() {
        val b = binder ?: return
        val next = !isEditing
        b.setEditing(next)
        isEditing = next
    }

    /** 透明度滑条（App 内控制） */
    private fun setAlpha(value: Float) {
        cameraAlpha = value
        binder?.setCameraAlpha(value)
    }

    /** 切换前后置摄像头 */
    private fun switchCamera() {
        binder?.switchCamera()
        isFrontCamera = !isFrontCamera
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 || requestCode == 101) {
            startOverlay()
        }
    }
}

@Composable
private fun MainScreen(
    isRunning: Boolean,
    isEditing: Boolean,
    isFrontCamera: Boolean,
    cameraAlpha: Float,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onToggleEdit: () -> Unit,
    onAlphaChange: (Float) -> Unit,
    onSwitchCamera: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "浮窗相机", fontSize = 28.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (isRunning) "服务运行中" else "服务未运行",
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = if (isRunning) onStop else onStart) {
            Text(if (isRunning) "停止服务" else "启动服务")
        }

        // 服务运行时的控制面板（App 内控制，无悬浮球）
        if (isRunning) {
            Spacer(modifier = Modifier.height(32.dp))
            Text(text = "窗口控制", fontSize = 16.sp)
            Spacer(modifier = Modifier.height(12.dp))

            Button(onClick = onToggleEdit) {
                Text(if (isEditing) "锁定（穿透）" else "编辑（可拖动）")
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onSwitchCamera) {
                Text(if (isFrontCamera) "切换：后置摄像头" else "切换：前置摄像头")
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(text = "透明度: ${"%.2f".format(cameraAlpha)}", fontSize = 14.sp)
            Slider(
                value = cameraAlpha,
                onValueChange = onAlphaChange,
                valueRange = 0.3f..1.0f,
                modifier = Modifier.width(260.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "提示：低透明度更可能穿透，但画面更淡", fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "悬浮摄像头与水印图层", fontSize = 12.sp)
    }
}