package com.imagefolder.open.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.imagefolder.open.ExtractItem
import com.imagefolder.open.R
import com.imagefolder.open.core.ImageProcessor
import com.imagefolder.open.core.SimpleLruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Longest decoded edge for a result row: 44 dp at 3x is roughly 132 px. */
private const val THUMBNAIL_SIZE = 128

/** Bounded so a result list with hundreds of images cannot pin memory. */
private val thumbnailCache = SimpleLruCache<String, ImageBitmap>(48)

/**
 * Cache key for a result thumbnail. Path plus size is stable across
 * recompositions and across a re-extraction of the same carrier, which is what
 * lets a scrolled-away row come back without decoding again.
 */
internal fun thumbnailKey(item: ExtractItem): String =
    item.relativePath + '\u0000' + item.sourceName + '\u0000' + item.bytes.size

/**
 * Small preview of an extracted image, decoded off the main thread. Falls back
 * to the generic icon while decoding or when the bytes are not a decodable
 * image (e.g. an entry named `.png` that is actually a text file).
 */
@Composable
internal fun ResultThumbnail(item: ExtractItem, onClick: () -> Unit) {
    val key = remember(item) { thumbnailKey(item) }
    var bitmap by remember(key) { mutableStateOf(thumbnailCache.get(key)) }
    LaunchedEffect(key) {
        if (bitmap != null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            ImageProcessor.decodePreviewBytes(item.bytes, THUMBNAIL_SIZE)?.asImageBitmap()
        }
        if (decoded != null) {
            thumbnailCache.put(key, decoded)
            bitmap = decoded
        }
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.thumbnail_desc),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.PhotoLibrary,
                contentDescription = stringResource(R.string.thumbnail_desc),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
