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

data class ArchivePart(
    val name: String,
    val bytes: ByteArray,
)

data class SplitGroupInfo(
    val format: ArchiveFormat,
    val baseName: String,
    val parts: List<ArchivePart>,
    val missing: List<String>,
)

class ArchiveMissingPartsException(val missing: List<String>) :
    Exception("缺少分卷：${missing.joinToString()}")

class ArchivePasswordRequiredException : Exception("需要密码")

class ArchiveWrongPasswordException : Exception("密码错误")

enum class ArchiveUnsupportedReason {
    RAR_ENCRYPTED,
    RAR4_SOLID,
}

class ArchiveUnsupportedException(
    message: String,
    val reason: ArchiveUnsupportedReason? = null,
) : Exception(message)

class ArchiveFormatException(message: String) : Exception(message)

internal fun mapRarFailure(message: String): Exception {
    val lower = message.lowercase()
    return when {
        "encryption is not supported" in lower ||
            "reading encrypted data" in lower ||
            "rar encryption support unavailable" in lower ->
            ArchiveUnsupportedException(
                "RAR 密码暂不支持",
                ArchiveUnsupportedReason.RAR_ENCRYPTED,
            )
        "rar solid archive support unavailable" in lower ->
            ArchiveUnsupportedException(
                "暂不支持固实 RAR4 压缩包",
                ArchiveUnsupportedReason.RAR4_SOLID,
            )
        "unexpected end of volume" in lower ||
            "mismatch of file parts" in lower ||
            "truncated" in lower ||
            "unexpected end of file" in lower ||
            "end of volume" in lower ->
            ArchiveFormatException("RAR 分卷不完整")
        "not supported" in lower ->
            ArchiveUnsupportedException("RAR 使用了暂不支持的特性：$message")
        else -> ArchiveFormatException("RAR 解压失败：$message")
    }
}

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

    @Volatile
    internal var rarExtractorOverride: RarExtractor? = null

    private val defaultRarExtractor: RarExtractor by lazy { LibarchiveRarExtractor() }

    private fun currentRarExtractor(): RarExtractor =
        rarExtractorOverride ?: defaultRarExtractor

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

        val lowerName = fileName?.lowercase()
        if (lowerName != null) {
            if (lowerName.matches(Regex(""".*\.z\d{2,}$""")) ||
                lowerName.matches(Regex(""".*\.zip\.\d{3,}$"""))
            ) {
                return ArchiveFormat.ZIP
            }
            if (lowerName.matches(Regex(""".*\.7z\.\d{3,}$"""))) {
                return ArchiveFormat.SEVEN_Z
            }
            if (lowerName.matches(Regex(""".*\.part\d+\.rar$""")) ||
                lowerName.matches(Regex(""".*\.r\d{2}$"""))
            ) {
                return ArchiveFormat.RAR
            }
        }

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
        ArchiveFormat.XZ,
        ArchiveFormat.RAR -> true
        ArchiveFormat.UNKNOWN -> false
    }

    fun splitGroup(clickedName: String, all: List<ArchivePart>): SplitGroupInfo? {
        val clicked = parseSplitName(clickedName) ?: return null
        val unique = LinkedHashMap<String, ArchivePart>()
        for (part in all) {
            val key = part.name.lowercase()
            if (key !in unique) unique[key] = part
        }
        val parsed = unique.values.mapNotNull { part ->
            val info = parseSplitName(part.name) ?: return@mapNotNull null
            if (info.family != clicked.family || info.baseKey != clicked.baseKey) {
                null
            } else {
                info to part
            }
        }

        return when (clicked.family) {
            SplitFamily.ZIP_CLASSIC -> {
                val numbered = parsed.filter { !it.first.isFinalZip }.sortedBy { it.first.index }
                val finalPart = parsed.firstOrNull { it.first.isFinalZip }?.second
                if (numbered.isEmpty()) return null
                val maxIndex = numbered.maxOf { it.first.index }
                val missing = mutableListOf<String>()
                for (index in 1..maxIndex) {
                    if (numbered.none { it.first.index == index }) {
                        missing += clicked.baseName + ".z" + index.toString().padStart(2, '0')
                    }
                }
                if (finalPart == null) missing += clicked.baseName + ".zip"
                SplitGroupInfo(
                    format = ArchiveFormat.ZIP,
                    baseName = clicked.baseName,
                    parts = numbered.map { it.second } + listOfNotNull(finalPart),
                    missing = missing,
                )
            }
            SplitFamily.ZIP_NUMBERED, SplitFamily.SEVEN_Z_NUMBERED -> {
                if (parsed.isEmpty()) return null
                val sorted = parsed.sortedBy { it.first.index }
                val maxIndex = sorted.maxOf { it.first.index }
                val missing = (1..maxIndex)
                    .filter { index -> sorted.none { it.first.index == index } }
                    .map { index ->
                        clicked.baseWithExtension + "." + index.toString().padStart(3, '0')
                    }
                SplitGroupInfo(
                    format = clicked.format,
                    baseName = clicked.baseName,
                    parts = sorted.map { it.second },
                    missing = missing,
                )
            }
            SplitFamily.RAR_OLD -> {
                val first = parsed.firstOrNull { it.first.isFirstRar }?.second
                val numbered = parsed.filter { !it.first.isFirstRar }.sortedBy { it.first.index }
                if (numbered.isEmpty()) return null
                val maxIndex = numbered.maxOf { it.first.index }
                val missing = mutableListOf<String>()
                if (first == null) missing += clicked.baseName + ".rar"
                for (index in 1..maxIndex) {
                    if (numbered.none { it.first.index == index }) {
                        missing += clicked.baseName + ".r" + (index - 1).toString().padStart(2, '0')
                    }
                }
                SplitGroupInfo(
                    format = ArchiveFormat.RAR,
                    baseName = clicked.baseName,
                    parts = listOfNotNull(first) + numbered.map { it.second },
                    missing = missing,
                )
            }
            SplitFamily.RAR_NEW -> {
                if (parsed.isEmpty()) return null
                val sorted = parsed.sortedBy { it.first.index }
                if (sorted.size == 1 && sorted.first().first.index == 1) return null
                val maxIndex = sorted.maxOf { it.first.index }
                val width = sorted.maxOf { it.first.numberWidth }
                val missing = (1..maxIndex)
                    .filter { index -> sorted.none { it.first.index == index } }
                    .map { index ->
                        clicked.baseName + ".part" + index.toString().padStart(width, '0') + ".rar"
                    }
                SplitGroupInfo(
                    format = ArchiveFormat.RAR,
                    baseName = clicked.baseName,
                    parts = sorted.map { it.second },
                    missing = missing,
                )
            }
        }
    }

    fun extractGroup(
        group: SplitGroupInfo,
        password: String?,
        tempDir: File?,
    ): ArchiveExtractResult {
        if (group.missing.isNotEmpty()) throw ArchiveMissingPartsException(group.missing)
        val limits = ExtractionLimits()
        val entries = when (group.format) {
            ArchiveFormat.ZIP -> extractZipParts(group.parts, password, tempDir, limits)
            ArchiveFormat.SEVEN_Z -> {
                val joined = ByteArrayOutputStream()
                group.parts.forEach { joined.write(it.bytes) }
                extractSevenZ(joined.toByteArray(), password, limits)
            }
            ArchiveFormat.RAR -> normalizeRarEntries(
                currentRarExtractor().extractGroup(group.parts, tempDir, limits),
            )
            else -> throw ArchiveUnsupportedException("不支持的压缩分卷格式")
        }
        return ArchiveExtractResult(group.format, entries)
    }

    private enum class SplitFamily {
        ZIP_CLASSIC,
        ZIP_NUMBERED,
        SEVEN_Z_NUMBERED,
        RAR_OLD,
        RAR_NEW,
    }

    private data class SplitName(
        val format: ArchiveFormat,
        val family: SplitFamily,
        val baseKey: String,
        val baseName: String,
        val baseWithExtension: String,
        val index: Int,
        val isFinalZip: Boolean,
        val isFirstRar: Boolean = false,
        val numberWidth: Int = 0,
    )

    private fun parseSplitName(rawName: String): SplitName? {
        val name = rawName.substringAfterLast('/').substringAfterLast('\\')
        val lower = name.lowercase()

        Regex("""^(.+)\.z(\d{2,})$""").matchEntire(lower)?.let { match ->
            val digits = match.groupValues[2]
            val baseEnd = name.length - digits.length - 2
            if (baseEnd <= 0) return null
            val base = name.substring(0, baseEnd)
            return SplitName(
                format = ArchiveFormat.ZIP,
                family = SplitFamily.ZIP_CLASSIC,
                baseKey = base.lowercase(),
                baseName = base,
                baseWithExtension = base,
                index = digits.toInt(),
                isFinalZip = false,
            )
        }
        Regex("""^(.+)\.zip$""").matchEntire(lower)?.let {
            if (name.length <= 4) return null
            val base = name.dropLast(4)
            return SplitName(
                format = ArchiveFormat.ZIP,
                family = SplitFamily.ZIP_CLASSIC,
                baseKey = base.lowercase(),
                baseName = base,
                baseWithExtension = base,
                index = Int.MAX_VALUE,
                isFinalZip = true,
            )
        }
        Regex("""^(.+)\.zip\.(\d{3,})$""").matchEntire(lower)?.let { match ->
            val digits = match.groupValues[2]
            val baseEnd = name.length - digits.length - 1
            if (baseEnd <= 0) return null
            val baseWithExtension = name.substring(0, baseEnd)
            if (!baseWithExtension.lowercase().endsWith(".zip")) return null
            return SplitName(
                format = ArchiveFormat.ZIP,
                family = SplitFamily.ZIP_NUMBERED,
                baseKey = baseWithExtension.lowercase(),
                baseName = baseWithExtension.dropLast(4),
                baseWithExtension = baseWithExtension,
                index = digits.toInt(),
                isFinalZip = false,
            )
        }
        Regex("""^(.+)\.7z\.(\d{3,})$""").matchEntire(lower)?.let { match ->
            val digits = match.groupValues[2]
            val baseEnd = name.length - digits.length - 1
            if (baseEnd <= 0) return null
            val baseWithExtension = name.substring(0, baseEnd)
            if (!baseWithExtension.lowercase().endsWith(".7z")) return null
            return SplitName(
                format = ArchiveFormat.SEVEN_Z,
                family = SplitFamily.SEVEN_Z_NUMBERED,
                baseKey = baseWithExtension.lowercase(),
                baseName = baseWithExtension.dropLast(3),
                baseWithExtension = baseWithExtension,
                index = digits.toInt(),
                isFinalZip = false,
            )
        }
        Regex("""^(.+)\.part(\d+)\.rar$""").matchEntire(lower)?.let { match ->
            val digits = match.groupValues[2]
            val baseEnd = name.length - digits.length - ".part.rar".length
            if (baseEnd <= 0) return null
            val base = name.substring(0, baseEnd)
            return SplitName(
                format = ArchiveFormat.RAR,
                family = SplitFamily.RAR_NEW,
                baseKey = base.lowercase(),
                baseName = base,
                baseWithExtension = name,
                index = digits.toInt(),
                isFinalZip = false,
                numberWidth = digits.length,
            )
        }
        Regex("""^(.+)\.r(\d{2})$""").matchEntire(lower)?.let { match ->
            val digits = match.groupValues[2]
            val baseEnd = name.length - digits.length - 2
            if (baseEnd <= 0) return null
            val base = name.substring(0, baseEnd)
            return SplitName(
                format = ArchiveFormat.RAR,
                family = SplitFamily.RAR_OLD,
                baseKey = base.lowercase(),
                baseName = base,
                baseWithExtension = base + ".rar",
                index = digits.toInt() + 1,
                isFinalZip = false,
                numberWidth = 2,
            )
        }
        Regex("""^(.+)\.rar$""").matchEntire(lower)?.let {
            if (name.length <= 4) return null
            val base = name.dropLast(4)
            return SplitName(
                format = ArchiveFormat.RAR,
                family = SplitFamily.RAR_OLD,
                baseKey = base.lowercase(),
                baseName = base,
                baseWithExtension = name,
                index = 0,
                isFinalZip = false,
                isFirstRar = true,
                numberWidth = 2,
            )
        }
        return null
    }

    fun extract(
        data: ByteArray,
        fileName: String?,
        password: String?,
        tempDir: File?,
    ): ArchiveExtractResult {
        val format = detect(fileName, data)
        val limits = ExtractionLimits()
        val entries = when (format) {
            ArchiveFormat.ZIP -> extractZip(data, password, tempDir, limits)
            ArchiveFormat.SEVEN_Z -> extractSevenZ(data, password, limits)
            ArchiveFormat.TAR -> readTar(data, limits)
            ArchiveFormat.GZIP,
            ArchiveFormat.BZIP2,
            ArchiveFormat.XZ -> extractCompressed(format, data, fileName, limits)
            ArchiveFormat.RAR -> normalizeRarEntries(
                currentRarExtractor().extract(data, fileName, tempDir, limits),
            )
            ArchiveFormat.UNKNOWN -> throw ArchiveFormatException("无法识别的压缩格式")
        }
        return ArchiveExtractResult(format, entries)
    }

    private fun extractZip(
        data: ByteArray,
        password: String?,
        tempDir: File?,
        limits: ExtractionLimits,
    ): List<ArchiveEntry> {
        val directory = tempDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        if (!directory.exists()) directory.mkdirs()
        val temporary = File.createTempFile("imagefolder-", ".zip", directory)
        return try {
            temporary.writeBytes(data)
            extractZipFile(temporary, password, limits)
        } finally {
            temporary.delete()
        }
    }

    private fun extractZipParts(
        parts: List<ArchivePart>,
        password: String?,
        tempDir: File?,
        limits: ExtractionLimits,
    ): List<ArchiveEntry> {
        if (parts.isEmpty()) throw ArchiveFormatException("没有可用的分卷")
        val directory = tempDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        if (!directory.exists()) directory.mkdirs()
        val work = File(directory, "imagefolder-split-${System.nanoTime()}")
        if (!work.mkdirs()) throw ArchiveFormatException("无法创建临时目录")
        return try {
            val numbered = parts.any { part ->
                part.name.matches(Regex(""".*\.zip\.\d{3,}$""", RegexOption.IGNORE_CASE))
            }
            val files = if (numbered) {
                val sorted = parts.sortedBy { part ->
                    part.name.substringAfterLast('.').toIntOrNull() ?: Int.MAX_VALUE
                }
                val baseWithExtension = sorted.first().name.substringBeforeLast('.')
                val base = if (baseWithExtension.lowercase().endsWith(".zip")) {
                    baseWithExtension.dropLast(4)
                } else {
                    baseWithExtension
                }
                sorted.mapIndexed { index, part ->
                    val name = if (index == sorted.lastIndex) {
                        "$base.zip"
                    } else {
                        base + ".z" + (index + 1).toString().padStart(2, '0')
                    }
                    File(work, name).apply { writeBytes(part.bytes) }
                }
            } else {
                parts.map { part ->
                    File(work, File(part.name).name).apply { writeBytes(part.bytes) }
                }
            }
            val target = files.lastOrNull { it.name.endsWith(".zip", ignoreCase = true) }
                ?: files.first()
            extractZipFile(target, password, limits)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun extractZipFile(
        file: File,
        password: String?,
        limits: ExtractionLimits,
    ): List<ArchiveEntry> {
        try {
            val zip = ZipFile(file)
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
        }
    }

    private fun extractSevenZ(
        data: ByteArray,
        password: String?,
        limits: ExtractionLimits,
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
        limits: ExtractionLimits,
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

    private fun readTar(data: ByteArray, limits: ExtractionLimits): List<ArchiveEntry> {
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

    private fun readLimited(input: java.io.InputStream, limits: ExtractionLimits): ByteArray {
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

    private fun normalizeRarEntries(entries: List<ArchiveEntry>): List<ArchiveEntry> =
        entries.map { ArchiveEntry(normalizePath(it.path), it.bytes) }

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

    internal class ExtractionLimits {
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
