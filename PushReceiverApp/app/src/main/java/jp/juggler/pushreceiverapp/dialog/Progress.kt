package jp.juggler.pushreceiverapp.dialog

import android.app.Dialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import jp.juggler.pushreceiverapp.databinding.DlgProgressBinding
import jp.juggler.util.AdbLog
import jp.juggler.util.AppDispatchers
import jp.juggler.util.dismissSafe
import jp.juggler.util.vg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch

class ProgressDialog(val activity: AppCompatActivity) {

    private val views = DlgProgressBinding.inflate(activity.layoutInflater)
    private val dialog = Dialog(activity)

    suspend fun <T : Any?> run(
        message: String,
        title: String,
        cancellable: Boolean,
        block: suspend (ProgressDialog.ProgressReporter) -> T?
    ): T? = ProgressReporter().use { reporter ->
        try {
            dialog.setContentView(views.root)
            reporter.setMessage(message)
            reporter.setTitle(title)
            dialog.setCancelable(cancellable)
            dialog.setCanceledOnTouchOutside(cancellable)
            dialog.show()
            block(reporter)
        } finally {
            dialog.dismissSafe()
        }
    }

    inner class ProgressReporter : AutoCloseable {
        private val channelMessage = Channel<Runnable>(capacity = Channel.CONFLATED)
        private val channelTitle = Channel<Runnable>(capacity = Channel.CONFLATED)

        suspend fun setMessage(msg: CharSequence) {
            try {
                channelMessage.send { views.tvMessage.vg(msg.isNotEmpty())?.text = msg }
            } catch (ex: Throwable) {
                AdbLog.w(ex)
            }
        }

        suspend fun setTitle(title: CharSequence) {
            try {
                channelMessage.send { views.tvTitle.vg(title.isNotEmpty())?.text = title }
            } catch (ex: Throwable) {
                AdbLog.w(ex)
            }
        }

        init {
            activity.lifecycleScope.launch(AppDispatchers.MainImmediate) {
                while (true) {
                    try {
                        channelMessage.receive().run()
                    } catch (ex: Throwable) {
                        when (ex) {
                            is CancellationException, is ClosedReceiveChannelException -> break
                            else -> AdbLog.w(ex, "error.")
                        }
                    }
                }
            }
            activity.lifecycleScope.launch(AppDispatchers.MainImmediate) {
                while (true) {
                    try {
                        channelTitle.receive().run()
                    } catch (ex: Throwable) {
                        when (ex) {
                            is CancellationException, is ClosedReceiveChannelException -> break
                            else -> AdbLog.w(ex, "error.")
                        }
                    }
                }
            }
        }

        override fun close() {
            channelTitle.close()
            channelMessage.close()
        }
    }
}

suspend fun <T : Any?> AppCompatActivity.runInProgress(
    message: String = "please wait…",
    title: String = "",
    cancellable: Boolean = true,
    block: suspend (ProgressDialog.ProgressReporter) -> T?
): T? = ProgressDialog(this).run(
    message = message,
    title = title,
    cancellable = cancellable,
    block = block
)
