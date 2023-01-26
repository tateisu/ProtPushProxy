package jp.juggler.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import jp.juggler.pushreceiverapp.push.fcmHandler
import jp.juggler.util.AdbLog
import kotlinx.coroutines.runBlocking

class MyFcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        val context = this
        try {
            runBlocking {
                context.fcmHandler.onTokenChanged(context, token)
            }
        } catch (ex: Throwable) {
            AdbLog.e(ex, "onNewToken failed.")
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val context = this
        try {
            runBlocking {
                AdbLog.i("onMessageReceived from=${remoteMessage.from}")
                AdbLog.i("onMessageReceived: notification=${remoteMessage.notification?.body}")
                val a = remoteMessage.data["a"]
                    ?: error("missing remoteMessage.data.a")
                context.fcmHandler.onMessageReceived(context, a)
            }
        } catch (ex: Throwable) {
            AdbLog.e(ex, "onMessageReceived failed.")
        }
    }
}
