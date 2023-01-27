package jp.juggler.pushreceiverapp.notification

import android.app.NotificationChannel
import android.content.Context
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.startup.Initializer
import jp.juggler.pushreceiverapp.R
import jp.juggler.util.*

const val notificationIdAlert = 1
const val notificationIdSns = 2

const val piRequestCodeAlertTap = 0
const val piRequestCodeAlertDelete = 1
const val piRequestCodeSnsTap = 2
const val piRequestCodeSnsDelete = 3

enum class NotificationChannels(
    val id: String,
    @StringRes val titleId: Int,
    @StringRes val descId: Int,
    val importance: Int,
    val priority: Int,
    // 通知ID。(ID+tagでユニーク)
    val notificationId: Int,
    // PendingIntentのrequestCode。(ID+intentのdata Uriでユニーク)
    // pending intent request code for tap
    val pircTap: Int,
    // pending intent request code for delete
    val pircDelete: Int,
    // 通知削除のUri prefix
    val uriPrefixDelete: String,
) {
    Alert(
        id = "Alert",
        titleId = R.string.alert,
        descId = R.string.alert_notification_desc,
        importance = NotificationManagerCompat.IMPORTANCE_HIGH,
        priority = NotificationCompat.PRIORITY_HIGH,
        notificationId = 1,
        pircTap = 0,
        pircDelete = 1,
        uriPrefixDelete = "pushreceiverapp://alert",
    ),
    PushMessage(
        id = "PushMessage",
        titleId = R.string.push_message,
        descId = R.string.push_message_desc,
        importance = NotificationManagerCompat.IMPORTANCE_HIGH,
        priority = NotificationCompat.PRIORITY_HIGH,
        notificationId = 2,
        pircTap = 2,
        pircDelete = 3,
        uriPrefixDelete = "pushreceiverapp://pushMessage",
    ),
    SubscriptionUpdate(
        id = "SubscriptionUpdate",
        titleId = R.string.push_subscription_update,
        descId = R.string.push_subscription_update_desc,
        importance = NotificationManagerCompat.IMPORTANCE_LOW,
        priority = NotificationCompat.PRIORITY_LOW,
        notificationId = 3,
        pircTap = 4,
        pircDelete = 5,
        uriPrefixDelete = "pushreceiverapp://subscriptionUpdate",
    ),
}

/**
 * 通知チャネルの初期化を
 * androidx app startupのイニシャライザとして実装したもの
 */
@Suppress("unused")
class NotificationChannelsInitializer : Initializer<Boolean> {
    override fun dependencies(): List<Class<out Initializer<*>>> =
        emptyList()

    override fun create(context: Context): Boolean {
        context.run {
            val list = NotificationChannels.values()
            AdbLog.i("createNotificationChannel(s) size=${list.size}")
            val notificationManager = NotificationManagerCompat.from(this)
            list.map {
                NotificationChannel(
                    it.id,
                    getString(it.titleId),
                    it.importance,
                ).apply {
                    description = getString(it.descId)
                }
            }.forEach {
                notificationManager.createNotificationChannel(it)
            }
        }
        return true
    }
}
