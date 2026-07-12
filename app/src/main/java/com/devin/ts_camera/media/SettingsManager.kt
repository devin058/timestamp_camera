package com.devin.ts_camera.media

import android.content.Context
import com.devin.ts_camera.model.CompressionLevel
import com.devin.ts_camera.model.VideoResolution

/**
 * Persists video processing settings via SharedPreferences.
 */
object SettingsManager {

    private const val PREFS = "video_settings"
    private const val KEY_RESOLUTION = "resolution"
    private const val KEY_COMPRESSION = "compression"
    private const val KEY_MUTE = "mute"
    private const val KEY_FPS = "fps"
    fun getResolution(ctx: Context): VideoResolution {
        val name = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RESOLUTION, null)
        return VideoResolution.entries.find { it.name == name } ?: VideoResolution.R1080P
    }

    fun setResolution(ctx: Context, res: VideoResolution) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RESOLUTION, res.name).apply()
    }

    fun getCompression(ctx: Context): CompressionLevel {
        val name = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COMPRESSION, null)
        return CompressionLevel.entries.find { it.name == name } ?: CompressionLevel.LOW
    }

    fun setCompression(ctx: Context, c: CompressionLevel) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_COMPRESSION, c.name).apply()
    }

    fun isMuted(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MUTE, false)

    fun setMuted(ctx: Context, mute: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MUTE, mute).apply()
    }

    fun getFps(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_FPS, 0)

    fun setFps(ctx: Context, fps: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_FPS, fps).apply()
    }

}
