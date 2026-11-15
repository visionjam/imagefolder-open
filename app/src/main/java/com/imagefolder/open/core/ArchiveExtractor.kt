package com.imagefolder.open.core

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

enum class ArchiveFormat(val label: String) {
    ZIP("ZIP"),
    SEVEN_Z("7z"),
    TAR("TAR"),
    GZIP("GZ"),
    BZIP2("BZ2"),
    XZ("XZ"),
    RAR("RAR"),
    UNKNOWN("未知"),
}

data class ArchiveEntry(
    val path: String,
    val bytes: ByteArray,
)

data class ArchiveExtractResult(
    val format: ArchiveFormat,
    val entries: List<ArchiveEntry>,
)

class ArchivePasswordRequiredException : Exception("需要密码")

class ArchiveWrongPasswordException : Exception("密码错误")

class ArchiveUnsupportedException(message: String) : Exception(message)

class ArchiveFormatException(message: String) : Exception(message)

/**
 * Reads common archives found inside hidden files.
 *
 * Licensed libraries:
 * - Apache Commons Compress (Apache-2.0) for 7z / tar / bzip2 / gzip
 * - Zip4j (Apache-2.0) for ZIP, including ZipCrypto and AES passwords
 * - XZ for Java (public domain) for xz
 *
 * RAR is detected but intentionally unsupported (requires an LGPL native
 * binding); callers should show a clear message instead of trying to parse it.
 */
object ArchiveExtractor {
    private const val MAX_ENTRIES = 100_000
    private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024

    fun detect(fileName: String?, data: ByteArray): ArchiveFormat {
        if (hasMagic(data, byteArrayOf(0x50, 0x4B, 0x03, 0x04)) ||
            hasMagic(data, byteArrayOf(0x50, 0x4B, 0x05, 0x06)) ||
            hasMagic(data, byteArrayOf(0x50, 0x4B, 0x07, 0x08))
        ) {
            return ArchiveFormat.ZIP
        }
        if (hasMagic(data, byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C))) {
            return ArchiveFormat.SEVEN_Z
        }
        if (hasMagic(data, byteArrayOf(0x1F, 0x8B.toByte()))) return ArchiveFormat.GZIP
        if (hasMagic(data, byteArrayOf(0x42, 0x5A, 0x68))) return ArchiveFormat.BZIP2
        if (hasMagic(data, byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00))) {
            return ArchiveFormat.XZ
        }
        if (hasMagic(data, byteArrayOf(0x52, 0x61, 0x72, 0x21))) return ArchiveFormat.RAR
        if (looksLikeTar(data)) return ArchiveFormat.TAR

        return when (fileName?.substringAfterLast('.', "")?.lowercase()) {
            "zip", "jar", "apk" -> ArchiveFormat.ZIP
            "7z" -> ArchiveFormat.SEVEN_Z
            "tar" -> ArchiveFormat.TAR
            "gz", "tgz" -> ArchiveFormat.GZIP
            "bz2", "tbz", "tbz2" -> ArchiveFormat.BZIP2
            "xz", "txz" -> ArchiveFormat.XZ
            "rar" -> ArchiveFormat.RAR
            else -> ArchiveFormat.UNKNOWN
        }
    }

    fun isSupported(format: ArchiveFormat): Boolean = when (format) {
        ArchiveFormat.ZIP,
        ArchiveFormat.SEVEN_Z,
        ArchiveFormat.TAR,
        ArchiveFormat.GZIP,
        ArchiveFormat.BZIP2,
        ArchiveFormat.XZ -> true
        ArchiveFormat.RAR, ArchiveFormat.UNKNOWN -> false
    }

    fun extract(
        data: ByteArray,
        fileName: String?,
        password: String?,
        tempDir: File?,
    ): ArchiveExtractResult {
        val format = detect(fileName, data)
        val limits = Limits()
        val entries = when (format) {
            ArchiveFormat.ZIP -> extractZip(data, password, tempDir, limits)
            ArchiveFormat.SEVEN_Z -> extractSevenZ(data, password, limits)
            ArchiveFormat.TAR -> readTar(data, limits)
            ArchiveFormat.GZIP,
            ArchiveFormat.BZIP2,
            ArchiveFormat.XZ -> extractCompressed(format, data, fileName, limits)
            ArchiveFormat.RAR -> throw ArchiveUnsupportedException("暂不支持 RAR 格式")
            ArchiveFormat.UNKNOWN -> throw ArchiveFormatException("无法识别的压缩格式")
        }
        return ArchiveExtractResult(format, entries)
    }

    private fun extractZip(
        data: ByteArray,
        password: String?,
        tempDir: File?,
        limits: Limits,
    ): List<ArchiveEntry> {
        val directory = tempDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        if (!directory.exists()) directory.mkdirs()
        val temporary = File.createTempFile("imagefolder-", ".zip", directory)
        try {
            temporary.writeBytes(data)
            val zip = ZipFile(temporary)
            if (zip.isEncrypted) {
                if (password.isNullOrEmpty()) throw ArchivePasswordRequiredException()
                zip.setPassword(password.toCharArray())
            }
            val headers = zip.fileHeaders
            if (headers.size > MAX_ENTRIES) throw ArchiveFormatException("条目数量超过限制")
            val entries = mutableListOf<ArchiveEntry>()
            for (header in headers) {
                limits.addEntry()
                val path = normalizePath(header.fileName)
                if (header.isDirectory) {
                    entries += ArchiveEntry(path, ByteArray(0))
                    continue
                }
                val bytes = zip.getInputStream(header).use { readLimited(it, limits) }
                entries += ArchiveEntry(path, bytes)
            }
            return entries
        } catch (e: ZipException) {
            throw when (e.type) {
                ZipException.Type.WRONG_PASSWORD -> ArchiveWrongPasswordException()
                ZipException.Type.UNSUPPORTED_ENCRYPTION ->
                    ArchiveUnsupportedException("不支持的 ZIP 加密方式")
                ZipException.Type.UNKNOWN_COMPRESSION_METHOD ->
                    ArchiveUnsupportedException("不支持的 ZIP 压缩方式")
                else -> ArchiveFormatException(e.message ?: "ZIP 解压失败")
            }
        } catch (e: ArchivePasswordRequiredException) {
            throw e
        } catch (e: ArchiveWrongPasswordException) {
            throw e
        } catch (e: ArchiveFormatException) {
            throw e
        } catch (e: Exception) {
            throw ArchiveFormatException(e.message ?: "ZIP 解压失败")
        } finally {
            temporary.delete()
        }
    }

    private fun extractSevenZ(
        data: ByteArray,
        password: String?,
        limits: Limits,
    ): List<ArchiveEntry> {
        val builder = SevenZFile.builder().setSeekableByteChannel(SeekableInMemoryByteChannel(data))
        if (!password.isNullOrEmpty()) builder.setPassword(password.toCharArray())
        val sevenZ = try {
            builder.get()
        } catch (e: PasswordRequiredException) {
            throw if (password.isNullOrEmpty()) {
                ArchivePasswordRequiredException()
            } else {
                ArchiveWrongPasswordException()
            }
        } catch (e: IOException) {
            mapSevenZFailure(e, password)
        }

        sevenZ.use { archive ->
            val entries = mutableListOf<ArchiveEntry>()
            while (true) {
                val entry = try {
                    archive.nextEntry
                } catch (e: Exception) {
                    mapSevenZFailure(e, password)
                } ?: break
                limits.addEntry()
                val path = normalizePath(entry.name)
                if (entry.isDirectory) {
                    entries += ArchiveEntry(path, ByteArray(0))
                    continue
                }
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = try {
                        archive.read(buffer)
                    } catch (e: Exception) {
                        mapSevenZFailure(e, password)
                    }
                    if (read < 0) break
                    if (read == 0) continue
                    limits.addBytes(read)
                    out.write(buffer, 0, read)
                }
                entries += ArchiveEntry(path, out.toByteArray())
            }
            return entries
        }
    }

    private fun extractCompressed(
        format: ArchiveFormat,
        data: ByteArray,
        fileName: String?,
        limits: Limits,
    ): List<ArchiveEntry> {
        val decompressed = try {
            when (format) {
                ArchiveFormat.GZIP ->
                    GzipCompressorInputStream(ByteArrayInputStream(data)).use { readCapped(it) }
                ArchiveFormat.BZIP2 ->
                    BZip2CompressorInputStream(ByteArrayInputStream(data)).use { readCapped(it) }
                ArchiveFormat.XZ ->
                    XZInputStream(ByteArrayInputStream(data)).use { readCapped(it) }
                else -> throw ArchiveFormatException("不支持的压缩格式")
            }
        } catch (e: ArchiveFormatException) {
            throw e
        } catch (e: Exception) {
            throw ArchiveFormatException(e.message ?: "解压失败")
        }

        if (looksLikeTar(decompressed)) {
            return readTar(decompressed, limits)
        }
        val name = singleFileName(fileName)
        limits.addEntry()
        limits.addBytes(decompressed.size)
        return listOf(ArchiveEntry(name, decompressed))
    }

    private fun readTar(data: ByteArray, limits: Limits): List<ArchiveEntry> {
        val entries = mutableListOf<ArchiveEntry>()
        try {
            TarArchiveInputStream(ByteArrayInputStream(data), "UTF-8").use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    limits.addEntry()
                    val path = normalizePath(entry.name)
                    if (entry.isDirectory) {
                        entries += ArchiveEntry(path, ByteArray(0))
                        continue
                    }
                    if (!entry.isFile) continue
                    entries += ArchiveEntry(path, readLimited(tar, limits))
                }
            }
        } catch (e: ArchiveFormatException) {
            throw e
        } catch (e: Exception) {
            throw ArchiveFormatException(e.message ?: "TAR 解压失败")
        }
        return entries
    }

    private fun readLimited(input: java.io.InputStream, limits: Limits): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            limits.addBytes(read)
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Reads a decompressed stream while enforcing the global size cap. */
    private fun readCapped(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > MAX_TOTAL_BYTES) throw ArchiveFormatException("解压后的数据过大")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun normalizePath(raw: String): String {
        if (raw.isEmpty()) throw ArchiveFormatException("压缩包包含空路径")
        val normalized = raw.replace('\\', '/')
        if (normalized.contains('\u0000') || normalized.contains(':')) {
            throw ArchiveFormatException("非法的压缩包路径：$raw")
        }
        val directory = normalized.endsWith("/")
        val body = if (directory) normalized.dropLast(1) else normalized
        if (body.isEmpty() || body.startsWith("/")) {
            throw ArchiveFormatException("非法的压缩包路径：$raw")
        }
        for (segment in body.split('/')) {
            if (segment.isEmpty() || segment == "." || segment == "..") {
                throw ArchiveFormatException("非法的压缩包路径：$raw")
            }
        }
        return if (directory) "$body/" else body
    }

    private fun singleFileName(fileName: String?): String {
        if (fileName.isNullOrEmpty()) return "解压文件"
        val lower = fileName.lowercase()
        val stripped = when {
            lower.endsWith(".tgz") -> fileName.dropLast(4) + ".tar"
            lower.endsWith(".tbz2") -> fileName.dropLast(5) + ".tar"
            lower.endsWith(".tbz") -> fileName.dropLast(4) + ".tar"
            lower.endsWith(".txz") -> fileName.dropLast(4) + ".tar"
            lower.endsWith(".gz") -> fileName.dropLast(3)
            lower.endsWith(".bz2") -> fileName.dropLast(4)
            lower.endsWith(".xz") -> fileName.dropLast(3)
            else -> fileName
        }
        return stripped.ifEmpty { "解压文件" }
    }

    private fun mapSevenZFailure(e: Exception, password: String?): Nothing {
        if (!password.isNullOrEmpty()) {
            // Once a password was supplied, decryption or stream errors almost
            // always mean the password is wrong (7z has no authenticated tag).
            throw ArchiveWrongPasswordException()
        }
        val message = (e.message ?: "").lowercase()
        val passwordRelated = message.contains("password") ||
            message.contains("encrypt") ||
            message.contains("decrypt") ||
            message.contains("cipher") ||
            message.contains("crc") ||
            message.contains("corrupt")
        if (passwordRelated) throw ArchivePasswordRequiredException()
        throw ArchiveFormatException(e.message ?: "7z 解压失败")
    }

    private fun hasMagic(data: ByteArray, magic: ByteArray): Boolean {
        if (data.size < magic.size) return false
        for (index in magic.indices) {
            if (data[index] != magic[index]) return false
        }
        return true
    }

    private fun looksLikeTar(data: ByteArray): Boolean {
        if (data.size < 262) return false
        val marker = "ustar".toByteArray(StandardCharsets.US_ASCII)
        for (index in marker.indices) {
            if (data[257 + index] != marker[index]) return false
        }
        return true
    }

    private class Limits {
        private var entries = 0
        private var bytes = 0L

        fun addEntry() {
            entries++
            if (entries > MAX_ENTRIES) throw ArchiveFormatException("条目数量超过限制")
        }

        fun addBytes(count: Int) {
            bytes += count
            if (bytes > MAX_TOTAL_BYTES) throw ArchiveFormatException("解压后的数据过大")
        }
    }
}
