package com.cuiyisrama.floatcam.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cuiyisrama.floatcam.data.model.CameraWindowConfig
import com.cuiyisrama.floatcam.data.model.OverlayConfig
import com.cuiyisrama.floatcam.data.model.WatermarkWindowConfig
import com.cuiyisrama.floatcam.data.model.WindowGeometry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore(name = "floatcam_config")

/**
 * DataStore 持久化（架构文档 7）。
 * - 800ms 防抖：变更后延迟落盘
 * - 5s 最大防抖窗口：距首次未持久化变更超 5s 强制落盘一次
 * - 读取校验 + 损坏回退默认（架构 5.4 / 7.2）
 * - IO 线程执行，不阻塞主线程
 *
 * 注意：UI 不直接写 DataStore；服务运行期间不持续收集 DataStore Flow 覆盖 StateFlow（避免写回环）。
 */
class ConfigRepository(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var pendingJob: Job? = null
    private var forceFlushJob: Job? = null
    private var firstChangeTime: Long = 0

    // ---- keys ----
    private val keyX = floatPreferencesKey("geometry_x_ratio")
    private val keyY = floatPreferencesKey("geometry_y_ratio")
    private val keyW = floatPreferencesKey("geometry_w_ratio")
    private val keyH = floatPreferencesKey("geometry_h_ratio")
    private val keyAlpha = floatPreferencesKey("geometry_alpha")
    private val keyFrontCamera = booleanPreferencesKey("camera_front")
    private val keyCameraVisible = booleanPreferencesKey("camera_visible")
    private val keyWatermarkVisible = booleanPreferencesKey("watermark_visible")
    private val keyWatermarkAlpha = floatPreferencesKey("watermark_alpha")
    private val keyWatermarkPath = stringPreferencesKey("watermark_path")
    private val keyEditing = booleanPreferencesKey("editing")
    private val keySchema = intPreferencesKey("schema_version")

    /** 读取配置（迁移 + 损坏回退）。IO 线程执行。 */
    suspend fun load(): OverlayConfig {
        return try {
            val prefs = context.dataStore.data.first()
            OverlayConfig(
                camera = CameraWindowConfig(
                    useFrontCamera = prefs[keyFrontCamera] ?: false,
                    visible = prefs[keyCameraVisible] ?: true
                ),
                watermark = WatermarkWindowConfig(
                    visible = prefs[keyWatermarkVisible] ?: false,
                    alpha = prefs[keyWatermarkAlpha] ?: 0.75f,
                    imagePath = prefs[keyWatermarkPath]
                ),
                geometry = WindowGeometry(
                    xRatio = prefs[keyX] ?: 0.15f,
                    yRatio = prefs[keyY] ?: 0.2f,
                    widthRatio = prefs[keyW] ?: 0.3f,
                    heightRatio = prefs[keyH] ?: 0.4f,
                    alpha = prefs[keyAlpha] ?: WindowGeometry.DEFAULT_ALPHA
                ),
                editing = prefs[keyEditing] ?: true
            ).sanitized()
        } catch (e: Exception) {
            // 损坏/解析失败 → 回退默认（架构 7.2）
            OverlayConfig()
        }
    }

    /** 保存配置：800ms 防抖 + 5s 最大防抖窗口（架构 7.1） */
    fun save(config: OverlayConfig) {
        pendingJob?.cancel()

        if (firstChangeTime == 0L) {
            firstChangeTime = System.currentTimeMillis()
        }

        // 800ms 防抖后落盘
        pendingJob = scope.launch {
            delay(800)
            firstChangeTime = 0L
            forceFlushJob?.cancel()
            forceFlushJob = null
            persist(config)
        }

        // 5s 最大防抖窗口：距首次变更超 5s 仍未落盘，强制落盘
        if (forceFlushJob == null) {
            forceFlushJob = scope.launch {
                delay(5000)
                if (firstChangeTime != 0L && System.currentTimeMillis() - firstChangeTime >= 5000) {
                    firstChangeTime = 0L
                    pendingJob?.cancel()
                    pendingJob = null
                    persist(config)
                }
            }
        }
    }

    /** 立即落盘（服务销毁前调用，架构 6.8 / 7.3） */
    fun saveImmediate(config: OverlayConfig) {
        pendingJob?.cancel()
        pendingJob = null
        forceFlushJob?.cancel()
        forceFlushJob = null
        firstChangeTime = 0L
        scope.launch { persist(config) }
    }

    private suspend fun persist(config: OverlayConfig) {
        context.dataStore.edit { prefs ->
            prefs[keyX] = config.geometry.xRatio
            prefs[keyY] = config.geometry.yRatio
            prefs[keyW] = config.geometry.widthRatio
            prefs[keyH] = config.geometry.heightRatio
            prefs[keyAlpha] = config.geometry.alpha
            prefs[keyFrontCamera] = config.camera.useFrontCamera
            prefs[keyCameraVisible] = config.camera.visible
            prefs[keyWatermarkVisible] = config.watermark.visible
            prefs[keyWatermarkAlpha] = config.watermark.alpha
            if (config.watermark.imagePath != null) {
                prefs[keyWatermarkPath] = config.watermark.imagePath
            }
            prefs[keyEditing] = config.editing
            prefs[keySchema] = 1
        }
    }
}