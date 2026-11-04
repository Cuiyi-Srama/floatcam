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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuiyisrama.floatcam.service.OverlayService
import com.cuiyisrama.floatcam.service.OverlayBinder
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    private var bound = false
    private var binder: OverlayBinder? = null
    private var isServiceRunning by mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as? OverlayBinder
            bound = true
            isServiceRunning = true
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
                        onStart = { startOverlay() },
                        onStop = { stopOverlay() }
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

    /** 探测服务是否存活：不带 BIND_AUTO_CREATE，避免误启动 */
    private fun probeService() {
        try {
            bindService(
                Intent(this, OverlayService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
        } catch (_: Exception) {
            isServiceRunning = false
        }
    }

    private fun startOverlay() {
        // 权限前置
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
            probeService()
        } catch (_: Exception) {
            // ForegroundServiceStartNotAllowedException 等：进入可控停止
        }
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
        isServiceRunning = false
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
    onStart: () -> Unit,
    onStop: () -> Unit
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
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "悬浮摄像头与水印图层", fontSize = 12.sp)
    }
}