package com.hdclark.notificationannouncer

/**
 * One logical announcement burst owns the playback decision. Only a single utterance may
 * be submitted to TTS at once; callbacks for stale/cancelled utterances are ignored.
 * This model has no Android dependencies so burst and completion ordering can be tested.
 */
class AnnouncementPlaybackQueue {
    private val queued = ArrayDeque<String>()
    private var activeId: String? = null

    var sessionOpen: Boolean = false
        private set
    var musicWasPlaying: Boolean = false
        private set
    private var speechStarted: Boolean = false

    val hasQueued: Boolean get() = queued.isNotEmpty()
    val isSpeaking: Boolean get() = activeId != null

    /** Returns true if this starts a new playback session. */
    fun enqueue(announcement: String, musicActive: Boolean): Boolean {
        val first = !sessionOpen
        if (first) {
            sessionOpen = true
            musicWasPlaying = musicActive
            speechStarted = false
        } else {
            observeMusicBeforeSpeech(musicActive)
        }
        queued.addLast(announcement)
        return first
    }

    /** Latch any media detected before our own TTS can contaminate isMusicActive. */
    fun observeMusicBeforeSpeech(musicActive: Boolean) {
        if (sessionOpen && !speechStarted) musicWasPlaying = musicWasPlaying || musicActive
    }

    fun startNext(utteranceId: String): String? {
        if (!sessionOpen || isSpeaking || queued.isEmpty()) return null
        activeId = utteranceId
        speechStarted = true
        return queued.removeFirst()
    }

    /** Returns false for late/duplicate callbacks from a previous utterance or session. */
    fun complete(utteranceId: String?): Boolean {
        if (utteranceId == null || activeId != utteranceId) return false
        activeId = null
        return true
    }

    fun cancel() {
        queued.clear()
        activeId = null
        sessionOpen = false
        musicWasPlaying = false
        speechStarted = false
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
