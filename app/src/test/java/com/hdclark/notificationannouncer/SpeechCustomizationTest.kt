package com.hdclark.notificationannouncer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechCustomizationTest {
    @Test
    fun defaultSettingsLeaveSpeechContentAndTimingUntouched() {
        val text = "Announcement. Messenger. Hello"
        assertEquals(text, SpeechCustomization.repeatAnnouncement(text, 1))
        val existingWait = AnnouncementTiming.waitBeforeSpeaking(100L, 100L, 100L)
        assertEquals(3_000L, existingWait)
        assertEquals(
            existingWait,
            maxOf(existingWait, SpeechCustomization.additionalWait(100L, 100L, 0L))
        )
    }

    @Test
    fun repetitionsStayOneQueuedTtsUtteranceAndOneCallback() {
        val spoken = SpeechCustomization.repeatAnnouncement("Notice.", 3)
        assertEquals("Notice. Notice. Notice.", spoken)
        val queue = AnnouncementPlaybackQueue()
        queue.enqueue(spoken, true)
        assertEquals(1, queue.queuedCount)
        assertEquals(spoken, queue.startNext("single-id"))
        assertTrue(queue.isSpeaking)
        assertTrue(queue.complete("single-id"))
        assertFalse(queue.hasQueued)
        assertFalse(queue.isSpeaking)
        assertTrue(queue.musicWasPlaying)
    }

    @Test
    fun unpunctuatedMessagesGetSeparationBetweenRepetitions() {
        assertEquals("Hello. Hello", SpeechCustomization.repeatAnnouncement("Hello", 2))
    }

    @Test
    fun repetitionsAreBoundedWithoutTruncatingOriginalMessage() {
        val original = "a".repeat(2_000)
        assertEquals(original, SpeechCustomization.repeatAnnouncement(original, 1))
        assertEquals(original, SpeechCustomization.repeatAnnouncement(original, 5))
        val small = SpeechCustomization.repeatAnnouncement("Text", 5, maxSpeechLength = 17)
        assertTrue(small.length <= 17)
        assertEquals("Text. Text. Text", small)
        assertEquals("Text", SpeechCustomization.repeatAnnouncement("Text", 0))
    }

    @Test
    fun speechMultipliersAreBoundedAndNonfiniteValuesResetToNormal() {
        assertEquals(0.5f, SpeechCustomization.boundedMultiplier(0.1f), 0.0001f)
        assertEquals(2.0f, SpeechCustomization.boundedMultiplier(5.0f), 0.0001f)
        assertEquals(1.0f, SpeechCustomization.boundedMultiplier(Float.NaN), 0.0001f)
        assertEquals(1.0f, SpeechCustomization.boundedMultiplier(Float.POSITIVE_INFINITY), 0.0001f)
    }

    @Test
    fun optionalMinimumWaitAppliesOnlyWhenConfigured() {
        val first = 100L
        val now = 4_000L
        assertEquals(0L, SpeechCustomization.additionalWait(first, now, 0L))
        assertEquals(6_100L, SpeechCustomization.additionalWait(first, now, 10_000L))
        assertEquals(0L, SpeechCustomization.additionalWait(first, 10_200L, 10_000L))
    }
}
