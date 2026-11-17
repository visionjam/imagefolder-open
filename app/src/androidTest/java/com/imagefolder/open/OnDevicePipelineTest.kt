package com.imagefolder.open

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.imagefolder.open.core.ArchiveExtractor
import com.imagefolder.open.core.ArchiveFormat
import com.imagefolder.open.core.ArchivePart
import com.imagefolder.open.core.BundleCodec
import com.imagefolder.open.core.BundleEntry
import com.imagefolder.open.core.CarrierCodec
import com.imagefolder.open.core.CarrierFormat
import com.imagefolder.open.core.HiddenFile
import com.imagefolder.open.core.ImageProcessor
import com.imagefolder.open.core.PngChunkCodec
import com.imagefolder.open.data.GallerySaver
import com.imagefolder.open.ui.writeShareCache
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionMethod
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets

@RunWith(AndroidJUnit4::class)
class OnDevicePipelineTest {

    @Test
    fun fullPipelineWorksOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val saver = GallerySaver(context, "ImageFolderTest")
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

    @Test
    fun jpegAndWebpCarriersRenderOnDevice() {
        val cover = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).drawColor(Color.rgb(46, 125, 50))
        }
        val envelope = ByteArray(1024) { (it * 5).toByte() }

        for (format in listOf(CarrierFormat.JPEG, CarrierFormat.WEBP)) {
            val coverBytes = ImageProcessor.renderHiddenImage(cover, "载体测试", format)
            assertNotNull("$format cover should decode", BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size))

            val covered = CarrierCodec.insertBundle(
                coverBytes = coverBytes,
                format = format,
                name = "bundle.zip",
                mime = "application/zip",
                envelope = envelope,
                encrypted = false,
                compressed = false,
                entryCount = 1,
            )
            val rendered = BitmapFactory.decodeByteArray(covered, 0, covered.size)
            assertNotNull("$format carrier should still render", rendered)
            assertEquals(320, requireNotNull(rendered).width)

            val items = CarrierCodec.extractItems(covered)
            assertEquals("$format items", 1, items.size)
            assertArrayEquals(envelope, items[0].bytes)
        }
    }

    @Test
    fun greenApplePlaceholderRendersOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = ImageProcessor.renderHiddenImage(null, null)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("green apple placeholder should decode", bitmap)
        assertEquals(512, requireNotNull(bitmap).width)
        assertEquals(512, bitmap.height)
        java.io.File(context.cacheDir, "green-apple-placeholder.png").writeBytes(bytes)
    }

    @Test
    fun archiveExtractionWorksOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val zip = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { stream ->
                stream.putNextEntry(java.util.zip.ZipEntry("目录/说明.txt"))
                stream.write("设备端 zip".toByteArray(StandardCharsets.UTF_8))
                stream.closeEntry()
            }
        }.toByteArray()

        val result = ArchiveExtractor.extract(zip, "ondevice.zip", null, context.cacheDir)
        assertEquals(ArchiveFormat.ZIP, result.format)
        assertEquals(1, result.entries.size)
        assertEquals("目录/说明.txt", result.entries.single().path)
        assertArrayEquals(
            "设备端 zip".toByteArray(StandardCharsets.UTF_8),
            result.entries.single().bytes,
        )
    }

    @Test
    fun splitZipWorksOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.cacheDir, "split-test-${System.currentTimeMillis()}")
        directory.mkdirs()
        try {
            val payload = ByteArray(200_000) { (it * 3).toByte() }
            val source = java.io.File(directory, "source.bin").apply { writeBytes(payload) }
            val zip = java.io.File(directory, "archive.zip")
            val params = ZipParameters().apply { compressionMethod = CompressionMethod.STORE }
            ZipFile(zip).createSplitZipFile(listOf(source), params, true, 65536)

            val parts = directory.listFiles()!!
                .filter { it.isFile && it.name.startsWith("archive.") }
                .map { ArchivePart(it.name, it.readBytes()) }
            assertTrue(parts.size >= 2)

            val group = ArchiveExtractor.splitGroup(
                parts.first { it.name.endsWith(".zip") }.name,
                parts,
            )
            assertNotNull(group)
            assertTrue(group!!.missing.isEmpty())

            val result = ArchiveExtractor.extractGroup(group, null, directory)
            assertArrayEquals(payload, result.entries.single().bytes)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun encryptedBundlePipelineWorksOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val saver = GallerySaver(context, "ImageFolderTest")
        val stamp = System.currentTimeMillis()

        val cover = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val coverPng = ImageProcessor.renderHiddenImage(cover, null)
        val entries = listOf(
            BundleEntry("目录/说明.txt", "设备端加密".toByteArray(StandardCharsets.UTF_8)),
            BundleEntry("目录/子目录/data.bin", ByteArray(2048) { it.toByte() }),
        )
        val envelope = BundleCodec.pack(entries, compress = true, password = "pw-设备")
        val png = PngChunkCodec.insertBundle(
            coverPng = coverPng,
            name = "bundle.zip",
            mime = "application/zip",
            envelope = envelope,
            encrypted = true,
            compressed = true,
            entryCount = entries.size,
        )

        val decoded = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertNotNull(decoded)

        val items = PngChunkCodec.extractItems(png)
        assertEquals(1, items.size)
        assertEquals("ifv2", items[0].format)
        val unpacked = BundleCodec.unpack(items[0].bytes, "pw-设备")
        assertEquals(entries.map { it.path }, unpacked.map { it.path })
        assertArrayEquals(entries[1].bytes, unpacked[1].bytes)

        val saved = saver.saveRelative(entries[0].bytes, entries[0].path, "text/plain")
        try {
            val readBack = context.contentResolver.openInputStream(saved.uri)!!.use { it.readBytes() }
            assertArrayEquals(entries[0].bytes, readBack)
        } finally {
            context.contentResolver.delete(saved.uri, null, null)
        }
    }

    @Test
    fun shareCacheWritesTheGeneratedBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = "share me".toByteArray(StandardCharsets.UTF_8)
        val uri = writeShareCache(context, bytes, "share-test.png")
        val file = java.io.File(requireNotNull(context.cacheDir), "shared/share-test.png")
        assertTrue(file.exists())
        assertEquals(
            uri,
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            ),
        )
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun readAtMostRejectsOversizedStreams() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "read-at-most.bin").apply {
            writeBytes(ByteArray(2 * 1024 * 1024))
        }
        val uri = android.net.Uri.fromFile(file)
        val limited = com.imagefolder.open.data.UriUtils.readAtMost(context, uri, 1024 * 1024)
        assertTrue(limited == null)
        val full = com.imagefolder.open.data.UriUtils.readAtMost(context, uri, 3 * 1024 * 1024)
        assertEquals(2 * 1024 * 1024, requireNotNull(full).size)
        file.delete()
    }
}
