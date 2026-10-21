# NotificationAnnouncer: playback-pause regression checklist

This documents the **device-level** checks needed for the Pixel 10 regression where music
briefly paused but resumed while TextToSpeech was speaking. Passing CI confirms the
Android app compiles and the interruption policy tests pass; it **cannot** prove
that a particular third-party player obeys media-session transport commands.

## Expected behaviour

1. Start a music or podcast player and confirm it is playing.
2. Send one eligible notification (or tap **Test announcement**).
3. The same player pauses before speech and stays paused through the *entire* spoken message.
4. After the announcement burst completes, the original player resumes when the
   playback mode is **Automatic**. The app must not start some other paused player.
5. If more notifications arrive in quick succession, music stays paused through all
   speech and resumes only after the last one, including the completion grace interval.
6. A failed or stuck TTS callback must still clear the pause guard through the existing
   TTS watchdog and batch cleanup. Speech remains audible if audio focus is denied.

## Scenarios worth reproducing

- Single short notification, then a long notification lasting at least 20 seconds.
- Three overlapping notifications and continuous incoming notifications for a minute.
- Notification arrives exactly as a track changes or the player buffers.
- Bluetooth headphones/speaker, handset speaker and a wired audio device if available.
- Spotify, YouTube Music, VLC, a podcast player, and the exact player affected on the device.
- Music playing before TTS vs. music intentionally paused before TTS.
- Start a different player during an announcement. It should be silenced during TTS,
  but must not be auto-started by GymTracker's unrelated or prior music snapshot.
- **Automatic**, **Always resume**, **Never resume** settings, both with music playing
  and with no media player active.
- Place or receive a call. TTS should stop/defer appropriately and not speak over calls.
- Disable announcements from the persistent notification while speech is in progress.
- Leave the phone locked for several minutes; verify later notifications still speak.

## Diagnostic collection

The main app shows a speech-status line; capture it when an issue occurs. For
developers with ADB access, run:

```shell
adb logcat -s NotificationAnnouncer:V AudioManager:D MediaSessionService:D TextToSpeech:V
```

The app logs session-enumeration failures and players that continue reporting PLAYING
after repeated transport PAUSE commands. If a player ignores both targeted pause and
the fallback MEDIA_PAUSE key, Android does not provide a universal privileged API
to force-stop it: note the player name, Android version, and active media controls.

No broad or privileged media-control permission is required. The session lookup uses
the app's already-authorized NotificationListenerService identity.

## Implementation expectations

- Capture media-session identity before taking transient focus.
- Never discard focus merely because a TTS engine or Android sends a focus-loss
  callback while a spoken batch is active.
- Pause commands must be idempotent (PAUSE, not PLAY_PAUSE).
- The periodic hold and playback-state callbacks must stop after a batch or cancellation.
- Restore **only** the captured original music session(s), never a new session seen
  during TTS; don't issue global PLAY when a captured controller is known.
- Don't allow hold-related failures to wedge the speech queue or suppress TTS.
