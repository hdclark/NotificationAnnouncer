package com.hdclark.notificationannouncer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import java.util.Locale
import java.util.UUID

class NotificationAnnouncerService : NotificationListenerService(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var audioManager: AudioManager? = null
    private var activeFocusRequest: AudioFocusRequest? = null
    private var shouldResumeMusicAfterAnnouncement = false
    private var pendingAnnouncementCount = 0

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
        startForeground(CONTROL_NOTIFICATION_ID, buildControlNotification(this))
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(announcementToggleReceiver) }
        tts?.shutdown()
        tts = null
        stopCurrentAnnouncements()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        updateControlNotification()
    }

    private fun updateControlNotification() {
        ensureControlNotificationChannel(this)
        startForeground(CONTROL_NOTIFICATION_ID, buildControlNotification(this))
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.language = Locale.getDefault()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = finishAnnouncementAudio()
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finishAnnouncementAudio()
                override fun onError(utteranceId: String?, errorCode: Int) = finishAnnouncementAudio()
            })
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn == null) return
        if (sbn.packageName == packageName) return
        if (!isNonSilent(sbn, rankingMap)) return

        NotificationSettingsStore.updateAppLastSeen(this, sbn.packageName, System.currentTimeMillis())
        sendBroadcast(Intent(ACTION_APP_LIST_UPDATED).setPackage(packageName))

        if (!NotificationSettingsStore.isPackageEnabled(this, sbn.packageName)) return
        if (!NotificationSettingsStore.areAnnouncementsEnabled(this)) return

        val spokenText = extractNotificationText(sbn.notification)
        if (spokenText.isBlank()) return

        val excludeFragments = NotificationSettingsStore.getExcludeFragments(this)
        if (NotificationPolicy.shouldFilterByFragments(spokenText, excludeFragments)) return

        if (!ttsReady) return

        val appLabel = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        }.getOrElse { sbn.packageName }

        beginAnnouncementAudio()
        val announcement = "Announcement. $appLabel. $spokenText"
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        val speakResult = tts?.speak(announcement, TextToSpeech.QUEUE_ADD, params, UUID.randomUUID().toString())
        if (speakResult != TextToSpeech.SUCCESS) {
            finishAnnouncementAudio()
        }
    }

    @Synchronized
    private fun beginAnnouncementAudio() {
        val manager = audioManager ?: return
        val isFirstQueuedAnnouncement = pendingAnnouncementCount == 0
        pendingAnnouncementCount += 1

        if (isFirstQueuedAnnouncement) {
            shouldResumeMusicAfterAnnouncement = manager.isMusicActive
            if (shouldResumeMusicAfterAnnouncement) {
                manager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
                manager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { }
                    .build()
                if (manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    activeFocusRequest = request
                }
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
        }
    }

    @Synchronized
    private fun finishAnnouncementAudio() {
        if (pendingAnnouncementCount > 0) {
            pendingAnnouncementCount -= 1
        }
        if (pendingAnnouncementCount > 0) return

        abandonAnnouncementAudioFocus()
        if (shouldResumeMusicAfterAnnouncement) {
            audioManager?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
            audioManager?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
            shouldResumeMusicAfterAnnouncement = false
        }
    }

    @Synchronized
    private fun stopCurrentAnnouncements() {
        tts?.stop()
        pendingAnnouncementCount = 0
        finishAnnouncementAudio()
    }

    private fun abandonAnnouncementAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activeFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
            activeFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(null)
        }
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
        const val ACTION_APP_LIST_UPDATED = "com.hdclark.notificationannouncer.APP_LIST_UPDATED"
        const val ACTION_TOGGLE_ANNOUNCEMENTS = "com.hdclark.notificationannouncer.TOGGLE_ANNOUNCEMENTS"
        const val ACTION_ANNOUNCEMENTS_TOGGLED = "com.hdclark.notificationannouncer.ANNOUNCEMENTS_TOGGLED"
        const val CONTROL_NOTIFICATION_CHANNEL_ID = "announcer_controls"
        const val CONTROL_NOTIFICATION_ID = 1001
    }
}

class AnnouncementToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != NotificationAnnouncerService.ACTION_TOGGLE_ANNOUNCEMENTS) return
        val enabled = NotificationSettingsStore.areAnnouncementsEnabled(context)
        NotificationSettingsStore.setAnnouncementsEnabled(context, !enabled)
        ensureControlNotificationChannel(context)
        context.getSystemService(NotificationManager::class.java)?.notify(
            NotificationAnnouncerService.CONTROL_NOTIFICATION_ID,
            buildControlNotification(context),
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
