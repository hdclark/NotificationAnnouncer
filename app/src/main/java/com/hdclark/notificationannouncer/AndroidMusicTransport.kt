package com.hdclark.notificationannouncer

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent

/**
 * Android adapter for MusicPlaybackHold.
 *
 * As an enabled NotificationListenerService this app may enumerate active media
 * sessions without MEDIA_CONTENT_CONTROL. Targeted TransportControls.pause/play
 * are far more reliable than sending a generic media button that can go to a
 * different player after audio focus or media-button routing changes.
 */
internal class AndroidMusicTransport(
    private val context: Context,
    private val audioManager: AudioManager?,
    private val onUnexpectedPlayback: () -> Unit = {},
) : MusicPlaybackHold.Transport {
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, NotificationAnnouncerService::class.java)
    private val knownControllers = mutableMapOf<String, MediaController>()
    private val stateCallbacks = mutableMapOf<String, MediaController.Callback>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ineffectivePauseAttempts = mutableMapOf<String, Int>()
    private var loggedDiscoveryFailure = false

    override fun sessions(): List<MusicPlaybackHold.Session>? {
        val manager = sessionManager ?: return null
        val active: List<MediaController> = try {
            manager.getActiveSessions(listener)
        } catch (error: Exception) {
            if (!loggedDiscoveryFailure) {
                Log.w(TAG, "Cannot enumerate media sessions; falling back to media keys", error)
                loggedDiscoveryFailure = true
            }
            return null
        }
        loggedDiscoveryFailure = false

        val preferredToken: MediaSession.Token? = if (Build.VERSION.SDK_INT >= 33) {
            runCatching { manager.mediaKeyEventSession }.getOrNull()
        } else null
        val controllers = active.toMutableList()
        if (preferredToken != null && controllers.none { it.sessionToken == preferredToken }) {
            runCatching { MediaController(context, preferredToken) }.getOrNull()
                ?.let { controllers.add(it) }
        }
        return controllers.filter { controller ->
            if (controller.packageName == context.packageName) return@filter false
            // Avoid pausing an accessibility / speech / call session, including a
            // TTS engine hosted in a different package from this application.
            val usage = controller.playbackInfo?.audioAttributes?.usage
            usage == null || usage == AudioAttributes.USAGE_UNKNOWN ||
                usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME
        }.map { controller ->
            val id = id(controller)
            knownControllers[id] = controller
            if (id !in stateCallbacks) {
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        if (state?.state in PLAYING_STATES) onUnexpectedPlayback()
                    }
                }
                runCatching { controller.registerCallback(callback, mainHandler) }
                    .onSuccess { stateCallbacks[id] = callback }
            }
            if (controller.playbackState?.state !in PLAYING_STATES) {
                ineffectivePauseAttempts.remove(id)
            }
            MusicPlaybackHold.Session(
                id = id,
                isPlaying = controller.playbackState?.state in PLAYING_STATES,
                isPreferred = controller.sessionToken == preferredToken
            )
        }
    }

    override fun pause(id: String) {
        val controller = knownControllers[id] ?: return
        val attempt = (ineffectivePauseAttempts[id] ?: 0) + 1
        ineffectivePauseAttempts[id] = attempt
        runCatching { controller.transportControls.pause() }.onFailure {
            Log.w(TAG, "Targeted media pause failed for ${controller.packageName}", it)
            dispatchToController(controller, KeyEvent.KEYCODE_MEDIA_PAUSE)
        }
        // The next poll only calls pause() if the controller STILL reports PLAYING.
        // Some players ignore TransportControls.pause despite not throwing an
        // exception. Escalate after two failed state transitions, not immediately.
        if (attempt >= 2) {
            dispatchToController(controller, KeyEvent.KEYCODE_MEDIA_PAUSE)
        }
        if (attempt >= 4 && attempt % 4 == 0) {
            Log.w(TAG, "Media session ${controller.packageName} still playing after $attempt pause attempts")
            dispatchToSystem(KeyEvent.KEYCODE_MEDIA_PAUSE)
        }
    }

    override fun play(id: String) {
        val controller = knownControllers[id] ?: return
        ineffectivePauseAttempts.remove(id)
        runCatching { controller.transportControls.play() }.onFailure {
            Log.w(TAG, "Targeted media resume failed for ${controller.packageName}", it)
            dispatchToController(controller, KeyEvent.KEYCODE_MEDIA_PLAY)
        }
    }

    override fun release() {
        stateCallbacks.forEach { (id, callback) ->
            knownControllers[id]?.let { controller ->
                runCatching { controller.unregisterCallback(callback) }
            }
        }
        stateCallbacks.clear()
        knownControllers.clear()
        ineffectivePauseAttempts.clear()
    }

    override fun pauseDefault() = dispatchToSystem(KeyEvent.KEYCODE_MEDIA_PAUSE)

    override fun playDefault() = dispatchToSystem(KeyEvent.KEYCODE_MEDIA_PLAY)

    private fun dispatchToController(controller: MediaController, keyCode: Int) {
        runCatching {
            controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }.onFailure { Log.w(TAG, "Targeted media key $keyCode failed", it) }
    }

    private fun dispatchToSystem(keyCode: Int) {
        runCatching {
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }.onFailure { Log.w(TAG, "Fallback media key $keyCode failed", it) }
    }

    private fun id(controller: MediaController): String =
        "${controller.packageName}:${controller.sessionToken.hashCode()}"

    companion object {
        private const val TAG = "NotificationAnnouncer"
        private val PLAYING_STATES = setOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING
        )
    }
}
