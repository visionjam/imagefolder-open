package com.imagefolder.open.core

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveExtractorTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `extracts zip entries`() {
        val data = zipBytes(
            "dir/" to ByteArray(0),
            "dir/a.txt" to "hello zip".toByteArray(),
            "b.bin" to ByteArray(64) { it.toByte() },
        )
        val result = ArchiveExtractor.extract(data, "test.zip", null, temp.newFolder("zip-tmp"))

        assertEquals(ArchiveFormat.ZIP, result.format)
        assertTrue(result.entries.any { it.path == "dir/" })
        assertArrayEquals(
            "hello zip".toByteArray(),
            result.entries.first { it.path == "dir/a.txt" }.bytes,
        )
    }

    @Test
    fun `encrypted zip needs the right password`() {
        val data = encryptedZip("pw-123", ByteArray(128) { (it * 3).toByte() })
        val work = temp.newFolder("enc-zip-tmp")

        assertThrows(ArchivePasswordRequiredException::class.java) {
            ArchiveExtractor.extract(data, "secret.zip", null, work)
        }
        assertThrows(ArchiveWrongPasswordException::class.java) {
            ArchiveExtractor.extract(data, "secret.zip", "wrong", work)
        }

        val result = ArchiveExtractor.extract(data, "secret.zip", "pw-123", work)
        assertArrayEquals(ByteArray(128) { (it * 3).toByte() }, result.entries.single().bytes)
    }

    @Test
    fun `extracts seven zip`() {
        val data = sevenZBytes(listOf("a.txt" to "seven".toByteArray(), "dir/b.bin" to ByteArray(32)))
        val result = ArchiveExtractor.extract(data, "test.7z", null, temp.newFolder("7z-tmp"))

        assertEquals(ArchiveFormat.SEVEN_Z, result.format)
        assertArrayEquals("seven".toByteArray(), result.entries.first { it.path == "a.txt" }.bytes)
        assertTrue(result.entries.any { it.path == "dir/b.bin" })
    }

    @Test
    fun `encrypted seven zip needs the right password`() {
        val data = encryptedSevenZBytes("pw-7z", listOf("a.txt" to "secret 7z".toByteArray()))
        val work = temp.newFolder("enc-7z-tmp")

        assertThrows(ArchivePasswordRequiredException::class.java) {
            ArchiveExtractor.extract(data, "secret.7z", null, work)
        }
        assertThrows(ArchiveWrongPasswordException::class.java) {
            ArchiveExtractor.extract(data, "secret.7z", "wrong", work)
        }

        val result = ArchiveExtractor.extract(data, "secret.7z", "pw-7z", work)
        assertArrayEquals("secret 7z".toByteArray(), result.entries.single().bytes)
    }

    @Test
    fun `extracts tar`() {
        val data = tarBytes(listOf("a.txt" to "tar".toByteArray(), "dir/" to ByteArray(0)))
        val result = ArchiveExtractor.extract(data, "test.tar", null, temp.newFolder("tar-tmp"))

        assertEquals(ArchiveFormat.TAR, result.format)
        assertArrayEquals("tar".toByteArray(), result.entries.first { it.path == "a.txt" }.bytes)
    }

    @Test
    fun `extracts tar gzip`() {
        val tar = tarBytes(listOf("note.txt" to "inside tar.gz".toByteArray()))
        val data = gzipBytes(tar)
        val result = ArchiveExtractor.extract(data, "bundle.tar.gz", null, temp.newFolder("tgz-tmp"))

        assertEquals(ArchiveFormat.GZIP, result.format)
        assertArrayEquals("inside tar.gz".toByteArray(), result.entries.single().bytes)
    }

    @Test
    fun `extracts gzip single file`() {
        val data = gzipBytes("plain gzip".toByteArray())
        val result = ArchiveExtractor.extract(data, "note.txt.gz", null, temp.newFolder("gz-tmp"))

        assertEquals(ArchiveFormat.GZIP, result.format)
        assertEquals("note.txt", result.entries.single().path)
        assertArrayEquals("plain gzip".toByteArray(), result.entries.single().bytes)
    }

    @Test
    fun `extracts bzip2 single file`() {
        val data = bzip2Bytes("bzip data".toByteArray())
        val result = ArchiveExtractor.extract(data, "note.txt.bz2", null, temp.newFolder("bz2-tmp"))

        assertEquals(ArchiveFormat.BZIP2, result.format)
        assertEquals("note.txt", result.entries.single().path)
        assertArrayEquals("bzip data".toByteArray(), result.entries.single().bytes)
    }

    @Test
    fun `extracts xz single file`() {
        val data = xzBytes("xz data".toByteArray())
        val result = ArchiveExtractor.extract(data, "note.txt.xz", null, temp.newFolder("xz-tmp"))

        assertEquals(ArchiveFormat.XZ, result.format)
        assertEquals("note.txt", result.entries.single().path)
        assertArrayEquals("xz data".toByteArray(), result.entries.single().bytes)
    }

    @Test
    fun `rejects path traversal in zip`() {
        val data = zipBytes("../evil.txt" to "boom".toByteArray())
        assertThrows(ArchiveFormatException::class.java) {
            ArchiveExtractor.extract(data, "evil.zip", null, temp.newFolder("evil-tmp"))
        }
    }

    @Test
    fun `rar is detected but unsupported`() {
        val data = "Rar!\u001A\u0007\u0000".toByteArray(StandardCharsets.ISO_8859_1)
        assertEquals(ArchiveFormat.RAR, ArchiveExtractor.detect("test.rar", data))
        assertThrows(ArchiveUnsupportedException::class.java) {
            ArchiveExtractor.extract(data, "test.rar", null, temp.newFolder("rar-tmp"))
        }
    }

    @Test
    fun `detects formats by magic`() {
        assertEquals(ArchiveFormat.ZIP, ArchiveExtractor.detect("x.bin", zipBytes("a" to ByteArray(1))))
        assertEquals(ArchiveFormat.SEVEN_Z, ArchiveExtractor.detect("x.bin", sevenZBytes(listOf("a" to ByteArray(1)))))
        assertEquals(ArchiveFormat.GZIP, ArchiveExtractor.detect("x.bin", gzipBytes(ByteArray(1))))
        assertEquals(ArchiveFormat.BZIP2, ArchiveExtractor.detect("x.bin", bzip2Bytes(ByteArray(1))))
        assertEquals(ArchiveFormat.XZ, ArchiveExtractor.detect("x.bin", xzBytes(ByteArray(1))))
        assertEquals(
            ArchiveFormat.TAR,
            ArchiveExtractor.detect("x.bin", tarBytes(listOf("a" to ByteArray(1)))),
        )
    }

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun encryptedZip(password: String, payload: ByteArray): ByteArray {
        val source = temp.newFile("source-${System.nanoTime()}.bin").apply { writeBytes(payload) }
        val zip = temp.newFile("enc-${System.nanoTime()}.zip")
        val params = ZipParameters().apply {
            compressionMethod = CompressionMethod.DEFLATE
            isEncryptFiles = true
            encryptionMethod = EncryptionMethod.AES
            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
        }
        ZipFile(zip).apply {
            setPassword(password.toCharArray())
            addFiles(listOf(source), params)
        }
        return zip.readBytes()
    }

    private fun sevenZBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
        val file = temp.newFile("test-${System.nanoTime()}.7z")
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

    private fun encryptedSevenZBytes(
        password: String,
        entries: List<Pair<String, ByteArray>>,
    ): ByteArray {
        val file = temp.newFile("enc-${System.nanoTime()}.7z")
        SevenZOutputFile(file, password.toCharArray()).use { seven ->
            for ((name, bytes) in entries) {
                val entry = SevenZArchiveEntry().apply { this.name = name }
                seven.putArchiveEntry(entry)
                seven.write(bytes)
                seven.closeArchiveEntry()
            }
        }
        return file.readBytes()
    }

    private fun tarBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { tar ->
            for ((name, bytes) in entries) {
                if (name.endsWith("/")) {
                    tar.putArchiveEntry(TarArchiveEntry(name, true))
                } else {
                    val entry = TarArchiveEntry(name)
                    entry.size = bytes.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                }
                tar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    private fun gzipBytes(data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out ->
            GzipCompressorOutputStream(out).use { it.write(data) }
        }.toByteArray()

    private fun bzip2Bytes(data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out ->
            BZip2CompressorOutputStream(out).use { it.write(data) }
        }.toByteArray()

    private fun xzBytes(data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out ->
            XZOutputStream(out, LZMA2Options()).use { it.write(data) }
        }.toByteArray()
}
