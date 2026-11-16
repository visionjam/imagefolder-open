package com.imagefolder.open.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTextTest {

    @Test
    fun sizeFollowsTheShortEdge() {
        // 1080x1920 screenshot: 1080 * 0.055 = 59.4 px, well under the ceiling.
        assertEquals(59.4f, NoteText.baseTextSize(1080, 1920), 0.5f)
        // 4000x3000 photo: 3000 * 0.055 = 165 px.
        assertEquals(165f, NoteText.baseTextSize(4000, 3000), 0.5f)
    }

    @Test
    fun neverExceedsTheCeiling() {
        assertEquals(NoteText.MAX_SIZE, NoteText.baseTextSize(100_000, 100_000), 0.01f)
    }

    @Test
    fun degenerateSizesFallBackToTheFloor() {
        assertEquals(NoteText.MIN_SIZE, NoteText.baseTextSize(0, 0), 0.01f)
        assertEquals(NoteText.MIN_SIZE, NoteText.baseTextSize(-5, 10), 0.01f)
    }

    @Test
    fun fitsWideNotesByShrinking() {
        assertEquals(50f, NoteText.fitTextSize(100f, 2000f, 1000f), 0.01f)
    }

    @Test
    fun leavesFittingNotesAlone() {
        assertEquals(100f, NoteText.fitTextSize(100f, 500f, 1000f), 0.01f)
        assertEquals(100f, NoteText.fitTextSize(100f, 0f, 1000f), 0.01f)
    }

    @Test
    fun neverShrinksBelowTheFloor() {
        assertEquals(NoteText.MIN_SIZE, NoteText.fitTextSize(20f, 10_000f, 100f), 0.01f)
    }

    @Test
    fun availableWidthKeepsAMargin() {
        assertEquals(920f, NoteText.availableWidth(1000), 0.01f)
        assertTrue(NoteText.needsEllipsis(1100f, 920f))
        assertFalse(NoteText.needsEllipsis(900f, 920f))
    }
}
