package jp.juggler.pushreceiverapp.push

import android.content.Context
import jp.juggler.pushreceiverapp.alert.showAlertNotification
import jp.juggler.pushreceiverapp.alert.showError
import jp.juggler.pushreceiverapp.api.PushSubscriptionApi
import jp.juggler.pushreceiverapp.db.appDatabase
import jp.juggler.util.AppDispatchers
import jp.juggler.util.EmptyScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.unifiedpush.android.connector.MessagingReceiver

class UpMessageReceiver : MessagingReceiver() {

    // メインスレッドで呼ばれる
    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        runBlocking {
            try {
                PushRepo(
                    api = PushSubscriptionApi(OkHttpClient()),
                    accountAccess = context.appDatabase.accountAccess()
                ).handleUpMessage(context, instance, message)
            } catch (ex: Throwable) {
                context.showError(ex, "onNewEndpoint failed.")
            }
        }
    }

    // メインスレッドで呼ばれる
    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        context.showAlertNotification("onNewEndpoint: instance=$instance endpoint=$endpoint, thread=${Thread.currentThread().name}")
        EmptyScope.launch(AppDispatchers.DEFAULT) {
            try {
                PushRepo(
                    api = PushSubscriptionApi(OkHttpClient()),
                    accountAccess = context.appDatabase.accountAccess()
                ).newEndpoint(context, instance, endpoint)
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
