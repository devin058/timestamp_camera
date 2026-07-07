# TS Camera

Timestamp camera app for Android — capture photos and videos with real-time
date/time watermark burned into every frame.

## Features

- **Photo capture** with timestamp watermark (single-line, bottom-center)
- **Video recording** with dynamic per-frame timestamp via FFmpeg `drawtext`
- Real-time clock overlay on the camera viewfinder (top-left, updates every second)
- **GPS location** tagging (EXIF for photos, JSON sidecar for videos)
- **Video trimming** — select start/end points before saving
- **Resolution & compression** controls (480p / 720p / 1080p / 4K, low/medium/high)
- **Mute audio** — optionally remove audio track from saved video
- **Frame rate** — output at original / 24 / 30 / 60 fps
- **Flash control** (off / on / auto)
- Front / back camera toggle
- **Swipe gestures**: left/right to switch photo/video mode, up/down to flip camera
- **Power-saving dim** — after 2 min of recording, screen dims with "recording… tap to wake"

## Tech Stack

| Component | Library |
|---|---|
| UI | **Kotlin** + Jetpack Compose (Material 3) |
| Camera | **CameraX** |
| Video post-processing | **FFmpegKit** (local AAR) — trim, compress, dynamic timestamp, mute, fps |
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
