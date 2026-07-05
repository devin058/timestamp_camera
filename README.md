# TS Camera

Timestamp camera app for Android — capture photos and videos with real-time
date/time watermark burned into every frame.

## Features

- **Photo capture** with timestamp watermark burned into the image
- **Video recording** with timestamp overlay burned into every frame via Media3 Transformer
- Real-time clock overlay on the camera viewfinder during recording
- **GPS location** tagging (EXIF for photos, JSON sidecar for videos)
- **Video trimming** — select start/end points before saving
- **Resolution & compression** controls (480p / 720p / 1080p / 4K, low/medium/high)
- **Flash control** (off / on / auto)
- Front / back camera toggle

## Tech Stack

- **Kotlin** + Jetpack Compose (Material 3)
- **CameraX** for capture
- **Media3 Transformer** for video post-processing (trim, compress, overlay)
- **Coil** for image loading
- **MediaStore** for saving to public directories

## Minimum SDK

Android 7.0 (API 24)

## Build

Open in Android Studio, sync Gradle, and run on a device or emulator.

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
