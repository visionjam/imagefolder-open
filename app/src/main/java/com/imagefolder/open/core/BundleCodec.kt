package com.imagefolder.open.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.DestroyFailedException
import javax.crypto.SecretKeyFactory

data class BundleEntry(
    val path: String,
    val bytes: ByteArray,
)

class BundleFormatException(message: String) : IllegalArgumentException(message)

class InvalidPasswordException(message: String = "密码错误或数据已损坏") : Exception(message)

/**
 * v0.2 bundle envelope: a ZIP archive (structure + optional deflate) that can
 * optionally be encrypted with AES-256-GCM. The envelope lives inside one
 * `tpWj` PNG chunk, after the JSON header and a NUL separator.
 *
 * Layout (all integers big-endian):
 * ```
 * "IFB2"        4 bytes magic
 * version       1 byte  (1)
 * flags         1 byte  (bit0 encrypted, bit1 compressed)
 * iterations    4 bytes (PBKDF2-HMAC-SHA256, 0 when not encrypted)
 * saltLength    1 byte  (16 when encrypted, 0 otherwise)
 * nonceLength   1 byte  (12 when encrypted, 0 otherwise)
 * salt          saltLength bytes
 * nonce         nonceLength bytes
 * payload       ZIP bytes, or AES-GCM ciphertext + 16-byte tag
 * ```
 * The header is authenticated as GCM additional data when encrypted.
 */
object BundleCodec {
    private const val MAGIC = "IFB2"
    private const val VERSION = 1
    private const val FLAG_ENCRYPTED = 0x01
    private const val FLAG_COMPRESSED = 0x02
    private const val PBKDF2_ITERATIONS = 150_000
    private const val MIN_ITERATIONS = 10_000
    private const val MAX_ITERATIONS = 1_000_000
    private const val SALT_LENGTH = 16
    private const val NONCE_LENGTH = 12
    private const val GCM_TAG_BITS = 128
    private const val MAX_ENTRIES = 100_000
    private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024

    fun pack(entries: List<BundleEntry>, compress: Boolean, password: String?): ByteArray {
        if (entries.isEmpty()) throw BundleFormatException("没有要打包的内容")
        val seen = HashSet<String>(entries.size)
        for (entry in entries) {
            validatePath(entry.path, allowDirectory = true)
            if (entry.path.endsWith("/") && entry.bytes.isNotEmpty()) {
                throw BundleFormatException("目录条目不能包含数据：${entry.path}")
            }
            if (!seen.add(entry.path)) {
                throw BundleFormatException("重复的路径：${entry.path}")
            }
        }

        val zipBytes = buildZip(entries, compress)
        val encrypt = !password.isNullOrEmpty()
        val flags = (if (encrypt) FLAG_ENCRYPTED else 0) or
            (if (compress) FLAG_COMPRESSED else 0)

        if (!encrypt) {
            val header = buildHeader(flags, 0, ByteArray(0), ByteArray(0))
            return header + zipBytes
        }

        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH).also(random::nextBytes)
        val nonce = ByteArray(NONCE_LENGTH).also(random::nextBytes)
        val header = buildHeader(flags, PBKDF2_ITERATIONS, salt, nonce)
        val key = deriveKey(password, salt, PBKDF2_ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(header)
        val payload = cipher.doFinal(zipBytes)
        return header + payload
    }

    fun unpack(data: ByteArray, password: String?): List<BundleEntry> {
        val envelope = parseHeader(data)
        if (envelope.encrypted && password.isNullOrEmpty()) {
            throw InvalidPasswordException("需要密码")
        }

        val zipBytes = if (envelope.encrypted) {
            try {
                val key = deriveKey(password!!, envelope.salt, envelope.iterations)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(GCM_TAG_BITS, envelope.nonce),
                )
                cipher.updateAAD(envelope.header)
                cipher.doFinal(data, envelope.header.size, data.size - envelope.header.size)
            } catch (e: AEADBadTagException) {
                throw InvalidPasswordException()
            } catch (e: IllegalArgumentException) {
                throw BundleFormatException("损坏的加密数据")
            }
        } else {
            data.copyOfRange(envelope.header.size, data.size)
        }

        return readZip(zipBytes)
    }

    fun isEncrypted(data: ByteArray): Boolean = parseHeader(data).encrypted

    private data class Envelope(
        val header: ByteArray,
        val encrypted: Boolean,
        val iterations: Int,
        val salt: ByteArray,
        val nonce: ByteArray,
    )

    private fun parseHeader(data: ByteArray): Envelope {
        if (data.size < 12) throw BundleFormatException("数据太短")
        val magic = String(data, 0, 4, StandardCharsets.US_ASCII)
        if (magic != MAGIC) throw BundleFormatException("不是 v0.2 打包数据")
        val version = data[4].toInt() and 0xFF
        if (version != VERSION) throw BundleFormatException("不支持的版本：$version")
        val flags = data[5].toInt() and 0xFF
        if (flags and FLAG_ENCRYPTED.inv() and FLAG_COMPRESSED.inv() != 0) {
            throw BundleFormatException("未知的标志位")
        }
        val encrypted = flags and FLAG_ENCRYPTED != 0
        val iterations = readInt(data, 6)
        val saltLength = data[10].toInt() and 0xFF
        val nonceLength = data[11].toInt() and 0xFF
        val headerLength = 12 + saltLength + nonceLength
        if (headerLength > data.size) throw BundleFormatException("头部长度异常")

        if (encrypted) {
            if (saltLength != SALT_LENGTH || nonceLength != NONCE_LENGTH) {
                throw BundleFormatException("加密参数长度异常")
            }
            if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
                throw BundleFormatException("密钥派生参数异常")
            }
        } else if (saltLength != 0 || nonceLength != 0) {
            throw BundleFormatException("未加密数据带有加密参数")
        }

        return Envelope(
            header = data.copyOfRange(0, headerLength),
            encrypted = encrypted,
            iterations = iterations,
            salt = data.copyOfRange(12, 12 + saltLength),
            nonce = data.copyOfRange(12 + saltLength, headerLength),
        )
    }

    private fun buildHeader(
        flags: Int,
        iterations: Int,
        salt: ByteArray,
        nonce: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream(12 + salt.size + nonce.size)
        out.write(MAGIC.toByteArray(StandardCharsets.US_ASCII))
        out.write(VERSION)
        out.write(flags)
        out.write(intToBytes(iterations))
        out.write(salt.size)
        out.write(nonce.size)
        out.write(salt)
        out.write(nonce)
        return out.toByteArray()
    }

    private fun buildZip(entries: List<BundleEntry>, compress: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (entry in entries) {
                val zipEntry = ZipEntry(entry.path)
                if (entry.path.endsWith("/")) {
                    zip.putNextEntry(zipEntry)
                    zip.closeEntry()
                    continue
                }
                zipEntry.method = if (compress) ZipEntry.DEFLATED else ZipEntry.STORED
                if (!compress) {
                    zipEntry.size = entry.bytes.size.toLong()
                    zipEntry.compressedSize = entry.bytes.size.toLong()
                    zipEntry.crc = CRC32().apply { update(entry.bytes) }.value
                }
                zip.putNextEntry(zipEntry)
                zip.write(entry.bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun readZip(zipBytes: ByteArray): List<BundleEntry> {
        val entries = mutableListOf<BundleEntry>()
        var totalBytes = 0L
        try {
            ZipInputStream(ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    validatePath(entry.name, allowDirectory = true)
                    if (entries.size >= MAX_ENTRIES) {
                        throw BundleFormatException("条目数量超过限制")
                    }
                    if (entry.name.endsWith("/")) {
                        entries += BundleEntry(entry.name, ByteArray(0))
                    } else {
                        val buffer = ByteArrayOutputStream()
                        val chunk = ByteArray(64 * 1024)
                        while (true) {
                            val read = zip.read(chunk)
                            if (read < 0) break
                            totalBytes += read
                            if (totalBytes > MAX_TOTAL_BYTES) {
                                throw BundleFormatException("解压后的数据过大")
                            }
                            buffer.write(chunk, 0, read)
                        }
                        entries += BundleEntry(entry.name, buffer.toByteArray())
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: BundleFormatException) {
            throw e
        } catch (e: Exception) {
            throw BundleFormatException("ZIP 数据损坏")
        }
        return entries
    }

    private fun validatePath(path: String, allowDirectory: Boolean) {
        if (path.isEmpty()) throw BundleFormatException("空路径")
        if (path.contains('\u0000') || path.contains('\\')) {
            throw BundleFormatException("非法路径：$path")
        }
        if (path.startsWith('/') || path.contains(':')) {
            throw BundleFormatException("非法路径：$path")
        }
        val isDirectory = path.endsWith("/")
        if (isDirectory && !allowDirectory) throw BundleFormatException("不允许目录条目")
        val body = if (isDirectory) path.dropLast(1) else path
        if (body.isEmpty()) throw BundleFormatException("非法路径：$path")
        for (segment in body.split('/')) {
            if (segment.isEmpty() || segment == "." || segment == "..") {
                throw BundleFormatException("非法路径：$path")
            }
        }
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            try {
                spec.clearPassword()
            } catch (_: DestroyFailedException) {
                // best effort
            }
        }
    }

    private fun readInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
