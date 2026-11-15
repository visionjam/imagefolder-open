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

    /**
     * Default cover used when the user does not pick one: a green apple
     * with the app palette, drawn after the original app's layout.
     */
    private fun generatePlaceholderCover(): Bitmap {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        // Leaf, tucked behind the stem and body.
        canvas.save()
        canvas.rotate(28f, 300f, 92f)
        val leaf = RectF(200f, 47f, 400f, 137f)
        canvas.drawOval(leaf, Paint(fill).apply { color = Color.parseColor("#558B2F") })
        canvas.drawOval(
            leaf,
            Paint(stroke).apply {
                color = Color.parseColor("#1B5E20")
                strokeWidth = 10f
            },
        )
        canvas.drawLine(
            215f,
            92f,
            385f,
            92f,
            Paint(stroke).apply {
                color = Color.parseColor("#1B5E20")
                strokeWidth = 5f
                strokeCap = Paint.Cap.ROUND
            },
        )
        canvas.restore()

        // Stem.
        canvas.drawRect(
            233f,
            55f,
            279f,
            200f,
            Paint(fill).apply { color = Color.parseColor("#795548") },
        )

        // Apple body.
        canvas.drawCircle(
            256f,
            320f,
            172f,
            Paint(fill).apply { color = Color.parseColor("#2E7D32") },
        )
        canvas.drawCircle(
            256f,
            320f,
            172f,
            Paint(stroke).apply {
                color = Color.parseColor("#1B5E20")
                strokeWidth = 18f
            },
        )

        // White document outline and lines.
        val document = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 18f
            strokeJoin = Paint.Join.ROUND
        }
        val outline = Path().apply {
            moveTo(171f, 410f)
            lineTo(341f, 410f)
            lineTo(341f, 205f)
            lineTo(205f, 205f)
            lineTo(171f, 239f)
            close()
        }
        canvas.drawPath(outline, document)
        for (y in floatArrayOf(273f, 316f, 358f)) {
            canvas.drawLine(205f, y, 307f, y, document)
        }
        return bitmap
    }
}
