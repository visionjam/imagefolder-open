package com.imagefolder.open.core

import kotlin.math.hypot

/**
 * Font sizing rules for the note drawn onto the generated image.
 *
 * The original app sized the note as 7.54% of the image diagonal, which on a
 * 1080x1920 phone screenshot works out to ~166 px — far too large — and made
 * long notes run off the image. These rules keep the note proportional to the
 * short edge and shrink it (never below [MIN_SIZE]) so it always fits.
 */
object NoteText {

    /** Share of the short edge used for the note's font size. */
    const val SHORT_EDGE_RATIO = 0.055f

    /** Hard floor, so a tiny thumbnail still gets readable text. */
    const val MIN_SIZE = 10f

    /** Hard ceiling, so a huge scan does not get a billboard-sized note. */
    const val MAX_SIZE = 240f

    /** Fraction of the image width the note may occupy. */
    const val MAX_WIDTH_RATIO = 0.92f

    /** Font size in pixels for an image of [width] x [height]. */
    fun baseTextSize(width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return MIN_SIZE
        val shortEdge = minOf(width, height).toFloat()
        val byEdge = shortEdge * SHORT_EDGE_RATIO
        // Keep a little of the original diagonal formula for very tall or very
        // wide images, where the short edge alone would look tiny.
        val byDiagonal = hypot(width.toFloat(), height.toFloat()) * 0.02f
        return maxOf(byEdge, byDiagonal).coerceIn(MIN_SIZE, MAX_SIZE)
    }

    /** Width in pixels the note may occupy inside an image of [width]. */
    fun availableWidth(width: Int): Float =
        (width.toFloat() * MAX_WIDTH_RATIO).coerceAtLeast(1f)

    /**
     * Shrinks [size] so a text that measures [textWidth] fits into
     * [availableWidth]. Text width scales linearly with the font size, so a
     * single ratio is exact.
     */
    fun fitTextSize(size: Float, textWidth: Float, availableWidth: Float): Float {
        if (textWidth <= availableWidth || textWidth <= 0f) return size
        return (size * availableWidth / textWidth).coerceIn(MIN_SIZE, size)
    }

    /** True when the note still does not fit at [MIN_SIZE] and must be cut. */
    fun needsEllipsis(textWidth: Float, availableWidth: Float): Boolean =
        textWidth > availableWidth
}
