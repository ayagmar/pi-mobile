package com.ayagmar.pimobile.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.ayagmar.pimobile.corerpc.ImagePayload

/**
 * Encodes images from URIs to base64 ImagePayload for RPC transmission.
 */
class ImageEncoder(private val context: Context) {
    fun encodeToPayload(pendingImage: PendingImage): ImagePayload? {
        return try {
            val uri = Uri.parse(pendingImage.uri)
            val bytes =
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return null

            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            ImagePayload(
                data = base64,
                mimeType = pendingImage.mimeType,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun getImageInfo(uri: Uri): PendingImage? {
        return try {
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            val (sizeBytes, displayName) = queryFileMetadata(uri)

            PendingImage(
                uri = uri.toString(),
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                displayName = displayName,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun queryFileMetadata(uri: Uri): Pair<Long, String?> {
        var sizeBytes = 0L
        var displayName: String? = null

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (sizeIndex >= 0) sizeBytes = cursor.getLong(sizeIndex)
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
            }
        }

        return Pair(sizeBytes, displayName)
    }

    companion object {
        const val MAX_IMAGE_SIZE_BYTES = 5L * 1024 * 1024 // 5MB
    }
}
