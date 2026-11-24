# Bluetooth automatic control regression checklist

Run the existing Android CI build and unit tests. On a physical device (especially Pixel 10),
use non-sensitive test notifications:

1. Upgrade with announcements manually enabled, then repeat with them manually paused.
   Confirm automatic control defaults off and the original notification actions work.
2. Enable automatic mode without Bluetooth audio. Verify paused state; post notifications
   and press Test announcement: nothing speaks. Connect a keyboard/watch only; still paused.
3. Connect classic A2DP headphones, then repeat with LE Audio if available. Verify
   automatic enabling, including when headphones were already connected on app/listener startup.
4. Play music, post multiple notifications and confirm the original ordering, quiet window,
   pause/hold/resume behavior, repetitions and delay settings.
5. Disconnect during the quiet window, during focus settling, mid-utterance and with queued
   messages. Verify speech stops, no queued messages play on the phone, stale callbacks
   do not restart speech, and existing manual-cancel music restoration policy applies.
6. Reconnect; verify old notifications are not replayed and new ones are announced.
   Repeat with screen locked, activity closed, and after listener rebind/process recreation.
7. Turn Bluetooth off entirely; repeat with multiple outputs. Removing a Bluetooth media
   output conservatively cancels an ongoing burst. On Android 13+, select the phone
   speaker while Bluetooth stays connected; verify automatic pause (route polling up to
   roughly one second when awake) and return to Bluetooth. On older Android, connection
   alone cannot confirm actual routing. Bluetooth speakers/car audio may qualify.
8. Turn automatic mode off while disconnected and while connected. Confirm the saved
   manual state returns, including manual pause/resume without headphones and the
   original music resume modes. Try a stale notification action in automatic mode:
   it must not override the Bluetooth gate.
9. Repeat the existing music-pause regression checklist. No queue, audio-focus,
   music transport, delay constants or TTS recovery code should change.

The Android audio routing API predicts routing, not the actual third-party TTS
engine output. Disconnect callbacks and TTS cancellation are asynchronous; check
for any audible leakage on each target device. This is not a hard privacy guarantee.
