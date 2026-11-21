# Astra Player v1.1 — Android source project

## Target and features
- Android 16 target (API 36), min Android 8.0 (API 26)
- Gallery-style local video albums via MediaStore
- Separate per-album video lists
- Sort by name, date added, and file size (tap same sort again to reverse)
- Vertical swipe playback via Media3 ExoPlayer
- Repeat-current and auto-next/auto-scroll modes
- Source media passed directly to ExoPlayer; fit aspect ratio, no intentional transcode/upscale
- Movable floating overlay window using Android `SYSTEM_ALERT_WINDOW`
- Foreground media playback service while floating

## Build in Android Studio
1. Open this folder in Android Studio.
2. Install Android SDK Platform 36 and JDK 17.
3. Sync Gradle and allow dependencies to download.
4. Build > Build Bundle(s) / APK(s) > Build APK(s).

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

## Permissions and notes
- Android 13+ uses `READ_MEDIA_VIDEO`; older versions use `READ_EXTERNAL_STORAGE`.
- Floating window requires the user to grant "Display over other apps" in Android settings. The first Float tap opens that settings page; return to the app and tap Float again.
- Album grouping uses MediaStore `BUCKET_DISPLAY_NAME`; Android's provider determines which gallery folders are exposed.
- The floating service is a first implementation and requires real-device testing, especially on Android 16 vendor builds and battery/background restrictions.
- Original source means the app does not intentionally resize, interpolate, or re-encode media. Actual output resolution and frame cadence are limited by the phone's hardware decoder, codec support, and display refresh rate.
- This project has not been compiled or tested on a physical device in this environment. Treat it as a development build, not a verified production APK.
