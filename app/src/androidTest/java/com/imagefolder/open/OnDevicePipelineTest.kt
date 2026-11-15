package com.imagefolder.open

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.imagefolder.open.core.HiddenFile
import com.imagefolder.open.core.ImageProcessor
import com.imagefolder.open.core.PngChunkCodec
import com.imagefolder.open.data.GallerySaver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets

@RunWith(AndroidJUnit4::class)
class OnDevicePipelineTest {

    @Test
    fun fullPipelineWorksOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val saver = GallerySaver(context)
        val stamp = System.currentTimeMillis()

        val cover = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(46, 125, 50))
            val paint = Paint().apply {
                color = Color.WHITE
                textSize = 48f
            }
            canvas.drawText("cover", 24f, 160f, paint)
        }

        val coverBytes = ImageProcessor.renderHiddenImage(cover, "设备测试")
        val decodedCover = BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size)
        assertNotNull(decodedCover)
        assertEquals(480, requireNotNull(decodedCover).width)

        val savedCover = saver.save(coverBytes, "ifotest_cover_$stamp.png", "image/png")
        try {
            val loaded = ImageProcessor.loadCover(context, savedCover.uri)
            assertNotNull(loaded)

            val payload = "on-device pipeline".toByteArray(StandardCharsets.UTF_8)
            val hidden = listOf(
                HiddenFile("测试.txt", "text/plain", payload),
                HiddenFile("blob.bin", null, ByteArray(4096) { it.toByte() }),
            )
            val coveredPng = PngChunkCodec.insertFiles(coverBytes, hidden)

            val rendered = BitmapFactory.decodeByteArray(coveredPng, 0, coveredPng.size)
            assertNotNull(rendered)
            assertEquals(480, requireNotNull(rendered).width)

            val extracted = PngChunkCodec.extractFiles(coveredPng)
            assertEquals(2, extracted.size)
            assertEquals("测试.txt", extracted[0].name)
            assertEquals("text/plain", extracted[0].mime)
            assertArrayEquals(payload, extracted[0].bytes)
            assertEquals("blob.bin", extracted[1].name)
            assertArrayEquals(ByteArray(4096) { it.toByte() }, extracted[1].bytes)

            val savedTank = saver.save(coveredPng, "ifotest_tank_$stamp.png", "image/png")
            try {
                val readBack = context.contentResolver.openInputStream(savedTank.uri)!!.use { it.readBytes() }
                assertArrayEquals(coveredPng, readBack)
            } finally {
                context.contentResolver.delete(savedTank.uri, null, null)
            }
        } finally {
            context.contentResolver.delete(savedCover.uri, null, null)
        }
    }
}
