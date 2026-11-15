package com.imagefolder.open.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets

class RarExtractorTest {

    @get:Rule
    val temp = TemporaryFolder()

    @After
    fun clearOverride() {
        ArchiveExtractor.rarExtractorOverride = null
    }

    @Test
    fun `extracts rar through rar extractor`() {
        val fake = RecordingRarExtractor()
        ArchiveExtractor.rarExtractorOverride = fake
        val data = "Rar!\u001A\u0007\u0000".toByteArray(StandardCharsets.ISO_8859_1)

        val result = ArchiveExtractor.extract(data, "test.rar", null, temp.newFolder("rar"))

        assertEquals(ArchiveFormat.RAR, result.format)
        assertEquals(listOf("photo.png"), result.entries.map { it.path })
        assertEquals(1, fake.singleCalls)
    }

    @Test
    fun `extracts rar split group through rar extractor`() {
        val fake = RecordingRarExtractor()
        ArchiveExtractor.rarExtractorOverride = fake
        val parts = listOf(
            ArchivePart("archive.rar", byteArrayOf(1)),
            ArchivePart("archive.r00", byteArrayOf(2)),
        )
        val group = ArchiveExtractor.splitGroup("archive.r00", parts)!!

        val result = ArchiveExtractor.extractGroup(group, null, temp.newFolder("rar-group"))

        assertEquals(ArchiveFormat.RAR, result.format)
        assertEquals(listOf("archive.rar", "archive.r00"), fake.groupParts)
    }

    @Test
    fun `maps encrypted rar failures`() {
        val error = mapRarFailure("Encryption is not supported")

        assertTrue(error is ArchiveUnsupportedException)
        assertEquals(
            ArchiveUnsupportedReason.RAR_ENCRYPTED,
            (error as ArchiveUnsupportedException).reason,
        )
    }

    @Test
    fun `maps solid rar4 failures`() {
        val error = mapRarFailure("RAR solid archive support unavailable")

        assertTrue(error is ArchiveUnsupportedException)
        assertEquals(
            ArchiveUnsupportedReason.RAR4_SOLID,
            (error as ArchiveUnsupportedException).reason,
        )
    }

    @Test
    fun `maps truncated rar failures to format errors`() {
        val error = mapRarFailure("Unexpected end of volume")

        assertTrue(error is ArchiveFormatException)
        assertEquals("RAR 分卷不完整", error.message)
    }

    @Test
    fun `maps multi-volume mismatch to format errors`() {
        val error = mapRarFailure("Mismatch of file parts split across multi-volume archive")

        assertTrue(error is ArchiveFormatException)
        assertEquals("RAR 分卷不完整", error.message)
    }

    @Test
    fun `maps unsupported rar features to unsupported`() {
        val error = mapRarFailure("Files with unknown unpacked size are not supported")

        assertTrue(error is ArchiveUnsupportedException)
    }

    private class RecordingRarExtractor : RarExtractor {
        var singleCalls = 0
        var groupParts: List<String> = emptyList()

        override fun extract(
            data: ByteArray,
            fileName: String?,
            tempDir: File?,
            limits: ArchiveExtractor.ExtractionLimits,
        ): List<ArchiveEntry> {
            singleCalls++
            return listOf(ArchiveEntry("photo.png", byteArrayOf(1)))
        }

        override fun extractGroup(
            parts: List<ArchivePart>,
            tempDir: File?,
            limits: ArchiveExtractor.ExtractionLimits,
        ): List<ArchiveEntry> {
            groupParts = parts.map { it.name }
            return listOf(ArchiveEntry("photo.png", byteArrayOf(1)))
        }
    }
}
