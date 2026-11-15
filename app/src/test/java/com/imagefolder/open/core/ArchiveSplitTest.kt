package com.imagefolder.open.core

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class ArchiveSplitTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `split zip z01 style extracts`() {
        val parts = splitZipParts()
        assertTrue(parts.size >= 2)
        assertTrue(parts.any { it.name.endsWith(".zip") })
        assertTrue(parts.any { it.name.matches(Regex(".*\\.z\\d{2}")) })

        val lastPart = parts.first { it.name.endsWith(".zip") }
        val group = ArchiveExtractor.splitGroup(lastPart.name, parts)
        assertNotNull(group)
        assertTrue(group!!.missing.isEmpty())

        val result = ArchiveExtractor.extractGroup(group, null, temp.newFolder("z01-out"))
        assertEquals(ArchiveFormat.ZIP, result.format)
        assertArrayEquals(expectedPayload(), result.entries.single().bytes)
    }

    @Test
    fun `split zip numbered style extracts from first part`() {
        val parts = splitZipParts().mapIndexed { index, part ->
            ArchivePart("archive.zip.%03d".format(index + 1), part.bytes)
        }

        val group = ArchiveExtractor.splitGroup(parts.first().name, parts)
        assertNotNull(group)
        assertTrue(group!!.missing.isEmpty())

        val result = ArchiveExtractor.extractGroup(group, null, temp.newFolder("zipnum-out"))
        assertArrayEquals(expectedPayload(), result.entries.single().bytes)
    }

    @Test
    fun `split seven zip extracts`() {
        val payload = ByteArray(50_000).also { java.util.Random(7).nextBytes(it) }
        val archive = sevenZBytes(listOf("a.txt" to payload))
        val chunks = archive.toList().chunked(1024).mapIndexed { index, bytes ->
            ArchivePart("archive.7z.%03d".format(index + 1), bytes.toByteArray())
        }
        assertTrue(chunks.size >= 2)

        val group = ArchiveExtractor.splitGroup(chunks.first().name, chunks)
        assertNotNull(group)
        assertTrue(group!!.missing.isEmpty())
        assertEquals(ArchiveFormat.SEVEN_Z, group.format)

        val result = ArchiveExtractor.extractGroup(group, null, temp.newFolder("7z-split-out"))
        assertArrayEquals(payload, result.entries.single { it.path == "a.txt" }.bytes)
    }

    @Test
    fun `missing split part is reported`() {
        val parts = splitZipParts()
        val removed = parts.first { it.name.endsWith(".z02") }
        val incomplete = parts.filterNot { it.name == removed.name }

        val group = ArchiveExtractor.splitGroup(parts.first { it.name.endsWith(".z01") }.name, incomplete)
        assertNotNull(group)
        assertFalse(group!!.missing.isEmpty())
        assertTrue(group.missing.contains(removed.name))

        assertThrows(ArchiveMissingPartsException::class.java) {
            ArchiveExtractor.extractGroup(group, null, temp.newFolder("missing-out"))
        }
    }

    @Test
    fun `split names are detected`() {
        assertEquals(ArchiveFormat.ZIP, ArchiveExtractor.detect("a.z01", ByteArray(0)))
        assertEquals(ArchiveFormat.ZIP, ArchiveExtractor.detect("a.zip.001", ByteArray(0)))
        assertEquals(ArchiveFormat.SEVEN_Z, ArchiveExtractor.detect("a.7z.002", ByteArray(0)))
    }

    @Test
    fun `rar old style parts are grouped`() {
        val parts = listOf(
            ArchivePart("archive.rar", byteArrayOf(1)),
            ArchivePart("archive.r00", byteArrayOf(2)),
            ArchivePart("archive.r01", byteArrayOf(3)),
        )

        val group = ArchiveExtractor.splitGroup("archive.r00", parts)

        assertNotNull(group)
        assertEquals(ArchiveFormat.RAR, group!!.format)
        assertEquals(
            listOf("archive.rar", "archive.r00", "archive.r01"),
            group.parts.map { it.name },
        )
        assertTrue(group.missing.isEmpty())
    }

    @Test
    fun `rar old style missing first volume is reported`() {
        val parts = listOf(
            ArchivePart("archive.r00", byteArrayOf(2)),
            ArchivePart("archive.r01", byteArrayOf(3)),
        )

        val group = ArchiveExtractor.splitGroup("archive.r00", parts)

        assertNotNull(group)
        assertTrue(group!!.missing.contains("archive.rar"))
    }

    @Test
    fun `rar new style parts are grouped`() {
        val parts = listOf(
            ArchivePart("archive.part1.rar", byteArrayOf(1)),
            ArchivePart("archive.part2.rar", byteArrayOf(2)),
            ArchivePart("archive.part3.rar", byteArrayOf(3)),
        )

        val group = ArchiveExtractor.splitGroup("archive.part1.rar", parts)

        assertNotNull(group)
        assertEquals(ArchiveFormat.RAR, group!!.format)
        assertEquals(
            listOf("archive.part1.rar", "archive.part2.rar", "archive.part3.rar"),
            group.parts.map { it.name },
        )
        assertTrue(group.missing.isEmpty())
    }

    @Test
    fun `rar padded new style missing part keeps padding`() {
        val parts = listOf(
            ArchivePart("archive.part01.rar", byteArrayOf(1)),
            ArchivePart("archive.part03.rar", byteArrayOf(3)),
        )

        val group = ArchiveExtractor.splitGroup("archive.part01.rar", parts)

        assertNotNull(group)
        assertEquals(listOf("archive.part02.rar"), group!!.missing)
    }

    @Test
    fun `single rar is not a split group`() {
        assertNull(
            ArchiveExtractor.splitGroup(
                "archive.rar",
                listOf(ArchivePart("archive.rar", ByteArray(1))),
            ),
        )
        assertNull(
            ArchiveExtractor.splitGroup(
                "archive.part1.rar",
                listOf(ArchivePart("archive.part1.rar", ByteArray(1))),
            ),
        )
    }

    private fun expectedPayload(): ByteArray = ByteArray(200_000) { (it * 7).toByte() }

    /** Creates a real split ZIP via Zip4j and returns its parts as in-memory entries. */
    private fun splitZipParts(): List<ArchivePart> {
        val source = temp.newFile("source.bin").apply { writeBytes(expectedPayload()) }
        val zip = File(temp.root, "archive.zip")
        val params = ZipParameters().apply {
            compressionMethod = CompressionMethod.STORE
        }
        ZipFile(zip).createSplitZipFile(listOf(source), params, true, 65536)
        return temp.root.listFiles()!!
            .filter { it.isFile && it.name.startsWith("archive.") }
            .sortedBy { it.name }
            .map { ArchivePart(it.name, it.readBytes()) }
    }

    private fun sevenZBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
        val file = temp.newFile("split-${System.nanoTime()}.7z")
        SevenZOutputFile(file).use { seven ->
            for ((name, bytes) in entries) {
                val entry = SevenZArchiveEntry().apply { this.name = name }
                seven.putArchiveEntry(entry)
                seven.write(bytes)
                seven.closeArchiveEntry()
            }
        }
        return file.readBytes()
    }
}
