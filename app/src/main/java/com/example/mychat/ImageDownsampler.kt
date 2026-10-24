package com.example.mychat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

private const val IMG_TAG = "ImageDownsampler"

/** 压缩结果。base64 不含 data URL 前缀，需要时用 [toDataUrl]。 */
data class CompressedImage(
    val base64: String,
    val width: Int,
    val height: Int,
    val byteSize: Int,
    val mimeType: String = "image/jpeg"
) {
    /** 直接可塞进 OpenAI / Anthropic / Gemini 多模态 content 的 data URL。 */
    fun toDataUrl(): String = "data:$mimeType;base64,$base64"
}

/**
 * 纯原生大图降采样 + JPEG Base64，用于多模态上传。
 *
 * 全程只用 `BitmapFactory` / `Matrix` / `Bitmap.compress` / `android.media.ExifInterface`
 * （ExifInterface 自 API 24 起进入 framework，minSdk 24 无需 support 库），零第三方依赖。
 *
 * 防 OOM 的三道闸：
 * 1. 先 `inJustDecodeBounds` 只读尺寸，不分配像素；
 * 2. 用 2 的幂 `inSampleSize` 粗降采样，保证解出的长边落在 [maxEdge, 2*maxEdge)；
 * 3. 再用一次 `Bitmap.createBitmap(..., matrix, true)` 同时完成方向矫正 + 精确缩放，
 *    不产生中间 Bitmap，解完即 recycle。
 *
 * 峰值内存量级：约 (2*maxEdge)^2 * 4B，1024 时约 16MB（4:3 图约 12MB），安全区间内。
 */
object ImageDownsampler {

    const val DEFAULT_MAX_EDGE = 1024
    const val DEFAULT_TARGET_MAX_BYTES = 512 * 1024

    /** 质量阶梯：从高到低，取第一个不超预算的档位。 */
    private val QUALITY_LADDER = intArrayOf(85, 75, 65, 55, 45)

    /**
     * 把 [uri] 指向的图片降采样并压成 Base64。必须在 IO 上执行（内部已 withContext）。
     *
     * @param maxEdgePx   允许的最长边像素（等比，绝不放大）。
     * @param targetMaxBytes JPEG 体积上限，超出则沿质量阶梯下调。
     */
    suspend fun compressToBase64(
        context: Context,
        uri: Uri,
        maxEdgePx: Int = DEFAULT_MAX_EDGE,
        targetMaxBytes: Int = DEFAULT_TARGET_MAX_BYTES
    ): Result<CompressedImage> = withContext(Dispatchers.IO) {
        try {
            Result.success(decodeAndCompress(context, uri, maxEdgePx, targetMaxBytes))
        } catch (io: IOException) {
            Log.w(IMG_TAG, "图片压缩失败: $uri", io)
            Result.failure(io)
        } catch (t: Throwable) {
            Log.e(IMG_TAG, "图片压缩异常: $uri", t)
            Result.failure(t)
        }
    }

    private suspend fun decodeAndCompress(
        context: Context,
        uri: Uri,
        maxEdgePx: Int,
        targetMaxBytes: Int
    ): CompressedImage {
        require(maxEdgePx > 0) { "maxEdgePx 必须 > 0" }
        val resolver = context.contentResolver

        // 1) 只读边界：不分配像素
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IOException("无法打开输入流: $uri")
        coroutineContext.ensureActive()
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("解码边界失败（非法或损坏的图片）: $uri")
        }

        // 2) 计算 2 的幂降采样倍率
        val sampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx)

        // 3) 按倍率解码（inScaled=false 屏蔽 density 二次缩放）
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: throw IOException("解码失败: $uri")
        coroutineContext.ensureActive()

        // 4) 读 EXIF 方向（读不到就按正常方向）
        val orientation = readOrientation(context, uri)

        // 5) 方向矫正 + 精确缩放，一次成图
        val output = transform(decoded, orientation, maxEdgePx)
        if (output !== decoded) decoded.recycle()

        // 6) 压成极小 JPEG
        val jpeg = encodeJpegWithinBudget(output, targetMaxBytes)
        val outWidth = output.width
        val outHeight = output.height
        output.recycle()

        return CompressedImage(
            base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP),
            width = outWidth,
            height = outHeight,
            byteSize = jpeg.size
        )
    }

    /**
     * 只按「最长边」计算 2 的幂倍率：使解出的长边 ∈ [maxEdge, 2*maxEdge)。
     * 旋转不改变最长边，所以这里无需关心 EXIF。
     */
    private fun computeInSampleSize(width: Int, height: Int, maxEdgePx: Int): Int {
        var sampleSize = 1
        val longSide = maxOf(width, height)
        while (longSide / (sampleSize * 2) >= maxEdgePx) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun readOrientation(context: Context, uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (t: Throwable) {
        Log.w(IMG_TAG, "EXIF 读取失败，按正常方向处理", t)
        ExifInterface.ORIENTATION_NORMAL
    }

    /**
     * 单次 [Bitmap.createBitmap] 同时完成 EXIF 方向矫正与等比缩放。
     * 等比缩放与旋转可交换，故缩放比只取决于解码后的最长边。
     */
    private fun transform(src: Bitmap, orientation: Int, maxEdgePx: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> Unit // NORMAL / UNDEFINED：不做方向变换
        }

        val longSide = maxOf(src.width, src.height)
        val scale = if (longSide > maxEdgePx) maxEdgePx.toFloat() / longSide else 1f
        if (scale != 1f) matrix.postScale(scale, scale)

        if (matrix.isIdentity) return src
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    /** 沿质量阶梯压缩，返回第一个不超预算的字节；全部超标则返回最低质量结果。 */
    private fun encodeJpegWithinBudget(bitmap: Bitmap, targetMaxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        var last = ByteArray(0)
        for (quality in QUALITY_LADDER) {
            out.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            last = out.toByteArray()
            if (last.size <= targetMaxBytes) return last
        }
        return last
    }
}