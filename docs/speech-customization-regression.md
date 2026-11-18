# Speech customization regression checklist

The settings feature must not change notification selection, queue ordering, TTS callback IDs,
watchdog constants, retry handling, audio-focus behavior or music-pause logic.

## Defaults (upgrade and clean install)

- Speech speed 1.0x, pitch 1.0x, repetitions 1, minimum first-notification delay 0 seconds.
- At defaults, the queued speech text is byte-for-byte unchanged, and no new rate/pitch
  engine setter calls occur.
- Original 3s quiet window/6s cap, focus settle, completion grace, and resume modes remain.
- Verify the existing device-level checks in docs/music-pause-regression.md.

## Configuration

- Scroll the entire main screen from notification access through the application list on
  a small device; all controls remain reachable without being squeezed to the top.
- Change each slider; verify readable values, persistence after app restart, and that
  the selected rate/pitch takes effect on the next announcement without resetting the
  listener or TTS engine.
- Select 1–5 repetitions and test: music must stay paused during every repetition and
  resume once after the single TTS completion callback. Very long text intentionally
  repeats fewer times to remain below the TTS speech-input length limit.
- Select delays 0, 1, 2, 3, 5 and 10s. The minimum is counted from the first queued
  notification of a pending group, not reinitialized for each additional notification.
  With 0s the previous timing is unchanged. With 10s, speech should not start before
  10s after the first item, even though the ordinary quiet wait caps at 6s.
- Try changing settings while an announcement is speaking: the current utterance should
  finish without interruption, and changes should affect subsequent queued messages.
- Repeat the tests with media playing, no media playing, Bluetooth devices and overlapping
  notifications; verify the persistent Pause toggle, call deferral and TTS failure recovery.

Automated JVM tests cover repeating text without additional queue entries, default unchanged
behavior, bounded text length and optional minimum delay. Android device tests are needed to
prove compatibility with specific speech engines and music players.
