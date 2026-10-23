package com.hdclark.notificationannouncer

/**
 * One burst owns the playback decision; exactly one utterance can be in flight.
 * A retry is returned to the *front* of the queue to preserve announcement order.
 * All access is serialized on the main looper by NotificationAnnouncerService.
 */
class AnnouncementPlaybackQueue {
    private data class Item(val text: String, val retries: Int = 0)
    private val queued = ArrayDeque<Item>()
    private var active: Item? = null
    var activeUtteranceId: String? = null
        private set

    var sessionOpen: Boolean = false
        private set
    var musicWasPlaying: Boolean = false
        private set
    private var speechStarted: Boolean = false

    val hasQueued: Boolean get() = queued.isNotEmpty()
    val queuedCount: Int get() = queued.size
    val isSpeaking: Boolean get() = activeUtteranceId != null
    val activeTextLength: Int get() = active?.text?.length ?: 0

    /** True if this is the first announcement of a burst. Bounded queue avoids OOM. */
    fun enqueue(announcement: String, musicActive: Boolean): Boolean {
        val first = !sessionOpen
        if (first) {
            sessionOpen = true
            musicWasPlaying = musicActive
            speechStarted = false
        } else {
            observeMusicBeforeSpeech(musicActive)
        }
        if (queued.size >= MAX_QUEUED) queued.removeFirst()
        queued.addLast(Item(announcement))
        return first
    }

    fun observeMusicBeforeSpeech(musicActive: Boolean) {
        if (sessionOpen && !speechStarted) musicWasPlaying = musicWasPlaying || musicActive
    }

    fun startNext(utteranceId: String): String? {
        if (!sessionOpen || isSpeaking || queued.isEmpty()) return null
        val next = queued.removeFirst()
        active = next
        activeUtteranceId = utteranceId
        speechStarted = true
        return next.text
    }

    fun complete(utteranceId: String?): Boolean {
        if (utteranceId == null || activeUtteranceId != utteranceId) return false
        activeUtteranceId = null
        active = null
        return true
    }

    /**
     * Invalidate a failed utterance and allow only one retry for that message.
     * Returns false when exhausted or when a callback is stale.
     */
    fun retry(utteranceId: String?): Boolean {
        if (utteranceId == null || activeUtteranceId != utteranceId) return false
        val failed = active
        activeUtteranceId = null
        active = null
        if (failed == null || failed.retries >= MAX_RETRIES) return false
        queued.addFirst(failed.copy(retries = failed.retries + 1))
        return true
    }

    fun cancel() {
        queued.clear()
        active = null
        activeUtteranceId = null
        sessionOpen = false
        musicWasPlaying = false
        speechStarted = false
    }

    companion object {
        const val MAX_QUEUED = 40
        const val MAX_RETRIES = 1
    }
}

enum class MusicResumeMode(val storedValue: String) {
    AUTOMATIC("automatic"),
    ALWAYS("always"),
    NEVER("never");

    fun shouldResume(musicWasPlaying: Boolean): Boolean = when (this) {
        AUTOMATIC -> musicWasPlaying
        ALWAYS -> true
        NEVER -> false
    }

    companion object {
        fun fromStoredValue(value: String?): MusicResumeMode =
            entries.firstOrNull { it.storedValue == value } ?: AUTOMATIC
    }
}


/** Prevents repeated notifications from postponing speech indefinitely. */
object AnnouncementTiming {
    fun waitBeforeSpeaking(
        firstNotificationUptime: Long,
        mostRecentNotificationUptime: Long,
        currentUptime: Long,
        quietWindowMs: Long = 3_000L,
        maxWaitMs: Long = 6_000L,
    ): Long {
        val quietDeadline = mostRecentNotificationUptime + quietWindowMs
        val absoluteDeadline = firstNotificationUptime + maxWaitMs
        return (minOf(quietDeadline, absoluteDeadline) - currentUptime).coerceAtLeast(0L)
    }
}
