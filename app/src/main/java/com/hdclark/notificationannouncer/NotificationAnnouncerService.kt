package com.hdclark.notificationannouncer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.UUID

class NotificationAnnouncerService : NotificationListenerService(), TextToSpeech.OnInitListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val announcements = AnnouncementPlaybackQueue()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var destroyed = false
    private var audioManager: AudioManager? = null
    private var activeFocusRequest: AudioFocusRequest? = null
    private var lastNotificationUptime = 0L
    private var focusRetryCount = 0

    // Only the main looper touches the queue, media focus and pending commands. TTS callbacks
    // can come from arbitrary threads, so they must be marshalled back to this looper.
    private val startNextRunnable = Runnable { startNextAnnouncement() }
    private val releaseFocusRunnable = Runnable { beginBatchCompletion() }
    private val finalizeBatchRunnable = Runnable { finalizeBatchCompletion() }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // A permanent loss means e.g. the user started another playback session. Do not
        // restart the previous player's music on their behalf in that situation.
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            mainHandler.post {
                // A late callback for a focus request we already abandoned must not
                // cancel the queued playback restoration or a subsequent burst.
                if (!destroyed && activeFocusRequest != null) {
                    stopCurrentAnnouncements(restorePlayback = false)
                }
            }
        }
    }

    private val announcementToggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_ANNOUNCEMENTS_TOGGLED &&
                !NotificationSettingsStore.areAnnouncementsEnabled(this@NotificationAnnouncerService)
            ) {
                stopCurrentAnnouncements()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        ensureControlNotificationChannel(this)
        tts = TextToSpeech(this, this)
        ContextCompat.registerReceiver(
            this,
            announcementToggleReceiver,
            IntentFilter(ACTION_ANNOUNCEMENTS_TOGGLED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onDestroy() {
        destroyed = true
        runCatching { unregisterReceiver(announcementToggleReceiver) }
        stopCurrentAnnouncements()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        updateControlNotification()
    }

    private fun updateControlNotification() {
        ensureControlNotificationChannel(this)
        getSystemService(NotificationManager::class.java)?.notify(
            CONTROL_NOTIFICATION_ID,
            buildControlNotification(this),
        )
    }

    override fun onInit(status: Int) {
        mainHandler.post {
            if (destroyed) return@post
            ttsReady = status == TextToSpeech.SUCCESS
            if (!ttsReady) {
                Log.w(TAG, "TTS engine initialization failed")
                return@post
            }
            tts?.language = Locale.getDefault()
            val speechAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(speechAttributes)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) = postUtteranceFinished(utteranceId)

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = postUtteranceFinished(utteranceId)

                override fun onError(utteranceId: String?, errorCode: Int) =
                    postUtteranceFinished(utteranceId)

                override fun onStop(utteranceId: String?, interrupted: Boolean) =
                    postUtteranceFinished(utteranceId)
            })
        }
    }

    private fun postUtteranceFinished(id: String?) {
        mainHandler.post {
            if (destroyed || !announcements.complete(id)) return@post
            if (announcements.hasQueued) scheduleNextAnnouncement() else scheduleBatchCompletion()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                if (!destroyed) onNotificationPosted(sbn, rankingMap)
            }
            return
        }
        if (destroyed || sbn == null || sbn.packageName == packageName) return
        if (!isNonSilent(sbn, rankingMap)) return

        NotificationSettingsStore.updateAppLastSeen(this, sbn.packageName, System.currentTimeMillis())
        sendBroadcast(Intent(ACTION_APP_LIST_UPDATED).setPackage(packageName))

        if (!NotificationSettingsStore.isPackageEnabled(this, sbn.packageName)) return
        if (!NotificationSettingsStore.areAnnouncementsEnabled(this)) return

        val spokenText = extractNotificationText(sbn.notification)
        if (spokenText.isBlank()) return
        if (NotificationPolicy.shouldFilterByFragments(
                spokenText, NotificationSettingsStore.getExcludeFragments(this)
            )
        ) return
        if (!ttsReady) return

        val appLabel = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        }.getOrElse { sbn.packageName }

        // One burst owns the original media state, regardless of how many notifications
        // arrive while speech has already paused that media.
        announcements.enqueue(
            "Announcement. $appLabel. $spokenText",
            audioManager?.isMusicActive == true,
        )
        lastNotificationUptime = SystemClock.uptimeMillis()
        if (!announcements.isSpeaking) scheduleNextAnnouncement()
    }

    private fun scheduleNextAnnouncement() {
        mainHandler.removeCallbacks(startNextRunnable)
        mainHandler.removeCallbacks(releaseFocusRunnable)
        mainHandler.removeCallbacks(finalizeBatchRunnable)
        if (!announcements.hasQueued || announcements.isSpeaking) return
        // A *quiet window* rather than a 3-second sleep for each message: allow Android's
        // notification ding and playback changes to settle, coalesce notification bursts,
        // but do not add 3 seconds between messages that were already queued.
        val remaining = (lastNotificationUptime + NOTIFICATION_QUIET_MS -
            SystemClock.uptimeMillis()).coerceAtLeast(0L)
        mainHandler.postDelayed(startNextRunnable, remaining)
    }

    private fun startNextAnnouncement() {
        if (destroyed || !announcements.hasQueued || announcements.isSpeaking) return
        val remaining = lastNotificationUptime + NOTIFICATION_QUIET_MS -
            SystemClock.uptimeMillis()
        if (remaining > 0L) {
            mainHandler.postDelayed(startNextRunnable, remaining)
            return
        }

        if (activeFocusRequest == null) {
            if (!acquireAnnouncementAudioFocus()) {
                focusRetryCount++
                if (focusRetryCount <= MAX_FOCUS_RETRIES) {
                    mainHandler.postDelayed(startNextRunnable, FOCUS_RETRY_MS)
                } else {
                    Log.w(TAG, "Audio focus unavailable; skipping burst to avoid speaking over media")
                    stopCurrentAnnouncements(restorePlayback = false)
                }
                return
            }
            focusRetryCount = 0
            // Media PAUSE and Android audio-focus changes are asynchronous. Give them a
            // chance to complete before TTS begins instead of talking over music.
            mainHandler.postDelayed(startNextRunnable, FOCUS_SETTLE_MS)
            return
        }

        val id = UUID.randomUUID().toString()
        val announcement = announcements.startNext(id) ?: return
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val speakResult = tts?.speak(announcement, TextToSpeech.QUEUE_ADD, params, id)
        if (speakResult != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS failed to enqueue an announcement")
            if (announcements.complete(id)) {
                if (announcements.hasQueued) scheduleNextAnnouncement() else scheduleBatchCompletion()
            }
        }
    }

    private fun acquireAnnouncementAudioFocus(): Boolean {
        val manager = audioManager ?: return false
        // Latch once, before TTS itself can make isMusicActive report true.
        announcements.observeMusicBeforeSpeech(manager.isMusicActive)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(audioFocusListener, mainHandler)
            .build()
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return false
        }
        activeFocusRequest = request
        // Idempotent PAUSE, never the PLAY_PAUSE toggle. Some media players do not
        // pause reliably on transient focus loss alone.
        if (announcements.musicWasPlaying ||
            NotificationSettingsStore.getMusicResumeMode(this) != MusicResumeMode.AUTOMATIC
        ) dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        return true
    }

    private fun scheduleBatchCompletion() {
        mainHandler.removeCallbacks(startNextRunnable)
        mainHandler.removeCallbacks(releaseFocusRunnable)
        mainHandler.removeCallbacks(finalizeBatchRunnable)
        // Keep the original music snapshot/focus across a short gap after the last TTS
        // callback. A second notification must not be mistaken for a new idle session.
        mainHandler.postDelayed(releaseFocusRunnable, BATCH_GRACE_MS)
    }

    private fun beginBatchCompletion() {
        if (announcements.isSpeaking || announcements.hasQueued || !announcements.sessionOpen) return
        abandonAnnouncementAudioFocus()
        if (NotificationSettingsStore.getMusicResumeMode(this) == MusicResumeMode.NEVER) {
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        }
        // System focus restoration can be asynchronous; apply the explicit playback
        // preference after that transition, without letting a new notification reset state.
        mainHandler.postDelayed(finalizeBatchRunnable, FOCUS_RELEASE_SETTLE_MS)
    }

    private fun finalizeBatchCompletion() {
        if (announcements.isSpeaking || announcements.hasQueued || !announcements.sessionOpen) return
        applyPlaybackResumeMode(announcements.musicWasPlaying)
        announcements.cancel()
        focusRetryCount = 0
    }

    private fun applyPlaybackResumeMode(wasMusicPlaying: Boolean) {
        when (NotificationSettingsStore.getMusicResumeMode(this)) {
            MusicResumeMode.ALWAYS -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            MusicResumeMode.NEVER -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            MusicResumeMode.AUTOMATIC -> {
                // Focus restoration may already have resumed playback. Do not send a
                // redundant PLAY when Android says audio is already active.
                if (wasMusicPlaying && audioManager?.isMusicActive != true) {
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                }
            }
        }
    }

    private fun stopCurrentAnnouncements(restorePlayback: Boolean = true) {
        mainHandler.removeCallbacks(startNextRunnable)
        mainHandler.removeCallbacks(releaseFocusRunnable)
        mainHandler.removeCallbacks(finalizeBatchRunnable)
        val hadSession = announcements.sessionOpen
        val wasPlaying = announcements.musicWasPlaying
        announcements.cancel() // invalidates all outstanding TTS callbacks
        tts?.stop()
        abandonAnnouncementAudioFocus()
        focusRetryCount = 0
        if (hadSession && restorePlayback) applyPlaybackResumeMode(wasPlaying)
    }

    private fun abandonAnnouncementAudioFocus() {
        val request = activeFocusRequest ?: return
        activeFocusRequest = null
        audioManager?.abandonAudioFocusRequest(request)
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val manager = audioManager ?: return
        manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun isNonSilent(
        sbn: StatusBarNotification,
        rankingMap: RankingMap?,
    ): Boolean {
        val ranking = Ranking()
        if (rankingMap?.getRanking(sbn.key, ranking) == true) {
            return ranking.importance > NotificationManager.IMPORTANCE_LOW
        }

        val notification = sbn.notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = notification.channelId
            if (!channelId.isNullOrBlank()) {
                val channelImportance =
                    getSystemService(NotificationManager::class.java)?.getNotificationChannel(channelId)?.importance
                if (channelImportance != null) {
                    return channelImportance > NotificationManager.IMPORTANCE_LOW
                }
            }
        }

        @Suppress("DEPRECATION")
        return notification.priority >= Notification.PRIORITY_DEFAULT
    }

    private fun extractNotificationText(notification: Notification): String {
        val extras = notification.extras ?: return ""
        val parts = listOf(
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty(),
        ).filter { it.isNotBlank() }

        return NotificationPolicy.deduplicateRepeatedBlocks(parts).trim()
    }

    companion object {
        private const val TAG = "NotificationAnnouncer"
        private const val NOTIFICATION_QUIET_MS = 3_000L
        private const val BATCH_GRACE_MS = 1_500L
        private const val FOCUS_SETTLE_MS = 350L
        private const val FOCUS_RELEASE_SETTLE_MS = 350L
        private const val FOCUS_RETRY_MS = 1_000L
        private const val MAX_FOCUS_RETRIES = 5
        const val ACTION_APP_LIST_UPDATED = "com.hdclark.notificationannouncer.APP_LIST_UPDATED"
        const val ACTION_TOGGLE_ANNOUNCEMENTS = "com.hdclark.notificationannouncer.TOGGLE_ANNOUNCEMENTS"
        const val ACTION_ANNOUNCEMENTS_TOGGLED = "com.hdclark.notificationannouncer.ANNOUNCEMENTS_TOGGLED"
        const val CONTROL_NOTIFICATION_CHANNEL_ID = "announcer_controls"
        const val CONTROL_NOTIFICATION_ID = 1001
    }
}

class AnnouncementControlService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureControlNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NotificationAnnouncerService.CONTROL_NOTIFICATION_ID,
            buildControlNotification(this),
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class AnnouncementToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != NotificationAnnouncerService.ACTION_TOGGLE_ANNOUNCEMENTS) return
        val enabled = NotificationSettingsStore.areAnnouncementsEnabled(context)
        NotificationSettingsStore.setAnnouncementsEnabled(context, !enabled)
        ensureControlNotificationChannel(context)
        ContextCompat.startForegroundService(
            context,
            Intent(context, AnnouncementControlService::class.java),
        )
        context.sendBroadcast(Intent(NotificationAnnouncerService.ACTION_ANNOUNCEMENTS_TOGGLED).setPackage(context.packageName))
        context.sendBroadcast(Intent(NotificationAnnouncerService.ACTION_APP_LIST_UPDATED).setPackage(context.packageName))
    }
}

private fun ensureControlNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel = NotificationChannel(
        NotificationAnnouncerService.CONTROL_NOTIFICATION_CHANNEL_ID,
        context.getString(R.string.control_notification_channel_name),
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        description = context.getString(R.string.control_notification_channel_description)
        setSound(null, null)
    }
    context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
}

private fun buildControlNotification(context: Context): Notification {
    val enabled = NotificationSettingsStore.areAnnouncementsEnabled(context)
    val launchIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val toggleIntent = PendingIntent.getBroadcast(
        context,
        1,
        Intent(context, AnnouncementToggleReceiver::class.java).setAction(NotificationAnnouncerService.ACTION_TOGGLE_ANNOUNCEMENTS),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return NotificationCompat.Builder(context, NotificationAnnouncerService.CONTROL_NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification_announcer)
        .setContentTitle(context.getString(R.string.control_notification_title))
        .setContentText(context.getString(if (enabled) R.string.announcements_on else R.string.announcements_off))
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(launchIntent)
        .addAction(
            android.R.drawable.ic_media_pause,
            context.getString(if (enabled) R.string.pause_announcements else R.string.resume_announcements),
            toggleIntent,
        )
        .build()
}
