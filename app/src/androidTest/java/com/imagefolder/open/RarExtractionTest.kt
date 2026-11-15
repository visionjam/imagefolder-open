package com.imagefolder.open

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.imagefolder.open.core.ArchiveExtractor
import com.imagefolder.open.core.ArchiveFormat
import com.imagefolder.open.core.ArchiveMissingPartsException
import com.imagefolder.open.core.ArchivePart
import com.imagefolder.open.core.ArchiveUnsupportedException
import com.imagefolder.open.core.ArchiveUnsupportedReason
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

@RunWith(AndroidJUnit4::class)
class RarExtractionTest {

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun rar5StoredExtracts() {
        val result = ArchiveExtractor.extract(
            asset("rar5_stored.rar"),
            "rar5_stored.rar",
            null,
            instrumentation.targetContext.cacheDir,
        )

        assertEquals(ArchiveFormat.RAR, result.format)
        val entry = result.entries.single()
        assertEquals("helloworld.txt", entry.path)
        assertArrayEquals(
            "hello libarchive test suite!\n".toByteArray(StandardCharsets.US_ASCII),
            entry.bytes,
        )
    }

    @Test
    fun rar5SolidExtractsAllFiles() {
        val result = ArchiveExtractor.extract(
            asset("rar5_solid.rar"),
            "rar5_solid.rar",
            null,
            instrumentation.targetContext.cacheDir,
        )

        assertEquals(
            listOf("test1.bin", "test2.bin", "test3.bin", "test4.bin"),
            result.entries.map { it.path },
        )
        result.entries.forEachIndexed { index, entry ->
            assertEquals(4096, entry.bytes.size)
            assertArrayEquals(expectedSolidEntry(index + 1), entry.bytes)
        }
    }

    @Test
    fun rar5MultiVolumeExtracts() {
        val parts = splitParts("rar5_multivolume", 8)
        val group = ArchiveExtractor.splitGroup(parts.first().name, parts)

        assertNotNull(group)
        assertTrue(group!!.missing.isEmpty())
        val result = ArchiveExtractor.extractGroup(
            group,
            null,
            instrumentation.targetContext.cacheDir,
        )

        assertEquals(
            listOf(
                "home/antek/temp/build/unrar5/libarchive/bin/bsdcat_test",
                "home/antek/temp/build/unrar5/libarchive/bin/bsdtar_test",
            ),
            result.entries.map { it.path },
        )
    }

    @Test
    fun missingRarVolumeIsReported() {
        val parts = splitParts("rar5_multivolume", 8)
            .filterNot { it.name.endsWith("part04.rar") }
        val group = ArchiveExtractor.splitGroup(parts.first().name, parts)

        assertNotNull(group)
        assertTrue(group!!.missing.contains("rar5_multivolume.part04.rar"))
        assertThrows(ArchiveMissingPartsException::class.java) {
            ArchiveExtractor.extractGroup(
                group,
                null,
                instrumentation.targetContext.cacheDir,
            )
        }
    }

    @Test
    fun rar4NormalExtractsFilesAndSkipsSymlink() {
        val result = ArchiveExtractor.extract(
            asset("rar4_normal.rar"),
            "rar4_normal.rar",
            null,
            instrumentation.targetContext.cacheDir,
        )

        val paths = result.entries.map { it.path }.toSet()
        assertTrue(paths.contains("LibarchiveAddingTest.html"))
        assertTrue(paths.contains("testdir/test.txt"))
        assertTrue(paths.contains("testdir/LibarchiveAddingTest.html"))
        assertTrue(paths.contains("testdir/"))
        assertTrue(paths.contains("testemptydir/"))
        assertFalse(paths.contains("testlink"))

        val text = result.entries.single { it.path == "testdir/test.txt" }
        assertArrayEquals(
            "test text document\r\n".toByteArray(StandardCharsets.US_ASCII),
            text.bytes,
        )
    }

    @Test
    fun rar5EncryptedDataIsReportedUnsupported() {
        assertEncryptedUnsupported("rar5_encrypted_data.rar")
    }

    @Test
    fun rar5EncryptedHeaderIsReportedUnsupported() {
        assertEncryptedUnsupported("rar5_encrypted_header.rar")
    }

    @Test
    fun rar4EncryptedHeaderIsReportedUnsupported() {
        assertEncryptedUnsupported("rar4_encrypted_header.rar")
    }

    private fun assertEncryptedUnsupported(name: String) {
        val error = assertThrows(ArchiveUnsupportedException::class.java) {
            ArchiveExtractor.extract(
                asset(name),
                name,
                null,
                instrumentation.targetContext.cacheDir,
            )
        }
        assertEquals(ArchiveUnsupportedReason.RAR_ENCRYPTED, error.reason)
    }

    private fun splitParts(prefix: String, count: Int): List<ArchivePart> =
        (1..count).map { index ->
            val name = prefix + ".part" + index.toString().padStart(2, '0') + ".rar"
            ArchivePart(name, asset(name))
        }

    private fun asset(name: String): ByteArray =
        instrumentation.context.assets.open("rar/$name").use { it.readBytes() }

    private fun expectedSolidEntry(magic: Int): ByteArray {
        val data = ByteArray(4096)
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until 4096 / 4) {
            val k = index + 1
            val value = (k * k - 3 * k + 1 + magic).coerceAtLeast(0)
            buffer.putInt(index * 4, value)
        }
        return data
    }
}
