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
| Video processing | **FFmpegKit** (local AAR) — trim, compress, timestamp overlay, mute, fps |
| Encoding | **H.264 via `h264_mediacodec`** with software `mpeg4` fallback |
| Background service | **ForegroundService** with notification progress |
| Image loading | **Coil** |
| Storage | **MediaStore** (public directories) |
| Location | **Play Services Location** |

## Video Pipeline

Recordings are handed to a foreground service and transcoded in a single
decode → filter → encode pass:

- **Hardware first.** `h264_mediacodec` is used when the device exposes an
  H.264 encoder, falling back to software `mpeg4` if it fails. On the test
  device this took a 260 s clip from 544 s to ~86 s.
- **Timestamp overlay.** The stamp is pre-rendered as one PNG per second and
  composited with `overlay`. Using `drawtext` re-shapes the glyphs for every
  frame, which was the dominant cost of the old pipeline.
- **Durable queue.** Jobs are written to `files/video_jobs.json` before
  processing starts, so a crash or reboot doesn't lose a recording. Failed
  jobs retry once, then the source is moved to
  `Android/data/…/files/recovered/` rather than deleted.

> Note: `h264_mediacodec` needs `-pix_fmt nv12`. Asking for `yuv420p` fails
> with "MediaCodec configure failed" on OMX-based Qualcomm devices. Use the
> debug probe (below) to check a new device.

### Probing encoders on a device

Debug builds include a receiver that tries several encoder configurations and
logs which ones succeed:

```bash
adb shell am broadcast -a com.devin.ts_camera.DEBUG_FFMPEG \
    -n com.devin.ts_camera/.media.FfmpegProbeReceiver \
    --es input /data/user/0/com.devin.ts_camera/cache/VID_x.mp4
adb logcat -s FfmpegProbe
```

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
