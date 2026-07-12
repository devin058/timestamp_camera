# TS Camera

Timestamp camera app for Android — capture photos and videos with real-time
date/time watermark burned into every frame.

## Features

- **Photo capture** with timestamp watermark (single-line, bottom-center)
- **Video recording** with dynamic per-frame timestamp via FFmpeg `drawtext`
- **Background processing** — video transcodes automatically after recording via foreground service
- **Notification progress bar** — shows real-time processing progress
- **Settings panel** — pre-configure resolution, compression, mute, frame rate (gear icon, bottom-left)
- Real-time clock overlay on the camera viewfinder (top-left, updates every second)
- **GPS location** tagging (EXIF for photos, JSON sidecar for videos)
- **Mute audio** — optionally remove audio track from saved video
- **Frame rate** — output at original / 24 / 30 / 60 fps
- **Flash control** (off / on / auto)
- Front / back camera toggle
- **Swipe gestures**: left/right to switch photo/video mode, up/down to flip camera
- **Power-saving dim** — after 2 min of recording, screen dims with "recording… tap to wake"
- **Splash screen** — shows app icon, description, and author info on launch

## Tech Stack

| Component | Library |
|---|---|
| UI | **Kotlin** + Jetpack Compose (Material 3) |
| Camera | **CameraX** |
| Video processing | **FFmpegKit** (local AAR) — trim, compress, dynamic timestamp, mute, fps |
| Background service | **ForegroundService** with notification progress |
| Image loading | **Coil** |
| Storage | **MediaStore** (public directories) |
| Location | **Play Services Location** |

## Minimum SDK

Android 7.0 (API 24)

## Build

1. Open in Android Studio, sync Gradle.
2. Place `ffmpeg-kit-full-6.0-2.LTS.aar` and `smartexception-*.jar` files in `app/libs/`.
3. Build & run.

```
./gradlew assembleDebug
```

## Save Location

- Photos → `Pictures/` (visible in gallery)
- Videos → `Movies/` (visible in gallery)
- Video metadata sidecar → app-private `files/metadata/`

## Permissions

| Permission | Purpose |
|---|---|
| `CAMERA` | Photo / video capture |
| `RECORD_AUDIO` | Video audio track |
| `ACCESS_FINE_LOCATION` | GPS coordinates in EXIF |
| `ACCESS_MEDIA_LOCATION` (API 33+) | Media location access |
| `POST_NOTIFICATIONS` (API 33+) | Processing progress notification |

## Author

**devin shaw** — [github.com/devin058/timestamp_camera](https://github.com/devin058/timestamp_camera)
