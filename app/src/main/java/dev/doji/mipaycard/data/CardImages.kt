package dev.doji.mipaycard.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

internal class CardImages(
    private val context: Context,
) {
    fun read(
        key: String,
        file: File,
    ): CachedImage {
        val bounds = bounds(file)
        val options =
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = 1
                while (max(bounds.outWidth, bounds.outHeight) / inSampleSize > 1024) {
                    inSampleSize *= 2
                }
            }
        val preview = BitmapFactory.decodeFile(file.path, options) ?: throw IOException("无法解码卡面缓存")
        return CachedImage(key, bounds.outWidth, bounds.outHeight, fingerprint(file), preview)
    }

    fun validate(
        file: File,
        width: Int,
        height: Int,
    ) {
        val bounds = bounds(file)
        if (bounds.outWidth != width || bounds.outHeight != height) throw IOException("卡面尺寸已变化，请刷新后重试")
        val bitmap = BitmapFactory.decodeFile(file.path) ?: throw IOException("原始卡面备份或缓存已损坏")
        bitmap.recycle()
    }

    fun prepare(
        image: CachedImage,
        uri: Uri,
        original: File,
    ): Bitmap {
        validate(original, image.width, image.height)
        val selected =
            try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longestEdge = max(info.size.width, info.size.height)
                    val decodeEdge = (max(image.width, image.height) * 2).coerceAtMost(4096)
                    if (longestEdge > decodeEdge) {
                        val scale = decodeEdge.toFloat() / longestEdge
                        decoder.setTargetSize(
                            (info.size.width * scale).roundToInt().coerceAtLeast(1),
                            (info.size.height * scale).roundToInt().coerceAtLeast(1),
                        )
                    }
                }
            } catch (error: SecurityException) {
                throw IOException("所选图片的读取权限已失效，请重新选择", error)
            }
        try {
            val mask = BitmapFactory.decodeFile(original.path) ?: throw IOException("无法解码原始卡面")
            try {
                val result = createBitmap(image.width, image.height)
                var prepared = false
                try {
                    val canvas = Canvas(result)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    val scale = max(image.width.toFloat() / selected.width, image.height.toFloat() / selected.height)
                    val width = selected.width * scale
                    val height = selected.height * scale
                    val left = (image.width - width) / 2
                    val top = (image.height - height) / 2
                    canvas.drawBitmap(selected, null, RectF(left, top, left + width, top + height), paint)
                    if (mask.hasAlpha()) {
                        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                        canvas.drawBitmap(mask, 0f, 0f, paint)
                    }
                    prepared = true
                    return result
                } finally {
                    if (!prepared) result.recycle()
                }
            } finally {
                mask.recycle()
            }
        } finally {
            selected.recycle()
        }
    }

    fun save(
        bitmap: Bitmap,
        file: File,
    ) {
        file.outputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("无法保存新卡面")
        }
    }

    private fun bounds(file: File): BitmapFactory.Options {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width.toLong() * height > 8_000_000 || bounds.outMimeType == "image/gif") {
            throw IOException("卡面图片格式或尺寸不受支持")
        }
        return bounds
    }
}

internal fun fingerprint(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
