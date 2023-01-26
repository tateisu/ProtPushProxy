package jp.juggler.pushreceiverapp.push

import android.content.Context
import jp.juggler.pushreceiverapp.alert.showAlertNotification
import jp.juggler.pushreceiverapp.alert.showError
import jp.juggler.util.EmptyScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.unifiedpush.android.connector.MessagingReceiver

class UpMessageReceiver : MessagingReceiver() {

    // メインスレッドで呼ばれる
    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        EmptyScope.launch  {
            try {
                context.pushRepo.handleUpMessage(context, message)
            } catch (ex: Throwable) {
                context.showError(ex, "onMessage failed.")
            }
        }
    }

    // メインスレッドで呼ばれる
    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        context.showAlertNotification("onNewEndpoint: instance=$instance endpoint=$endpoint, thread=${Thread.currentThread().name}")
        EmptyScope.launch {
            try {
                context.pushRepo.newEndpoint(context, endpoint)
            } catch (ex: Throwable) {
                context.showError(ex, "onNewEndpoint failed.")
            }
        }
    }

    override fun onRegistrationFailed(context: Context, instance: String) {
        context.showAlertNotification("onRegistrationFailed: instance=$instance, thread=${Thread.currentThread().name}")
    }

    override fun onUnregistered(context: Context, instance: String) {
        context.showAlertNotification("onUnregistered: instance=$instance, thread=${Thread.currentThread().name}")
    }
}
