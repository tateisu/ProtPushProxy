package jp.juggler.pushreceiverapp.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import jp.juggler.pushreceiverapp.push.pushRepo
import jp.juggler.util.AdbLog
import kotlinx.coroutines.runBlocking

class NotificationDeleteReceiver : BroadcastReceiver() {
    companion object {
        fun Context.intentNotificationDelete(dataUri: Uri) =
            Intent(this, NotificationDeleteReceiver::class.java).apply {
                data = dataUri
            }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        runBlocking {
            try {
                val uri = intent?.data?.toString()
                AdbLog.i("onReceive uri=$uri")
                when {
                    uri == null ->
                        Unit

                    uri.startsWith(NotificationChannels.Alert.uriPrefixDelete) ->
                        Unit

                    uri.startsWith(NotificationChannels.PushMessage.uriPrefixDelete) ->
                        context.pushRepo.onDeleteNotification(uri)
                }
            } catch (ex: Throwable) {
                AdbLog.e(ex, "onReceive failed.")
            }
        }
    }
}
