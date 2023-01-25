package jp.juggler.pushreceiverapp.dialog

import android.app.AlertDialog
import android.content.Context
import jp.juggler.util.dismissSafe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException

class ActionsDialogInitializer {
    class Action(val caption: String, val action: suspend () -> Unit)

    val list = ArrayList<Action>()

    fun action(caption: String, action: suspend () -> Unit) {
        list.add(Action(caption, action))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun showSuspend(context: Context) = suspendCancellableCoroutine { cont ->
        val dialog = AlertDialog.Builder(context)
            .setItems(list.map { it.caption }.toTypedArray()) { d, i ->
                if (cont.isActive) cont.resume(list.elementAtOrNull(i)) {
                }
                d.dismissSafe()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener {
                if (cont.isActive) cont.resumeWithException(CancellationException())
            }
            .create()
        cont.invokeOnCancellation { dialog.dismissSafe() }
        dialog.show()
    }
}

suspend fun Context.actionsDialog(init: ActionsDialogInitializer.() -> Unit) {
    ActionsDialogInitializer()
        .apply { init() }
        .showSuspend(this)
        ?.action?.invoke()
}
