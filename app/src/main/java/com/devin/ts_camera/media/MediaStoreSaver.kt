package com.devin.ts_camera.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Saves photo and video files to public directories via MediaStore
 * so they appear in the device gallery and file manager.
 *
 * - Android 10+ (API 29): uses scoped storage with IS_PENDING for atomic writes
 * - Android 9-  (API 24-28): writes to external storage + MediaScanner scan
 */
object MediaStoreSaver {

    private const val PHOTO_RELATIVE_PATH = "Pictures"
    private const val VIDEO_RELATIVE_PATH = "Movies"

    // ---- Public save paths (for Toast / UI) -------------------------------

    fun getPhotoSaveLocationLabel(): String = PHOTO_RELATIVE_PATH
    fun getVideoSaveLocationLabel(): String = VIDEO_RELATIVE_PATH

    // ---- Public API --------------------------------------------------------

    /**
     * Save a JPEG photo to [PHOTO_RELATIVE_PATH].  EXIF metadata should
     * already be written into [sourceFile] before calling this method.
     */
    suspend fun savePhoto(
        context: Context,
        sourceFile: File,
        displayName: String  // without extension, e.g. "IMG_20260706_120001_1"
    ): Uri = withContext(Dispatchers.IO) {
        insertToMediaStore(
            context = context,
            sourceFile = sourceFile,
            displayName = "$displayName.jpg",
            mimeType = "image/jpeg",
            relativePath = PHOTO_RELATIVE_PATH,
            externalPublicDir = Environment.DIRECTORY_PICTURES,
            mediaCollectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        )
    }

    /**
     * Save an MP4 video to [VIDEO_RELATIVE_PATH].
     */
    suspend fun saveVideo(
        context: Context,
        sourceFile: File,
        displayName: String  // without extension, e.g. "VID_20260706_120001_1"
    ): Uri = withContext(Dispatchers.IO) {
        insertToMediaStore(
            context = context,
            sourceFile = sourceFile,
            displayName = "$displayName.mp4",
            mimeType = "video/mp4",
            relativePath = VIDEO_RELATIVE_PATH,
            externalPublicDir = Environment.DIRECTORY_MOVIES,
            mediaCollectionUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
    }

    // ---- Private implementation --------------------------------------------

    @Throws(IOException::class)
    private fun insertToMediaStore(
        context: Context,
        sourceFile: File,
        displayName: String,
        mimeType: String,
        relativePath: String,
        externalPublicDir: String,
        mediaCollectionUri: Uri
    ): Uri {
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // ---- Android 10+ : scoped storage via MediaStore --------------
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = resolver.insert(mediaCollectionUri, values)
                ?: throw IOException("MediaStore insert returned null for $displayName")

            try {
                resolver.openOutputStream(uri)?.use { output ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                } ?: throw IOException("Failed to open OutputStream for $uri")

                // Mark as complete → visible in gallery
                val updateValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, updateValues, null, null)

                return uri
            } catch (e: Exception) {
                // Clean up the pending row on failure
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            // ---- Android 9 and below : direct external storage ------------
            val publicDir = Environment.getExternalStoragePublicDirectory(externalPublicDir)
            if (!publicDir.exists()) {
                publicDir.mkdirs()
            }
            val destFile = File(publicDir, displayName)
            sourceFile.inputStream().use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // Register with MediaStore so it shows in gallery
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATA, destFile.absolutePath)
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            }
            val uri = resolver.insert(mediaCollectionUri, values)

            // Trigger media scanner so gallery picks it up
            MediaScannerConnection.scanFile(
                context,
                arrayOf(destFile.absolutePath),
                arrayOf(mimeType),
                null
            )

            return uri ?: Uri.fromFile(destFile)
        }
    }
}
