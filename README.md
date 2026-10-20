# NotificationAnnouncer

Android app that reads non-silent notifications aloud in the background using Text-to-Speech.

## Build APK

Developers should use GitHub Actions CI to build APKs. The workflow in `.github/workflows/android-ci.yml` runs unit tests, assembles `app-debug.apk`, and uploads the APK as a workflow artifact.


## Music interruption and de-sync prevention

Notifications are announced serially as one **burst**. A 3-second quiet window, measured
from the most recent eligible notification, allows system notification sounds to finish
before speech begins. New notifications arriving while speaking join the existing burst
rather than starting another audio-focus transaction. The app holds one transient audio
focus request until all utterances finish plus a 1.5-second burst grace period. Audio
focus and PAUSE/PLAY media-key commands are separate Android mechanisms; a short
settling interval after requesting/releasing focus prevents them from racing TTS.

In the main screen, **Music playback after announcements** offers:

- **Automatic (default):** sample whether music was active before any speech in the
  burst, and resume it afterward if needed. Music is not sampled again during TTS,
  because TTS itself may make Android report music as active.
- **Always resume music:** issue an explicit PLAY at the end of the burst, even when
  playback was not detected beforehand. This can start a paused media player.
- **Never resume music:** issue an explicit PAUSE after the burst, including after
  Android has had time to restore transient audio focus. This can pause music that the
  user manually started during the announcement.

The setting is stored in the existing app preferences, and upgrading users retain
the original automatic policy. A permanently lost audio-focus request cancels speech
without restarting the previous player. If Android refuses audio focus (for example,
during a phone call), speech is not played over existing audio; acquisition is retried
a limited number of times instead. The app does not attempt to change the notification
sound settings of other applications.

## Manual regression test (Pixel 10 and other Android devices)

1. Start music, post one notification and confirm a pause, announcement, and resume.
2. Post several notifications rapidly while the system ding is audible. Confirm no
   speech overlaps the ding, all messages are read once, and music resumes once.
3. Post another notification while the previous message is being read, then one
   immediately after it ends. Confirm the original playing-music state survives both.
4. Start with music stopped. In Automatic mode, verify notifications do not start music.
5. Try Always resume and Never resume, with music initially playing and paused.
   Confirm each choice controls the *final* playback state.
6. Toggle announcements off in the middle of a long utterance; confirm stale TTS
   completion callbacks do not restart a new announcement or disrupt music.
7. Test with wired headphones, Bluetooth, and a phone call/navigation interruption
   when available; media apps differ in how they respond to audio focus and media keys.

Run `./gradlew testDebugUnitTest assembleDebug` to execute the queue/policy tests and
build a debug APK. A physical-device test is still needed because Android and third-party
media players control the actual media-key/focus behavior.
