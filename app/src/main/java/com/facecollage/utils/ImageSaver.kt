package com.facecollage.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saves the rendered collage bitmap to disk in two places:
 *
 *   1. MediaStore (visible in the system gallery app)
 *      - API 29+: MediaStore RELATIVE_PATH in Pictures/FaceCollage
 *      - API 26-28: Environment.DIRECTORY_PICTURES / FaceCollage
 *
 *   2. App-external files dir (getExternalFilesDir / collages)
 *      Needed for FileProvider sharing — the gallery copy may not be
 *      addressable by a content URI on all devices.
 *
 * Returns the path in the app-external dir (the one safe for FileProvider).
 */
@Singleton
class ImageSaver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Saves [bitmap] as a JPEG named [filename].jpg.
     * Returns the absolute path of the app-external copy (used for sharing).
     */
    fun saveCollage(bitmap: Bitmap, filename: String): String {
        val jpegName = "$filename.jpg"
        // Always write the share-able copy first
        val sharePath = saveToAppExternalDir(bitmap, jpegName)
        // Also push to gallery
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(bitmap, jpegName)
        } else {
            saveToPublicExternalStorage(bitmap, jpegName)
        }
        return sharePath
    }

    // ─── MediaStore (API 29+) ────────────────────────────────────────────────

    private fun saveViaMediaStore(bitmap: Bitmap, filename: String) {
        val resolver = context.contentResolver
        val values   = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,   filename)
            put(MediaStore.Images.Media.MIME_TYPE,      "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/FaceCollage"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")

        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    // ─── Public external storage (API 26-28) ─────────────────────────────────

    private fun saveToPublicExternalStorage(bitmap: Bitmap, filename: String) {
        val dir  = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "FaceCollage"
        ).also { it.mkdirs() }
        FileOutputStream(File(dir, filename)).use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
        }
    }

    // ─── App-external dir (FileProvider) ────────────────────────────────────

    /**
     * Writes to [Context.getExternalFilesDir]/collages/.
     * This directory is covered by the `<external-files-path>` in file_paths.xml
     * so FileProvider can generate a content URI for it.
     */
    fun saveToAppExternalDir(bitmap: Bitmap, filename: String): String {
        val dir  = File(context.getExternalFilesDir(null), "collages").also { it.mkdirs() }
        val file = File(dir, filename)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return file.absolutePath
    }

    /**
     * Returns a FileProvider content URI for [path].
     * Pass this URI to Intent.EXTRA_STREAM for sharing.
     */
    fun getShareUri(path: String): Uri =
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            File(path)
        )
}