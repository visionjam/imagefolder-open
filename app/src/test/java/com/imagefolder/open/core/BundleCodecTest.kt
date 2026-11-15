package com.imagefolder.open.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BundleCodecTest {

    private val entries = listOf(
        BundleEntry("照片/a.txt", "hello 图片".toByteArray(StandardCharsets.UTF_8)),
        BundleEntry("照片/子目录/b.bin", ByteArray(65536) { (it * 31).toByte() }),
        BundleEntry("照片/空目录/", ByteArray(0)),
    )

    @Test
    fun `round trip keeps directory structure and bytes`() {
        val packed = BundleCodec.pack(entries, compress = true, password = null)
        val unpacked = BundleCodec.unpack(packed, password = null)

        assertEquals(entries.map { it.path }, unpacked.map { it.path })
        for (index in entries.indices) {
            assertArrayEquals(entries[index].bytes, unpacked[index].bytes)
        }
    }

    @Test
    fun `round trip works without compression`() {
        val packed = BundleCodec.pack(entries, compress = false, password = null)
        val unpacked = BundleCodec.unpack(packed, password = null)

        assertEquals(entries.map { it.path }, unpacked.map { it.path })
        assertArrayEquals(entries[1].bytes, unpacked[1].bytes)
    }

    @Test
    fun `encrypted round trip needs the right password`() {
        val packed = BundleCodec.pack(entries, compress = true, password = "机密口令-42")

        val unpacked = BundleCodec.unpack(packed, password = "机密口令-42")
        assertEquals(entries.map { it.path }, unpacked.map { it.path })
        assertArrayEquals(entries[1].bytes, unpacked[1].bytes)

        assertThrows(InvalidPasswordException::class.java) {
            BundleCodec.unpack(packed, password = "wrong password")
        }
    }

    @Test
    fun `isEncrypted reports the envelope flag`() {
        assertFalse(BundleCodec.isEncrypted(BundleCodec.pack(entries, true, null)))
        assertTrue(BundleCodec.isEncrypted(BundleCodec.pack(entries, true, "pw")))
    }

    @Test
    fun `encryption uses a fresh salt and nonce`() {
        val first = BundleCodec.pack(entries, true, "pw")
        val second = BundleCodec.pack(entries, true, "pw")
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `pack rejects path traversal`() {
        assertThrows(BundleFormatException::class.java) {
            BundleCodec.pack(listOf(BundleEntry("../evil.txt", ByteArray(1))), true, null)
        }
        assertThrows(BundleFormatException::class.java) {
            BundleCodec.pack(listOf(BundleEntry("/absolute.txt", ByteArray(1))), true, null)
        }
    }

    @Test
    fun `unpack rejects a crafted zip with path traversal`() {
        val evilZip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("../evil.txt"))
                zip.write("boom".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        assertThrows(BundleFormatException::class.java) {
            BundleCodec.unpack(plainEnvelope(evilZip), password = null)
        }
    }

    @Test
    fun `pack rejects empty entry list`() {
        assertThrows(BundleFormatException::class.java) {
            BundleCodec.pack(emptyList(), true, null)
        }
    }

    @Test
    fun `unpack rejects data without the bundle magic`() {
        assertThrows(BundleFormatException::class.java) {
            BundleCodec.unpack(ByteArray(24), password = null)
        }
    }

    @Test
    fun `new encrypted envelope uses 600000 iterations`() {
        val packed = BundleCodec.pack(entries, compress = true, password = "pw")
        val iterations = ((packed[6].toInt() and 0xFF) shl 24) or
            ((packed[7].toInt() and 0xFF) shl 16) or
            ((packed[8].toInt() and 0xFF) shl 8) or
            (packed[9].toInt() and 0xFF)
        assertEquals(600_000, iterations)
    }

    @Test
    fun `legacy 150000 iteration envelope still decrypts`() {
        val zip = zipOf("old.txt", "legacy payload".toByteArray(StandardCharsets.UTF_8))
        val envelope = legacyEncryptedEnvelope(zip, "pw", 150_000)
        val unpacked = BundleCodec.unpack(envelope, "pw")
        assertEquals(listOf("old.txt"), unpacked.map { it.path })
        assertArrayEquals("legacy payload".toByteArray(StandardCharsets.UTF_8), unpacked.single().bytes)
    }

    private fun zipOf(path: String, bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }.toByteArray()

    /** Builds an IFB2 envelope using an arbitrary iteration count. */
    private fun legacyEncryptedEnvelope(
        zipBytes: ByteArray,
        password: String,
        iterations: Int,
    ): ByteArray {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val nonce = ByteArray(12) { (it + 21).toByte() }
        val header = ByteArrayOutputStream().also { out ->
            out.write("IFB2".toByteArray(StandardCharsets.US_ASCII))
            out.write(1)
            out.write(1)
            out.write(
                byteArrayOf(
                    (iterations ushr 24).toByte(),
                    (iterations ushr 16).toByte(),
                    (iterations ushr 8).toByte(),
                    iterations.toByte(),
                ),
            )
            out.write(16)
            out.write(12)
            out.write(salt)
            out.write(nonce)
        }.toByteArray()
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256))
            .encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce),
        )
        cipher.updateAAD(header)
        return header + cipher.doFinal(zipBytes)
    }

    /** Builds an unencrypted envelope so tests can inject a hand-crafted ZIP. */
    private fun plainEnvelope(zipBytes: ByteArray): ByteArray {
        val header = ByteArrayOutputStream()
        header.write("IFB2".toByteArray(StandardCharsets.US_ASCII))
        header.write(1)
        header.write(2)
        header.write(byteArrayOf(0, 0, 0, 0))
        header.write(0)
        header.write(0)
        return header.toByteArray() + zipBytes
    }
}
