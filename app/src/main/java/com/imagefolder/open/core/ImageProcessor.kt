package com.imagefolder.open.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.util.Size
import java.io.ByteArrayOutputStream

object ImageProcessor {

    /**
     * Longest edge kept when the cover is decoded for generation. A 108 MP
     * phone photo decoded at full size needs ~430 MB of ARGB_8888 memory and
     * would OOM on most devices; 4096 px is more than any gallery export needs.
     */
    const val MAX_CARRIER_SIZE = 4096

    /** Longest edge used for on-screen previews. */
    const val PREVIEW_SIZE = 720

    /**
     * Decodes the cover image with EXIF orientation applied and a software-backed buffer.
     *
     * [maxSize] caps the longest edge. Split mode passes a smaller value
     * because every part repeats the cover image.
     */
    fun loadCover(
        context: Context,
        uri: Uri,
        maxSize: Int = MAX_CARRIER_SIZE,
    ): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            applyTargetSize(decoder, info.size, maxSize)
        }
    }

    /**
     * Decodes [uri] for display only, downsampled to [maxSize] on the long edge.
     *
     * Decoding at full resolution just to fill a 180 dp thumbnail used to
     * allocate hundreds of megabytes; once the system folder picker came to the
     * foreground the app was GC-thrashing or killed, which is what "选完表图再
     * 选文件夹就卡爆" looked like.
     */
    fun decodePreview(
        context: Context,
        uri: Uri,
        maxSize: Int = PREVIEW_SIZE,
    ): Bitmap? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            applyTargetSize(decoder, info.size, maxSize)
        }
    }.getOrNull()

    /**
     * Decodes in-memory [bytes] for display only, downsampled to [maxSize] on
     * the long edge. Used by the extraction preview, where the payload can be
     * a full-resolution photo.
     */
    fun decodePreviewBytes(bytes: ByteArray, maxSize: Int = PREVIEW_SIZE): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > maxSize) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()

    private fun applyTargetSize(decoder: ImageDecoder, size: Size, maxSize: Int) {
        val longest = maxOf(size.width, size.height)
        if (longest <= maxSize || longest <= 0) return
        val scale = maxSize.toFloat() / longest
        decoder.setTargetSize(
            (size.width * scale).toInt().coerceAtLeast(1),
            (size.height * scale).toInt().coerceAtLeast(1),
        )
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

    /**
     * Draws [note] in the top-right corner.
     *
     * The font size now comes from [NoteText] (a share of the short edge,
     * clamped) instead of 7.54% of the diagonal, which produced oversized text
     * on 16:9 screenshots. Notes that are too wide are shrunk and, if they
     * still do not fit, ellipsized instead of running off the image.
     */
    private fun drawNote(bitmap: Bitmap, note: String) {
        val text = note.trim()
        if (text.isEmpty()) return
        val canvas = Canvas(bitmap)
        val margin = minOf(bitmap.width, bitmap.height) * 0.02f
        val available = NoteText.availableWidth(bitmap.width)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = NoteText.baseTextSize(bitmap.width, bitmap.height)
        }
        var body = text
        var width = paint.measureText(body)
        if (width > available) {
            paint.textSize = NoteText.fitTextSize(paint.textSize, width, available)
            width = paint.measureText(body)
            if (NoteText.needsEllipsis(width, available)) {
                body = ellipsize(paint, body, available)
                width = paint.measureText(body)
            }
        }
        val x = (bitmap.width - width - margin).coerceAtLeast(margin)
        val y = margin - paint.fontMetrics.ascent
        canvas.drawText(body, x, y, paint)
    }

    private fun ellipsize(paint: Paint, text: String, maxWidth: Float): String {
        val suffix = "…"
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + suffix) > maxWidth) {
            end--
        }
        return text.substring(0, end).trimEnd() + suffix
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
