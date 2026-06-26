# NotificationAnnouncer

Android app that reads non-silent notifications aloud in the background using Text-to-Speech.

## Build APK

Developers should use GitHub Actions CI to build APKs. The workflow in `.github/workflows/android-ci.yml` runs unit tests, assembles `app-debug.apk`, and uploads the APK as a workflow artifact.
