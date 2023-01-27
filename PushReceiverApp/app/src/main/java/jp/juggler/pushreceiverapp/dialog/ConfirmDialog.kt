package jp.juggler.pushreceiverapp.dialog

import android.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import jp.juggler.util.dismissSafe
import jp.juggler.util.notBlank
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException

/**
 * ダイアログにメッセージを表示する。
 * - OKしたらUnitを返す。
 * - キャンセルまたは閉じたらCancellationExceptionを投げる
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun AppCompatActivity.confirm(message: String, title: String? = null) =
    suspendCancellableCoroutine { cont ->
        val dialog = AlertDialog.Builder(this).apply {
            setMessage(message)
            title?.notBlank()?.let { setTitle(it) }
            setPositiveButton(android.R.string.ok) { _, _ ->
                if (cont.isActive) cont.resume(Unit){}
            }
            setNegativeButton(android.R.string.cancel, null)
            setOnDismissListener {
                if (cont.isActive) cont.resumeWithException(CancellationException())
            }
        }.create()
        cont.invokeOnCancellation { dialog.dismissSafe() }
        dialog.show()
    }
