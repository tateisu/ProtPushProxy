package jp.juggler.pushreceiverapp.dialog

import android.app.AlertDialog
import android.content.Context
import jp.juggler.util.dismissSafe
import jp.juggler.util.notEmpty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException

class ActionsDialogInitializer(
    val title: String? = null
) {
    class Action(val caption: String, val action: suspend () -> Unit)

    val list = ArrayList<Action>()

    fun action(caption: String, action: suspend () -> Unit) {
        list.add(Action(caption, action))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun showSuspend(context: Context): Action =
        suspendCancellableCoroutine { cont ->
            val dialog = AlertDialog.Builder(context).apply {
                title?.notEmpty()?.let { setTitle(it) }
                setNegativeButton(android.R.string.cancel, null)
                setItems(list.map { it.caption }.toTypedArray()) { d, i ->
                    if (cont.isActive) cont.resume(list[i]) {}
                    d.dismissSafe()
                }
                setOnDismissListener {
                    if (cont.isActive) cont.resumeWithException(CancellationException())
                }
            }.create()
            cont.invokeOnCancellation { dialog.dismissSafe() }
            dialog.show()
        }
}

suspend fun Context.actionsDialog(
    title: String? = null,
    init: ActionsDialogInitializer.() -> Unit
) {
    ActionsDialogInitializer(title)
        .apply { init() }
        .showSuspend(this)
        .action.invoke()
}
