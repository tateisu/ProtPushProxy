package jp.juggler.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import jp.juggler.pushreceiverapp.fcm.FcmHandler
import jp.juggler.util.AdbLog
import jp.juggler.util.EmptyScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MyFcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        EmptyScope.launch {
            try {
                FcmHandler.saveFcmToken(this@MyFcmService, token)
                FcmHandler.fcmToken.emit(token)
            } catch (ex: Throwable) {
                AdbLog.e(ex, "fcmToken.emit failed.")
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        runBlocking {
            try {
                AdbLog.i("onMessageReceived from=${remoteMessage.from}")
                AdbLog.i("onMessageReceived: notification=${remoteMessage.notification?.body}")
                if (remoteMessage.data.isNotEmpty()) {
                    // Map<String,String>
                    AdbLog.i("onMessageReceived: payload=${remoteMessage.data}")
                }
                FcmHandler.onMessageReceived(remoteMessage)
            } catch (ex: Throwable) {
                AdbLog.e(ex, "onMessageReceived failed.")
            }
        }
    }
}
