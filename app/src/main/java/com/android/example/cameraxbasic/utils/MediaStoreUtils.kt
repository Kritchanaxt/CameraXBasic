package com.android.example.cameraxbasic.utils

import android.content.ContentUris
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import java.io.File


/**
 * A utility class for accessing this app's photo storage.
 *
 * Since this app doesn't request any external storage permissions, it will only be able to access
 * photos taken with this app. If the app is uninstalled, the photos taken with this app will stay
 * on the device, but reinstalling the app will not give it access to photos taken with the app's
 * previous instance. You can request further permissions to change this app's access. See this
 * guide for more: https://developer.android.com/training/data-storage/use-cases#app-specific-files-media
 */
object MediaStoreUtils {

    /**
     * [queryImages] returns a [Cursor] over the images in the MediaStore.Images.Media.EXTERNAL_CONTENT_URI
     * collection. The [projection] describes the columns that will be returned in the cursor.
     * The [selection] and [selectionArgs] restrict the rows that are returned.
     */
    private fun Context.queryImages(
        collection: Uri,
        projection: Array<String>,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        sortOrder: String? = "${MediaStore.Images.Media.DATE_ADDED} DESC"
    ): Cursor? {
        return contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )
    }

    /**
     * [getImages] returns a [Cursor] over the images in the
     * MediaStore.Images.Media.EXTERNAL_CONTENT_URI collection. The [projection] describes the
     * columns that will be returned in the cursor. The [selection] and [selectionArgs] restrict the
     * rows that are returned.
     */
    suspend fun getImages(context: Context, outputDirectory: File): MutableList<MediaStoreFile> = withContext(Dispatchers.IO) {
        val files = mutableListOf<MediaStoreFile>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY
            )
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA // DATA is deprecated but still commonly used
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val imageDataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val imageIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(imageIdColumn)
                val filePath = cursor.getString(imageDataColumn)
                val contentUri: Uri = ContentUris.withAppendedId(collection, id)

                val file = File(filePath)
                // Filter to ensure the image is in our app's specific output directory
                if (file.parentFile == outputDirectory) {
                    files.add(MediaStoreFile(contentUri, file, id))
                }
            }
        }
        return@withContext files
    }

    suspend fun addImageToMediaStore(
        contentResolver: ContentResolver,
        displayName: String,
        timestamp: Long,
        directory: File,
        uri: Uri,
        mimeType: String
    ): MediaStoreFile? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.DATE_ADDED, timestamp / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.DATE_EXPIRES, (timestamp + 3600000) / 1000) // Expires in 1 hour
                put(MediaStore.MediaColumns.RELATIVE_PATH, directory.name)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                put(MediaStore.MediaColumns.DATA, File(directory, displayName).absolutePath)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val imageUri: Uri? = contentResolver.insert(collection, values)

        imageUri?.let {
            try {
                // If on Android Q or higher, write to the pending URI
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.openOutputStream(it)?.use { outputStream -> // <-- แก้ไขตรงนี้: เพิ่ม ?. ก่อน use
                        val input = contentResolver.openInputStream(uri)
                        input?.copyTo(outputStream)
                        input?.close()
                    } ?: run {
                        Log.e("MediaStoreUtils", "Failed to open output stream for imageUri: $it")
                        return@withContext null // Return null if outputStream is null
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0) // Clear pending flag
                    contentResolver.update(it, values, null, null)
                }

                val id = ContentUris.parseId(it)
                val file = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // For Android Q and above, the file path might not be directly accessible via DATA.
                    // We'll rely on the URI. If a File object is strictly needed, you might need
                    // to query MediaStore again or use a different approach.
                    // For simplicity, we'll create a dummy file object pointing to the expected path.
                    File(directory, displayName)
                } else {
                    File(directory, displayName)
                }
                return@withContext MediaStoreFile(it, file, id)
            } catch (e: Exception) {
                Log.e("MediaStoreUtils", "Failed to add image to MediaStore: ${e.message}", e)
                // Delete the pending entry if an error occurs
                imageUri?.let { uriToDelete ->
                    contentResolver.delete(uriToDelete, null, null)
                }
                return@withContext null
            }
        } ?: run {
            Log.e("MediaStoreUtils", "Failed to insert new MediaStore record.")
            return@withContext null
        }
    }
}

@Parcelize
data class MediaStoreFile(val uri: Uri, val file: File, val id: Long) : Parcelable