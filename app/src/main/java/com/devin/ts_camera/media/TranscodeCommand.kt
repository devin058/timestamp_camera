package com.devin.ts_camera.media

import com.devin.ts_camera.model.CompressionLevel
import com.devin.ts_camera.model.VideoResolution
import java.io.File

/**
 * Builds the FFmpeg command line for a single transcode pass.
 *
 * Kept separate from [VideoProcessor] so the argument ordering — the part that
 * is easy to get subtly wrong and hard to verify by eye — can be unit tested
 * without a device.
 */
internal object TranscodeCommand {

    /** Everything needed to build one command line. */
    data class Spec(
        val inputPath: String,
        val outputPath: String,
        /** Directory of `stamp_%06d.png` frames, or null to skip the stamp. */
        val overlay: File?,
        val startMs: Long,
        val endMs: Long,
        /** Output dimensions, already oriented to match the source. */
        val width: Int,
        val height: Int,
        val compression: CompressionLevel,
        val muteAudio: Boolean,
        val fps: Int,
        val hardwareEncoder: Boolean
    ) {
        val durationMs: Long get() = endMs - startMs
    }

    fun build(spec: Spec): String = buildString {
        append("-y ")

        // Seeking before the input is far cheaper than after it.
        if (spec.startMs > 0L) {
            append("-ss ${fmtSec(spec.startMs)} ")
        }
        append("-i \"${spec.inputPath}\" ")

        // One PNG per second; `-framerate 1` gives each strip a 1s lifetime.
        val stampPattern = spec.overlay?.let {
            File(it, "stamp_%06d.png").absolutePath
        }
        stampPattern?.let { append("-framerate 1 -i \"$it\" ") }

        val w = spec.width
        val h = spec.height
        val chain = buildList {
            add("scale=$w:$h:force_original_aspect_ratio=decrease")
            add("pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=black")
            add("setsar=1")
            if (spec.fps > 0) {
                add("fps=${spec.fps}")
            }
            // Must match the encoder: MediaCodec on OMX devices only accepts
            // nv12, and asking for yuv420p makes `configure` fail outright.
            add("format=${pixelFormat(spec.hardwareEncoder)}")
        }

        if (stampPattern != null) {
            val margin = (minOf(w, h) * 0.04f).toInt().coerceAtLeast(8)
            append("-filter_complex ")
            // eof_action=repeat holds the last stamp until `-t` ends the
            // output; shortest=1 would cut the video when the 1 fps stamp
            // sequence runs out a frame early.
            append("\"[0:v]${chain.joinToString(",")}[base];")
            append("[base][1:v]overlay=x=(W-w)/2:y=H-h-$margin:eof_action=repeat[vout]\" ")
            append("-map \"[vout]\" ")
            if (!spec.muteAudio) {
                // The `?` keeps this valid for sources that have no audio.
                append("-map 0:a? ")
            }
        } else {
            append("-vf \"${chain.joinToString(",")}\" ")
        }

        append(
            if (spec.hardwareEncoder) h264Args(spec.compression)
            else mpeg4Args(spec.compression)
        )

        if (spec.muteAudio) {
            append("-an ")
        } else {
            append("-c:a aac -b:a 128k ")
        }

        // Exact length: the 1 fps stamp sequence and the video stream don't
        // end at precisely the same instant.
        append("-t ${fmtSec(spec.durationMs)} ")
        append("-f mp4 ")
        append("\"${spec.outputPath}\"")
    }

    /**
     * Pixel format the chosen encoder accepts.
     *
     * `h264_mediacodec` rejects `yuv420p` at configure time ("MediaCodec
     * configure failed") on OMX-based Qualcomm devices; `nv12` works. Software
     * `mpeg4` wants planar `yuv420p`.
     */
    fun pixelFormat(hardware: Boolean): String = if (hardware) "nv12" else "yuv420p"

    fun h264Args(compression: CompressionLevel): String {
        val bitrate = when (compression) {
            CompressionLevel.HIGH -> "2M"
            CompressionLevel.MEDIUM -> "5M"
            CompressionLevel.LOW -> "10M"
        }
        // bf=0 keeps MediaCodec happy; g is a 1-second GOP at 30 fps.
        return "-c:v h264_mediacodec -b:v $bitrate -g 30 -bf 0 -pix_fmt nv12 "
    }

    fun mpeg4Args(compression: CompressionLevel): String {
        val q = when (compression) {
            CompressionLevel.HIGH -> 10
            CompressionLevel.MEDIUM -> 7
            CompressionLevel.LOW -> 5
        }
        return "-c:v mpeg4 -q:v $q "
    }

    fun fmtSec(ms: Long) = "%.3f".format(ms / 1000.0)
}
