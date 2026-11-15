package com.devin.ts_camera.media

import com.devin.ts_camera.model.CompressionLevel
import com.devin.ts_camera.model.VideoResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The command line is the whole transcode pipeline, and a wrong flag silently
 * costs minutes or loses the timestamp. These pin down the parts that broke
 * on real recordings.
 */
class TranscodeCommandTest {

    private fun spec(
        overlay: File? = null,
        startMs: Long = 0L,
        endMs: Long = 30_000L,
        width: Int = 720,
        height: Int = 480,
        compression: CompressionLevel = CompressionLevel.HIGH,
        muteAudio: Boolean = true,
        fps: Int = 24,
        hardware: Boolean = true
    ) = TranscodeCommand.Spec(
        inputPath = "/tmp/in.mp4",
        outputPath = "/tmp/out.mp4",
        overlay = overlay,
        startMs = startMs,
        endMs = endMs,
        width = width,
        height = height,
        compression = compression,
        muteAudio = muteAudio,
        fps = fps,
        hardwareEncoder = hardware
    )

    @Test
    fun `hardware encode uses nv12 because mediacodec rejects yuv420p`() {
        val cmd = TranscodeCommand.build(spec(hardware = true))

        assertTrue(cmd.contains("-c:v h264_mediacodec"))
        assertTrue(cmd.contains("format=nv12"))
        assertTrue(cmd.contains("-pix_fmt nv12"))
        // yuv420p at configure time is the exact failure seen on device.
        assertFalse(cmd.contains("format=yuv420p"))
    }

    @Test
    fun `software encode uses yuv420p`() {
        val cmd = TranscodeCommand.build(spec(hardware = false))

        assertTrue(cmd.contains("-c:v mpeg4"))
        assertTrue(cmd.contains("format=yuv420p"))
    }

    @Test
    fun `overlay chain keeps the stamp from ending the video early`() {
        val cmd = TranscodeCommand.build(spec(overlay = File("/tmp/stamps")))

        assertTrue(cmd.contains("eof_action=repeat"))
        // shortest=1 truncated output whenever the 1 fps stamps ran out.
        assertFalse(cmd.contains("shortest=1"))
        assertTrue(cmd.contains("-framerate 1"))
        assertTrue(cmd.contains("stamp_%06d.png"))
    }

    @Test
    fun `overlay is composited bottom centre`() {
        val cmd = TranscodeCommand.build(
            spec(overlay = File("/tmp/stamps"), width = 720, height = 480)
        )

        // Margin is 4% of the short edge (480) -> 19px.
        assertTrue(cmd.contains("y=H-h-19"))
        assertTrue(cmd.contains("x=(W-w)/2"))
    }

    @Test
    fun `portrait footage keeps portrait output dimensions`() {
        // 720x480 preset applied to a portrait source must become 480x720,
        // otherwise the video is letterboxed into a landscape frame.
        val cmd = TranscodeCommand.build(spec(width = 480, height = 720))

        assertTrue(cmd.contains("scale=480:720"))
        assertTrue(cmd.contains("pad=480:720"))
        assertFalse(cmd.contains("scale=720:480"))
    }

    @Test
    fun `output length is pinned to the trim range`() {
        val cmd = TranscodeCommand.build(spec(startMs = 5_000L, endMs = 35_000L))

        assertTrue(cmd.contains("-ss 5.000"))
        assertTrue(cmd.contains("-t 30.000"))
    }

    @Test
    fun `trim start is applied as an input seek`() {
        val cmd = TranscodeCommand.build(spec(startMs = 2_500L))

        val ssIndex = cmd.indexOf("-ss ")
        val inputIndex = cmd.indexOf("-i \"")
        assertTrue("seek should precede the input", ssIndex in 0 until inputIndex)
    }

    @Test
    fun `muted output drops audio entirely`() {
        val cmd = TranscodeCommand.build(spec(muteAudio = true))

        assertTrue(cmd.contains("-an"))
        assertFalse(cmd.contains("-c:a aac"))
        assertFalse(cmd.contains("-map 0:a"))
    }

    @Test
    fun `audio is kept and remapped when not muted`() {
        val cmd = TranscodeCommand.build(
            spec(muteAudio = false, overlay = File("/tmp/stamps"))
        )

        assertTrue(cmd.contains("-map 0:a?"))
        assertTrue(cmd.contains("-c:a aac -b:a 128k"))
        assertFalse(cmd.contains("-an"))
    }

    @Test
    fun `frame rate filter is only added when requested`() {
        assertTrue(TranscodeCommand.build(spec(fps = 30)).contains("fps=30"))
        assertFalse(TranscodeCommand.build(spec(fps = 0)).contains("fps="))
    }

    @Test
    fun `every command ends with an mp4 output path`() {
        val cmd = TranscodeCommand.build(spec())
        assertTrue(cmd.trimEnd().endsWith("\"/tmp/out.mp4\""))
        assertTrue(cmd.contains("-f mp4"))
    }

    @Test
    fun `bitrate follows the compression level`() {
        assertTrue(
            TranscodeCommand.build(spec(compression = CompressionLevel.HIGH))
                .contains("-b:v 2M")
        )
        assertTrue(
            TranscodeCommand.build(spec(compression = CompressionLevel.LOW))
                .contains("-b:v 10M")
        )
    }
}
