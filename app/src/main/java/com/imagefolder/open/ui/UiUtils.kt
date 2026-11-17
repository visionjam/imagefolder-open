package com.imagefolder.open.ui

import java.util.Locale

fun formatSize(bytes: Long): String {
    if (bytes < 0) return "大小未知"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    val pattern = if (value >= 100) "%.0f %s" else "%.1f %s"
    return String.format(Locale.US, pattern, value, units[index])
}

fun isImageMime(mime: String?, fileName: String): Boolean {
    if (mime?.startsWith("image/") == true) return true
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return extension in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "avif")
}

/** Formats the in-app text preview can show as UTF-8. */
private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "json", "xml", "csv", "tsv", "log", "ini", "yml", "yaml",
    "kt", "kts", "java", "py", "js", "ts", "html", "htm", "css", "sh", "bat", "conf",
    "properties", "gradle", "toml", "srt", "vtt",
)

fun isTextFile(mime: String?, fileName: String): Boolean {
    if (mime?.startsWith("text/") == true) return true
    return fileName.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS
}
