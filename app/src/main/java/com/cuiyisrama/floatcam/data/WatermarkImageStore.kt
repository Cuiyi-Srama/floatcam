package com.cuiyisrama.floatcam.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * 水印图片存储（架构文档 8）。
 * - 相对路径存 filesDir/watermarks/
 * - EXIF 归一化（旋转修正）
 * - 采样解码基准：锁定态尺寸与屏幕长边 0.6 倍中的较大者，上限 4096px
 * - 复制失败/文件缺失/解码失败 → 提示并回退无图状态，不崩溃
 */
class WatermarkImageStore(private val context: Context) {

    companion object {
        private const val TAG = "FloatCam.WatermarkStore"
        private const val DIR_NAME = "watermarks"
        private const val MAX_DECODE_PX = 4096
    }

    private val dir: File
        get() = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** 把选中的图片复制到私有目录，返回相对文件名；失败返回 null */
    suspend fun importImage(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val name = "wm_${System.currentTimeMillis()}.png"
            val target = File(dir, name)
            val input: InputStream? = context.contentResolver.openInputStream(uri)
            input?.use { ins ->
                FileOutputStream(target).use { outs ->
                    ins.copyTo(outs)
                }
            } ?: return@withContext null
            name
        } catch (e: Exception) {
            Log.e(TAG, "importImage failed", e)
            null
        }
    }

    /** 按相对文件名解码为 Bitmap（EXIF 归一化 + 采样）。失败返回 null */
    suspend fun decodeImage(fileName: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val file = File(dir, fileName)
            if (!file.exists()) return@withContext null

            // 采样解码：先读边界，再按目标尺寸采样（#7：屏幕长边 0.6 倍，非仅宽度）
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val screenW = context.resources.displayMetrics.widthPixels
            val screenH = context.resources.displayMetrics.heightPixels
            val longEdge = maxOf(screenW, screenH)
            val target = (longEdge * 0.6f).toInt().coerceAtMost(MAX_DECODE_PX)

            var sample = 1
            while (bounds.outWidth / sample > target || bounds.outHeight / sample > target) {
                sample *= 2
            }

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return@withContext null

            // EXIF 归一化（#8：完整处理 8 方向，含翻转/转置）
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                    matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 ->
                    matrix.postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                    matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 ->
                    matrix.postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 ->
                    matrix.postRotate(270f)
                else -> {}
            }
            if (!matrix.isIdentity) {
                val transformed = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                if (transformed != bmp) {
                    bmp.recycle()
                    bmp = transformed
                }
            }
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "decodeImage failed", e)
            null
        }
    }

    /** 删除旧图片（替换水印时清理，架构 8） */
    fun deleteImage(fileName: String) {
        try {
            File(dir, fileName).delete()
        } catch (e: Exception) {
            Log.w(TAG, "deleteImage failed", e)
        }
    }
}