package com.hdclark.notificationannouncer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ComponentName
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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.UUID

class NotificationAnnouncerService : NotificationListenerService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val announcements = AnnouncementPlaybackQueue()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsGeneration = 0
    private var engineFailureCount = 0
    private var engineRestartScheduled = false
    private var destroyed = false
    private var audioManager: AudioManager? = null
    private var activeFocusRequest: AudioFocusRequest? = null
    private var focusGeneration = 0
    private var audioPrepared = false
    private var suppressAutomaticResume = false
    // Event-driven recheck quickly corrects media players that restart on focus gain.
    // A periodic poll remains as a fallback for players which never report state updates.
    private val enforcePlaybackRunnable: Runnable = Runnable {
        if (!destroyed && announcements.sessionOpen && audioPrepared) musicHold.enforce()
    }
    private val musicHold: MusicPlaybackHold by lazy {
        MusicPlaybackHold(AndroidMusicTransport(this, audioManager) {
            mainHandler.removeCallbacks(enforcePlaybackRunnable)
            mainHandler.postDelayed(enforcePlaybackRunnable, MEDIA_RESUME_DEBOUNCE_MS)
        })
    }
    private val maintainPauseRunnable = object : Runnable {
        override fun run() {
            if (!destroyed && announcements.sessionOpen && audioPrepared) {
                musicHold.enforce()
                mainHandler.postDelayed(this, MUSIC_HOLD_RECHECK_MS)
            }
        }
    }
    private var lastNotificationUptime = 0L
    private var firstNotificationUptime = 0L

    // Everything touching the queue, focus, or TTS state runs on the main looper.
    private val startNextRunnable = Runnable { startNextAnnouncement() }
    private val releaseFocusRunnable = Runnable { beginBatchCompletion() }
    private val finalizeBatchRunnable = Runnable { finalizeBatchCompletion() }
    private val ttsRestartRunnable = Runnable {
        engineRestartScheduled = false
        if (!destroyed) initializeTtsEngine()
    }
    private val ttsInitWatchdogRunnable = Runnable {
        if (!destroyed && !ttsReady) restartTtsEngine("TTS initialization timed out")
    }
    private var activeSpeechWatchdog: Runnable? = null

    private val announcementToggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_ANNOUNCEMENTS_TOGGLED -> {
                    if (!NotificationSettingsStore.areAnnouncementsEnabled(this@NotificationAnnouncerService)) {
                        stopCurrentAnnouncements()
                    }
                }
                ACTION_TEST_ANNOUNCEMENT -> {
                    if (NotificationSettingsStore.areAnnouncementsEnabled(this@NotificationAnnouncerService)) {
                        queueAnnouncement("This is a test announcement from Notification Announcer.")
                    } else {
                        reportStatus("Announcements are paused; enable them before testing")
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        ensureControlNotificationChannel(this)
        ContextCompat.registerReceiver(
            this,
            announcementToggleReceiver,
            IntentFilter().apply {
                addAction(ACTION_ANNOUNCEMENTS_TOGGLED)
                addAction(ACTION_TEST_ANNOUNCEMENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        reportStatus("Notification listener starting; initializing speech engine")
        initializeTtsEngine()
    }

    override fun onDestroy() {
        destroyed = true
        ttsGeneration++ // invalidate old engine callbacks
        runCatching { unregisterReceiver(announcementToggleReceiver) }
        stopCurrentAnnouncements()
        mainHandler.removeCallbacks(ttsRestartRunnable)
        mainHandler.removeCallbacks(ttsInitWatchdogRunnable)
        mainHandler.removeCallbacks(maintainPauseRunnable)
        mainHandler.removeCallbacks(enforcePlaybackRunnable)
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        updateControlNotification()
        reportStatus(if (ttsReady) "Ready to announce notifications" else "Listener connected; speech engine initializing")
    }

    override fun onListenerDisconnected() {
        stopCurrentAnnouncements()
        reportStatus("Notification listener disconnected; requesting rebind")
        // Android will no longer deliver posted notifications until the listener reconnects.
        // Rebinding is explicitly permitted by NotificationListenerService at this point.
        NotificationListenerService.requestRebind(ComponentName(this, NotificationAnnouncerService::class.java))
        super.onListenerDisconnected()
    }

    private fun updateControlNotification() {
        ensureControlNotificationChannel(this)
        getSystemService(NotificationManager::class.java)?.notify(
            CONTROL_NOTIFICATION_ID, buildControlNotification(this)
        )
    }

    private fun initializeTtsEngine() {
        if (destroyed) return
        engineRestartScheduled = false
        mainHandler.removeCallbacks(ttsRestartRunnable)
        mainHandler.removeCallbacks(ttsInitWatchdogRunnable)
        ttsReady = false
        val generation = ++ttsGeneration
        // Shutdown can emit onStop; both generation and utterance ID are checked.
        tts?.shutdown()
        tts = null
        try {
            tts = TextToSpeech(this, TextToSpeech.OnInitListener { status ->
                mainHandler.post {
                    if (!destroyed && generation == ttsGeneration) {
                        onTtsInitialized(status, generation)
                    }
                }
            })
            mainHandler.postDelayed(ttsInitWatchdogRunnable, TTS_INIT_TIMEOUT_MS)
        } catch (error: Exception) {
            restartTtsEngine("Unable to initialize TTS: ${error.javaClass.simpleName}")
        }
    }

    private fun onTtsInitialized(status: Int, generation: Int) {
        mainHandler.removeCallbacks(ttsInitWatchdogRunnable)
        if (status != TextToSpeech.SUCCESS) {
            restartTtsEngine("TTS engine initialization failed: $status")
            return
        }
        val engine = tts ?: run {
            restartTtsEngine("TTS engine missing after initialization")
            return
        }
        val configured = runCatching {
            engine.language = Locale.getDefault()
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    mainHandler.post {
                        if (generation == ttsGeneration && !destroyed) {
                            onUtteranceStarted(utteranceId)
                        }
                    }
                }

                override fun onDone(utteranceId: String?) =
                    postUtteranceFinished(utteranceId, generation, false, "completed")

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) =
                    postUtteranceFinished(utteranceId, generation, true, "TTS synthesis error")

                override fun onError(utteranceId: String?, errorCode: Int) =
                    postUtteranceFinished(utteranceId, generation, true, "TTS error $errorCode")

                override fun onStop(utteranceId: String?, interrupted: Boolean) =
                    postUtteranceFinished(utteranceId, generation, true, "TTS interrupted")
            })
        }.isSuccess
        if (!configured) {
            restartTtsEngine("Could not configure speech engine")
            return
        }
        ttsReady = true
        engineFailureCount = 0
        reportStatus("Speech engine ready")
        scheduleNextAnnouncement()
    }

    private fun restartTtsEngine(reason: String) {
        if (destroyed) return
        ttsReady = false
        mainHandler.removeCallbacks(ttsInitWatchdogRunnable)
        mainHandler.removeCallbacks(ttsRestartRunnable)
        reportStatus("$reason; restarting speech engine")
        engineFailureCount++
        if (engineFailureCount > MAX_ENGINE_RESTARTS) {
            reportStatus("$reason; retries exhausted. Will retry on next notification.")
            stopCurrentAnnouncements()
            ttsGeneration++
            tts?.shutdown()
            tts = null
            engineRestartScheduled = false
            return
        }
        engineRestartScheduled = true
        // Bounded backoff avoids continuously binding a broken TTS service.
        mainHandler.postDelayed(ttsRestartRunnable, TTS_RESTART_MS * engineFailureCount)
    }

    private fun postUtteranceFinished(
        id: String?, generation: Int, failed: Boolean, reason: String
    ): Unit {
        mainHandler.post {
            if (destroyed || generation != ttsGeneration) return@post
            if (id == null || announcements.activeUtteranceId != id) return@post
            clearSpeechWatchdog()
            if (failed) {
                recoverFailedUtterance(id, reason)
            } else if (announcements.complete(id)) {
                reportStatus("Announcement completed")
                if (announcements.hasQueued) scheduleNextAnnouncement() else scheduleBatchCompletion()
            }
        }
    }

    private fun onUtteranceStarted(id: String?) {
        if (id == null || id != announcements.activeUtteranceId) return
        reportStatus("Speaking announcement")
        musicHold.enforce()
        // Startup watchdog is replaced with a duration-dependent *completion* watchdog.
        // It must accommodate large spoken messages without timing out normal TTS.
        val maximumDuration = (
            TTS_COMPLETION_BASE_MS + announcements.activeTextLength * TTS_MS_PER_CHARACTER
        ).coerceIn(TTS_MIN_COMPLETION_MS, TTS_MAX_COMPLETION_MS)
        watchUtterance(id, maximumDuration)
    }

    private fun watchUtterance(id: String, delayMs: Long) {
        clearSpeechWatchdog()
        val generation = ttsGeneration
        val runnable = Runnable {
            if (!destroyed && generation == ttsGeneration && announcements.activeUtteranceId == id) {
                recoverFailedUtterance(id, "TTS timed out (no progress callback)")
            }
        }
        activeSpeechWatchdog = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun clearSpeechWatchdog() {
        activeSpeechWatchdog?.let { mainHandler.removeCallbacks(it) }
        activeSpeechWatchdog = null
    }

    private fun recoverFailedUtterance(id: String, reason: String) {
        if (announcements.activeUtteranceId != id) return
        clearSpeechWatchdog()
        val retry = announcements.retry(id)
        reportStatus(if (retry) "$reason; retrying with a new speech engine" else "$reason; skipping after retry")
        // A stuck TTS binder needs re-creation; a retry using the same engine can hang too.
        ttsReady = false
        ttsGeneration++ // stale TTS callbacks cannot complete the replacement utterance
        tts?.stop()
        if (!retry && !announcements.hasQueued) {
            tts?.shutdown()
            tts = null
            scheduleBatchCompletion()
        } else {
            // Recovery occurs with the previous audio focus/session still held.
            restartTtsEngine(reason)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { if (!destroyed) onNotificationPosted(sbn, rankingMap) }
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

        val appLabel = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        }.getOrElse { sbn.packageName }
        queueAnnouncement("Announcement. $appLabel. $spokenText")
    }

    private fun queueAnnouncement(text: String) {
        val firstPendingAnnouncement = !announcements.hasQueued
        val firstInBurst = announcements.enqueue(text, audioManager?.isMusicActive == true)
        lastNotificationUptime = SystemClock.uptimeMillis()
        // Each new *pending group* gets its own bounded quiet window, even when
        // previous announcements are still finishing in the same music session.
        if (firstPendingAnnouncement) firstNotificationUptime = lastNotificationUptime
        if (firstInBurst) suppressAutomaticResume = false
        reportStatus("Announcement queued (${announcements.queuedCount} waiting)")
        if (!ttsReady && tts == null && !engineRestartScheduled) {
            engineFailureCount = 0
            initializeTtsEngine()
        }
        if (!announcements.isSpeaking) scheduleNextAnnouncement()
    }

    private fun scheduleNextAnnouncement() {
        mainHandler.removeCallbacks(startNextRunnable)
        mainHandler.removeCallbacks(releaseFocusRunnable)
        mainHandler.removeCallbacks(finalizeBatchRunnable)
        if (destroyed || !announcements.hasQueued || announcements.isSpeaking || !ttsReady) return
        val remaining = AnnouncementTiming.waitBeforeSpeaking(
            firstNotificationUptime, lastNotificationUptime, SystemClock.uptimeMillis(),
            NOTIFICATION_QUIET_MS, MAX_QUIET_WAIT_MS,
        )
        mainHandler.postDelayed(startNextRunnable, remaining)
    }

    private fun startNextAnnouncement() {
        if (destroyed || !announcements.hasQueued || announcements.isSpeaking || !ttsReady) return
        val remaining = AnnouncementTiming.waitBeforeSpeaking(
            firstNotificationUptime, lastNotificationUptime, SystemClock.uptimeMillis(),
            NOTIFICATION_QUIET_MS, MAX_QUIET_WAIT_MS,
        )
        if (remaining > 0L) {
            mainHandler.postDelayed(startNextRunnable, remaining)
            return
        }
        val manager = audioManager
        // Do not speak private notifications into an ongoing call; retry when the call ends.
        if (manager?.mode == AudioManager.MODE_IN_CALL ||
            manager?.mode == AudioManager.MODE_IN_COMMUNICATION
        ) {
            reportStatus("Call or communication audio active; announcement deferred")
            mainHandler.postDelayed(startNextRunnable, CALL_RECHECK_MS)
            return
        }
        if (!audioPrepared) {
            prepareAnnouncementAudio()
            mainHandler.postDelayed(startNextRunnable, FOCUS_SETTLE_MS)
            return
        }

        // A player can auto-resume between the initial focus request and TTS startup.
        musicHold.enforce()
        val id = UUID.randomUUID().toString()
        val announcement = announcements.startNext(id) ?: return
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        reportStatus("Sending announcement to speech engine")
        watchUtterance(id, TTS_START_TIMEOUT_MS)
        val result = runCatching {
            // Each request is isolated: no hidden TTS engine queue can hold an old utterance.
            tts?.speak(announcement, TextToSpeech.QUEUE_FLUSH, params, id)
        }.getOrNull()
        if (result != TextToSpeech.SUCCESS) {
            recoverFailedUtterance(id, "TTS rejected speech request")
        }
    }

    private fun prepareAnnouncementAudio() {
        val manager = audioManager
        if (manager == null) {
            reportStatus("Audio manager unavailable; announcements may overlap media")
            audioPrepared = true
            return
        }

        // Snapshot which specific music sessions were playing BEFORE our focus
        // request. A transient focus change can otherwise obscure their state.
        announcements.observeMusicBeforeSpeech(manager.isMusicActive)
        musicHold.begin(announcements.musicWasPlaying)
        audioPrepared = true
        mainHandler.removeCallbacks(maintainPauseRunnable)
        mainHandler.postDelayed(maintainPauseRunnable, MUSIC_HOLD_RECHECK_MS)

        val session = ++focusGeneration
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            mainHandler.post {
                if (destroyed || session != focusGeneration || !announcements.sessionOpen) return@post
                if (change == AudioManager.AUDIOFOCUS_LOSS &&
                    (audioManager?.mode == AudioManager.MODE_IN_CALL ||
                        audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION)
                ) {
                    stopCurrentAnnouncements(restorePlayback = false)
                    reportStatus("Speech stopped for a call or communication session")
                    return@post
                }
                // CRITICAL: an external TTS engine may itself request focus just as it
                // starts speaking. Abandoning OUR focus here can immediately allow the
                // previous music player to resume. Never release it before batch end.
                // Independently keep the exact original music session paused even if
                // another focus owner becomes active.
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ||
                    change == AudioManager.AUDIOFOCUS_GAIN
                ) {
                    musicHold.enforce()
                }
            }
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(listener, mainHandler)
            .build()
        val granted = runCatching {
            manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.getOrDefault(false)
        if (granted) {
            activeFocusRequest = request
        } else {
            // This is NOT a reason to drop notifications or speak over music:
            // direct transport pause works separately from audio-focus ownership.
            reportStatus("Audio focus denied; actively holding media sessions paused")
        }
        musicHold.enforce()
    }

    private fun scheduleBatchCompletion() {
        clearSpeechWatchdog()
        mainHandler.removeCallbacks(startNextRunnable)
        mainHandler.removeCallbacks(releaseFocusRunnable)
        mainHandler.removeCallbacks(finalizeBatchRunnable)
        mainHandler.postDelayed(releaseFocusRunnable, BATCH_GRACE_MS)
    }

    private fun beginBatchCompletion() {
        if (announcements.isSpeaking || announcements.hasQueued || !announcements.sessionOpen) return
        abandonAnnouncementAudioFocus()
        // Keep the pause guard running while focus restoration settles; some
        // players automatically start again on transient AUDIOFOCUS_GAIN.
        musicHold.enforce()
        mainHandler.postDelayed(finalizeBatchRunnable, FOCUS_RELEASE_SETTLE_MS)
    }

    private fun finalizeBatchCompletion() {
        if (announcements.isSpeaking || announcements.hasQueued || !announcements.sessionOpen) return
        mainHandler.removeCallbacks(maintainPauseRunnable)
        mainHandler.removeCallbacks(enforcePlaybackRunnable)
        musicHold.finish(
            NotificationSettingsStore.getMusicResumeMode(this),
            automaticResumeAllowed = !suppressAutomaticResume
        )
        audioPrepared = false
        announcements.cancel()
        suppressAutomaticResume = false
        engineFailureCount = 0
        reportStatus(if (ttsReady) "Ready to announce notifications" else "Speech engine unavailable; the next notification will trigger recovery")
    }

    private fun stopCurrentAnnouncements(restorePlayback: Boolean = true) {
        mainHandler.removeCallbacks(startNextRunnable)
        mainHandler.removeCallbacks(releaseFocusRunnable)
        mainHandler.removeCallbacks(finalizeBatchRunnable)
        clearSpeechWatchdog()
        val hadSession = announcements.sessionOpen
        mainHandler.removeCallbacks(maintainPauseRunnable)
        mainHandler.removeCallbacks(enforcePlaybackRunnable)
        announcements.cancel()
        tts?.stop()
        abandonAnnouncementAudioFocus()
        if (hadSession) {
            musicHold.cancel(
                if (restorePlayback) NotificationSettingsStore.getMusicResumeMode(this)
                else MusicResumeMode.NEVER,
                automaticResumeAllowed = !suppressAutomaticResume && restorePlayback
            )
        }
        audioPrepared = false
        suppressAutomaticResume = false
    }

    private fun abandonAnnouncementAudioFocus() {
        focusGeneration++
        val request = activeFocusRequest ?: return
        activeFocusRequest = null
        audioManager?.abandonAudioFocusRequest(request)
    }

    private fun reportStatus(message: String) {
        Log.i(TAG, message)
        NotificationSettingsStore.setSpeechStatus(this, message)
        sendBroadcast(Intent(ACTION_APP_LIST_UPDATED).setPackage(packageName))
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
        private const val MAX_QUIET_WAIT_MS = 6_000L
        private const val BATCH_GRACE_MS = 1_500L
        private const val FOCUS_SETTLE_MS = 350L
        private const val MUSIC_HOLD_RECHECK_MS = 700L
        private const val MEDIA_RESUME_DEBOUNCE_MS = 120L
        private const val FOCUS_RELEASE_SETTLE_MS = 350L
        private const val MAX_ENGINE_RESTARTS = 3
        private const val TTS_INIT_TIMEOUT_MS = 12_000L
        private const val TTS_RESTART_MS = 1_500L
        private const val TTS_START_TIMEOUT_MS = 12_000L
        private const val TTS_COMPLETION_BASE_MS = 15_000L
        private const val TTS_MIN_COMPLETION_MS = 30_000L
        private const val TTS_MAX_COMPLETION_MS = 600_000L
        private const val TTS_MS_PER_CHARACTER = 120L
        private const val CALL_RECHECK_MS = 3_000L
        const val ACTION_TEST_ANNOUNCEMENT = "com.hdclark.notificationannouncer.TEST_ANNOUNCEMENT"
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
