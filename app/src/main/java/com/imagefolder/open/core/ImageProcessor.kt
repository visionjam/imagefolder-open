package com.imagefolder.open.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.hypot

object ImageProcessor {

    /** Decodes the cover image with EXIF orientation applied and a software-backed buffer. */
    fun loadCover(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
        }
    }

    /**
     * Sniffs the cover container so output can keep PNG, JPEG or WebP.
     * Returns null for containers this app does not write to (e.g. HEIC/AVIF);
     * callers fall back to PNG.
     */
    fun detectCarrier(context: Context, uri: Uri): CarrierFormat? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(16)
            val read = input.read(header)
            if (read <= 0) null else CarrierCodec.detect(header.copyOf(read))
        }
    }.getOrNull()

    /**
     * Renders the cover (or a generated placeholder) to [format], drawing the
     * optional note in the top-right corner just like the original app.
     */
    fun renderHiddenImage(
        cover: Bitmap?,
        note: String?,
        format: CarrierFormat = CarrierFormat.PNG,
    ): ByteArray {
        val base = when {
            cover == null -> generatePlaceholderCover()
            cover.isMutable -> cover
            else -> requireNotNull(cover.copy(Bitmap.Config.ARGB_8888, true)) { "无法复制封面图像" }
        }
        if (!note.isNullOrBlank()) drawNote(base, note)
        val compressFormat = when (format) {
            CarrierFormat.PNG -> Bitmap.CompressFormat.PNG
            CarrierFormat.JPEG -> Bitmap.CompressFormat.JPEG
            CarrierFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
        }
        val quality = if (format == CarrierFormat.PNG) 100 else 95
        val out = ByteArrayOutputStream()
        if (!base.compress(compressFormat, quality, out)) {
            throw IllegalStateException("${format.name} 编码失败")
        }
        return out.toByteArray()
    }

    private fun drawNote(bitmap: Bitmap, note: String) {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = hypot(bitmap.width.toFloat(), bitmap.height.toFloat()) * 0.07541333f
        }
        val bounds = Rect()
        paint.getTextBounds(note, 0, note.length, bounds)
        val x = bitmap.width - bounds.width() - 10f
        val y = 10f - bounds.top
        canvas.drawText(note, x, y, paint)
    }

    private fun generatePlaceholderCover(): Bitmap {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#2E7D32")
        canvas.drawRoundRect(RectF(64f, 96f, 448f, 416f), 40f, 40f, paint)

        paint.color = Color.parseColor("#A5D6A7")
        canvas.drawCircle(384f, 160f, 36f, paint)

        val mountain = Path().apply {
            moveTo(96f, 400f)
            lineTo(224f, 224f)
            lineTo(320f, 360f)
            lineTo(384f, 288f)
            lineTo(448f, 400f)
            close()
        }
        paint.color = Color.WHITE
        canvas.drawPath(mountain, paint)
        return bitmap
    }
}
