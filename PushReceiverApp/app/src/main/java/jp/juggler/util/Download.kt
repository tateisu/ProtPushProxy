package jp.juggler.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream

/**
 * 端末のダウンロードフォルダにデータを保存する
 */
suspend fun Context.saveToDownload(
    displayName: String,
    writer: suspend (OutputStream) -> Unit,
) {
    val folderUri = if (Build.VERSION.SDK_INT >= 29) {
        MediaStore.Downloads.getContentUri("external")
    } else {
        MediaStore.Files.getContentUri("external")
    }

    val uri = contentResolver.insert(
        folderUri,
        ContentValues().apply {
            put(
                MediaStore.MediaColumns.DISPLAY_NAME,
                displayName
            )
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS
                )
            }
        }
    ) ?: error("contentResolver.insert returns null.")

    (contentResolver.openOutputStream(uri)
        ?: error("contentResolver.openOutputStream returns null")
            ).use { writer(it) }

    if (Build.VERSION.SDK_INT >= 29) {
        contentResolver.update(
            uri,
            ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            },
            null,
            null
        )
    }
}
