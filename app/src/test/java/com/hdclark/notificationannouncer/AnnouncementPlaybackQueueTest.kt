package com.hdclark.notificationannouncer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementPlaybackQueueTest {
    @Test
    fun `multiple notifications share the first media snapshot even while speech is active`() {
        val queue = AnnouncementPlaybackQueue()
        assertTrue(queue.enqueue("First", true))
        assertEquals("First", queue.startNext("first-id"))
        assertFalse(queue.enqueue("Second", false)) // music is paused during the first utterance
        assertFalse(queue.enqueue("Third", false))
        assertTrue(queue.musicWasPlaying)
        assertTrue(queue.complete("first-id"))
        assertEquals("Second", queue.startNext("second-id"))
        assertTrue(queue.complete("second-id"))
        assertEquals("Third", queue.startNext("third-id"))
        assertTrue(queue.complete("third-id"))
        assertFalse(queue.hasQueued)
        assertFalse(queue.isSpeaking)
        assertTrue(queue.sessionOpen) // playback must not be restored until the burst closes
    }

    @Test
    fun `music detected during notification sound recovery is latched before speech`() {
        val queue = AnnouncementPlaybackQueue()
        queue.enqueue("First", false)
        queue.observeMusicBeforeSpeech(true)
        assertTrue(queue.musicWasPlaying)
        queue.startNext("first-id")
        queue.observeMusicBeforeSpeech(false)
        assertTrue(queue.musicWasPlaying)
    }

    @Test
    fun `our own TTS output cannot cause music to be detected after speech starts`() {
        val queue = AnnouncementPlaybackQueue()
        queue.enqueue("First", false)
        queue.startNext("first-id")
        queue.enqueue("Second", true) // isMusicActive might now reflect TTS itself
        queue.observeMusicBeforeSpeech(true)
        assertFalse(queue.musicWasPlaying)
    }

    @Test
    fun `duplicate and obsolete TTS callbacks cannot complete another announcement`() {
        val queue = AnnouncementPlaybackQueue()
        queue.enqueue("A", true)
        queue.enqueue("B", false)
        assertEquals("A", queue.startNext("a"))
        assertFalse(queue.complete("unknown"))
        assertNull(queue.startNext("b"))
        assertTrue(queue.complete("a"))
        assertFalse(queue.complete("a"))
        assertEquals("B", queue.startNext("b"))
        assertFalse(queue.complete("a"))
        assertTrue(queue.isSpeaking)
        queue.cancel()
        assertFalse(queue.complete("b"))
        assertFalse(queue.sessionOpen)
    }

    @Test
    fun `resume policy restores the prior behaviour and supports explicit overrides`() {
        assertEquals(MusicResumeMode.AUTOMATIC, MusicResumeMode.fromStoredValue(null))
        assertEquals(MusicResumeMode.AUTOMATIC, MusicResumeMode.fromStoredValue("invalid"))
        assertEquals(MusicResumeMode.NEVER, MusicResumeMode.fromStoredValue("never"))
        assertTrue(MusicResumeMode.AUTOMATIC.shouldResume(true))
        assertFalse(MusicResumeMode.AUTOMATIC.shouldResume(false))
        assertTrue(MusicResumeMode.ALWAYS.shouldResume(false))
        assertFalse(MusicResumeMode.NEVER.shouldResume(true))
    }
    @Test
    fun `watchdog recovery retries the failed utterance before later notifications`() {
        val queue = AnnouncementPlaybackQueue()
        queue.enqueue("first", true)
        queue.enqueue("second", false)
        assertEquals("first", queue.startNext("stuck-utterance"))
        assertTrue(queue.retry("stuck-utterance"))
        assertFalse(queue.isSpeaking)
        assertEquals("first", queue.startNext("retry-utterance"))
        assertFalse(queue.complete("stuck-utterance"))
        assertTrue(queue.complete("retry-utterance"))
        assertEquals("second", queue.startNext("next-id"))
        assertTrue(queue.complete("next-id"))
        assertTrue(queue.musicWasPlaying)
    }

    @Test
    fun `a permanently broken utterance never blocks later ones`() {
        val queue = AnnouncementPlaybackQueue()
        queue.enqueue("broken", false)
        queue.enqueue("healthy", false)
        assertEquals("broken", queue.startNext("id-1"))
        assertTrue(queue.retry("id-1"))
        assertEquals("broken", queue.startNext("id-2"))
        assertFalse(queue.retry("id-2"))
        assertFalse(queue.isSpeaking)
        assertEquals("healthy", queue.startNext("id-3"))
        assertTrue(queue.complete("id-3"))
    }

    @Test
    fun `queue is bounded under repeated incoming notification bursts`() {
        val queue = AnnouncementPlaybackQueue()
        for (index in 0..AnnouncementPlaybackQueue.MAX_QUEUED + 4) {
            queue.enqueue("message $index", false)
        }
        assertEquals(AnnouncementPlaybackQueue.MAX_QUEUED, queue.queuedCount)
        assertEquals("message 5", queue.startNext("first"))
    }

}
