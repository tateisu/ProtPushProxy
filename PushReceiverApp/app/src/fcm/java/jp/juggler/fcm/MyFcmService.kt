package jp.juggler.fcm

import android.app.ActivityManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import jp.juggler.pushreceiverapp.push.fcmHandler
import jp.juggler.util.AdbLog
import jp.juggler.util.checkAppForeground
import kotlinx.coroutines.runBlocking

class MyFcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        checkAppForeground()
        try {
            val context = this
            runBlocking {
                context.fcmHandler.onTokenChanged(context, token)
            }
        } catch (ex: Throwable) {
            AdbLog.e(ex, "onNewToken failed.")
        }
        checkAppForeground()
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        checkAppForeground()
        try {
            val context = this
            runBlocking {
                context.fcmHandler.onMessageReceived(context, remoteMessage.data)
            }
        } catch (ex: Throwable) {
            AdbLog.e(ex, "onMessageReceived failed.")
        }
        checkAppForeground()
    }
}
