package com.hdclark.notificationannouncer

/**
 * Owns one interruption of external music playback across a whole notification burst.
 *
 * Deliberately independent of Android types so both the interruption rules and all
 * transport actions can be exercised with a fake media player in JVM tests.
 *
 * The snapshot is captured once, BEFORE requesting transient focus. Later focus
 * changes, TTS output, or new notifications must not change what gets resumed.
 */
internal class MusicPlaybackHold(private val transport: Transport) {
    data class Session(val id: String, val isPlaying: Boolean, val isPreferred: Boolean = false)

    interface Transport {
        /** null when session discovery is unavailable (e.g. listener access revoked). */
        fun sessions(): List<Session>?
        fun pause(id: String)
        fun play(id: String)
        fun pauseDefault()
        fun playDefault()
        fun release() = Unit
    }

    private val originalSessions = linkedSetOf<String>()
    private var wasMusicPlaying = false
    private var fallback = false
    private var competingSessionSeen = false
    var isOpen: Boolean = false
        private set

    fun begin(musicWasPlaying: Boolean) {
        if (isOpen) return
        isOpen = true
        originalSessions.clear()
        fallback = false
        competingSessionSeen = false

        val sessions = transport.sessions()
        val playing = sessions.orEmpty().filter { it.isPlaying }
        // A playing media session is a stronger signal than isMusicActive, which
        // can temporarily report false around a notification sound or focus loss.
        wasMusicPlaying = musicWasPlaying || playing.isNotEmpty()
        if (playing.isNotEmpty()) {
            originalSessions.addAll(playing.map { it.id })
        } else if (musicWasPlaying) {
            // Music may already be transiently paused by the incoming notification.
            // Prefer Android's media-button recipient, not an arbitrary paused app.
            val preferred = sessions.orEmpty().filter { it.isPreferred }
            if (preferred.isNotEmpty()) {
                originalSessions.addAll(preferred.map { it.id })
            } else if (sessions?.size == 1) {
                originalSessions.add(sessions.single().id)
            }
        }

        // Do not resume an unrelated player merely because some other audio (e.g.
        // a notification sound) was active. The queue's pre-speech snapshot is the
        // authority for automatic resumption.
        if (!wasMusicPlaying) originalSessions.clear()
        if (originalSessions.isEmpty() && wasMusicPlaying) {
            fallback = true
            transport.pauseDefault()
        } else {
            // Only one idempotent pause per target, even if it was already playing.
            (playing.map { it.id } + originalSessions).distinct().forEach { transport.pause(it) }
        }
    }

    /** Reassert the hold after focus callbacks or an audio player auto-resumes. */
    fun enforce() {
        if (!isOpen) return
        val sessions = transport.sessions()
        if (sessions == null || sessions.isEmpty()) {
            if (fallback && wasMusicPlaying) transport.pauseDefault()
            return
        }
        // We silence new players started during TTS, but will NOT auto-resume them
        // or override their newly chosen music by restoring the original one.
        sessions.filter { it.isPlaying }.forEach {
            if (it.id !in originalSessions) competingSessionSeen = true
            transport.pause(it.id)
        }
    }

    /**
     * Call only AFTER transient focus was abandoned and its restoration settled.
     * Return no global PLAY when a known target exists: it could start the wrong app.
     */
    fun finish(mode: MusicResumeMode, automaticResumeAllowed: Boolean) {
        if (!isOpen) return
        val sessions = transport.sessions().orEmpty()
        val otherPlayerIsPlaying = sessions.any {
            it.isPlaying && it.id !in originalSessions
        }
        val restore = when (mode) {
            MusicResumeMode.ALWAYS -> true
            MusicResumeMode.NEVER -> false
            MusicResumeMode.AUTOMATIC ->
                wasMusicPlaying && automaticResumeAllowed &&
                    !otherPlayerIsPlaying && !competingSessionSeen
        }
        if (restore) {
            if (originalSessions.isNotEmpty()) {
                originalSessions.forEach { id ->
                    if (sessions.none { it.id == id && it.isPlaying }) transport.play(id)
                }
            } else if ((fallback && wasMusicPlaying) || mode == MusicResumeMode.ALWAYS) {
                // Best effort for older / non-media-session playback apps.
                transport.playDefault()
            }
        } else if (mode == MusicResumeMode.NEVER) {
            enforce()
        }
        clear()
    }

    /** Abnormal shutdown must not retain stale playback targets. */
    fun cancel(mode: MusicResumeMode, automaticResumeAllowed: Boolean) =
        finish(mode, automaticResumeAllowed)

    private fun clear() {
        transport.release()
        originalSessions.clear()
        wasMusicPlaying = false
        fallback = false
        competingSessionSeen = false
        isOpen = false
    }
}
