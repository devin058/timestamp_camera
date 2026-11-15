package com.devin.ts_camera.media

import com.devin.ts_camera.media.PassThrough.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Choosing the cheapest mode matters: a full encode of a long take costs about
 * realtime, while a remux is IO-bound. Muting is the default setting, so
 * treating it as "must re-encode" used to make every recording pay for an
 * encode it did not need.
 */
class PassThroughTest {

    private fun mode(
        trimmed: Boolean = false,
        fps: Int = 0,
        sourceW: Int = 1080,
        sourceH: Int = 1920,
        outW: Int = 1080,
        outH: Int = 1920
    ) = PassThrough.mode(trimmed, fps, sourceW, sourceH, outW, outH)

    @Test
    fun `untouched recording at the requested size only needs a copy`() {
        assertEquals(Mode.REMUX, mode())
    }

    @Test
    fun `trim still requires a real encode`() {
        assertEquals(Mode.ENCODE, mode(trimmed = true))
    }

    @Test
    fun `frame rate override still requires a real encode`() {
        assertEquals(Mode.ENCODE, mode(fps = 30))
    }

    @Test
    fun `resolution change still requires a real encode`() {
        assertEquals(Mode.ENCODE, mode(outW = 720, outH = 1280))
    }

    @Test
    fun `orientation change still requires a real encode`() {
        assertEquals(Mode.ENCODE, mode(sourceW = 1920, sourceH = 1080))
    }

    @Test
    fun `muting does not force an encode when pixels already match`() {
        // The whole point: dropping audio is a container operation.
        assertEquals(Mode.REMUX, mode())
    }
}
