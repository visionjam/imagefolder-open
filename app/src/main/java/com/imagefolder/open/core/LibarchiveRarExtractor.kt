package com.imagefolder.open.core

import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry as LibarchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * RAR backend built on libarchive (BSD-2-Clause) through the zhanghai Android binding.
 *
 * libarchive reads unencrypted RAR5 (including solid and multi-volume archives) and
 * unencrypted non-solid RAR4. RAR passwords and solid RAR4 are not supported.
 */
internal class LibarchiveRarExtractor : RarExtractor {

    override fun extract(
        data: ByteArray,
        fileName: String?,
        tempDir: File?,
        limits: ArchiveExtractor.ExtractionLimits,
    ): List<ArchiveEntry> {
        val directory = prepareDirectory(tempDir)
        val temporary = File.createTempFile("imagefolder-", ".rar", directory)
        return try {
            temporary.writeBytes(data)
            readArchive(listOf(temporary), limits)
        } finally {
            temporary.delete()
        }
    }

    override fun extractGroup(
        parts: List<ArchivePart>,
        tempDir: File?,
        limits: ArchiveExtractor.ExtractionLimits,
    ): List<ArchiveEntry> {
        if (parts.isEmpty()) throw ArchiveFormatException("没有可用的分卷")
        val directory = prepareDirectory(tempDir)
        val work = File(directory, "imagefolder-rar-${System.nanoTime()}")
        if (!work.mkdirs()) throw ArchiveFormatException("无法创建临时目录")
        return try {
            val files = parts.mapIndexed { index, part ->
                File(work, partFileName(part.name, index)).apply { writeBytes(part.bytes) }
            }
            readArchive(files, limits)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun readArchive(
        files: List<File>,
        limits: ArchiveExtractor.ExtractionLimits,
    ): List<ArchiveEntry> {
        var archive = 0L
        try {
            archive = Archive.readNew()
            Archive.readSupportFormatRar(archive)
            Archive.readSupportFormatRar5(archive)
            val names = files
                .map { it.absolutePath.toByteArray(StandardCharsets.UTF_8) }
                .toTypedArray()
            Archive.readOpenFileNames(archive, names, BLOCK_SIZE)
            if (Archive.readHasEncryptedEntries(archive) > 0) {
                throw encryptedException()
            }

            val entries = mutableListOf<ArchiveEntry>()
            while (true) {
                val entry = Archive.readNextHeader(archive)
                if (entry == 0L) break
                val path = LibarchiveEntry.pathnameUtf8(entry)
                    ?: throw ArchiveFormatException("RAR 条目缺少路径")
                if (LibarchiveEntry.isMetadataEncrypted(entry) ||
                    LibarchiveEntry.isDataEncrypted(entry)
                ) {
                    throw encryptedException()
                }
                when (LibarchiveEntry.filetype(entry)) {
                    LibarchiveEntry.AE_IFDIR -> {
                        limits.addEntry()
                        entries += ArchiveEntry(path, ByteArray(0))
                    }
                    LibarchiveEntry.AE_IFREG -> {
                        limits.addEntry()
                        entries += ArchiveEntry(path, readEntry(archive, limits))
                    }
                    else -> Unit
                }
            }
            return entries
        } catch (e: ArchiveException) {
            throw mapRarFailure(e.message ?: "")
        } finally {
            if (archive != 0L) {
                runCatching { Archive.free(archive) }
            }
        }
    }

    private fun readEntry(
        archive: Long,
        limits: ArchiveExtractor.ExtractionLimits,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
        while (true) {
            buffer.clear()
            Archive.readData(archive, buffer)
            val count = buffer.position()
            if (count == 0) break
            limits.addBytes(count)
            buffer.flip()
            val chunk = ByteArray(count)
            buffer.get(chunk)
            out.write(chunk)
        }
        return out.toByteArray()
    }

    private fun prepareDirectory(tempDir: File?): File {
        val directory = tempDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        if (!directory.exists() && !directory.mkdirs()) {
            throw ArchiveFormatException("无法创建临时目录")
        }
        return directory
    }

    private fun partFileName(rawName: String, index: Int): String {
        val name = File(rawName.replace('\\', '/')).name
        return if (name.isBlank() || name == "." || name == "..") {
            "part-${index + 1}.rar"
        } else {
            name
        }
    }

    private fun encryptedException() = ArchiveUnsupportedException(
        "RAR 密码暂不支持",
        ArchiveUnsupportedReason.RAR_ENCRYPTED,
    )

    private companion object {
        const val BLOCK_SIZE = 64L * 1024
        const val BUFFER_SIZE = 64 * 1024
    }
}
