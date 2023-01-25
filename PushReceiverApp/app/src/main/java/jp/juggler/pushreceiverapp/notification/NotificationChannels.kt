package jp.juggler.pushreceiverapp.notification

import android.app.NotificationChannel
import android.content.Context
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import androidx.startup.Initializer
import jp.juggler.pushreceiverapp.R
import jp.juggler.util.*

enum class NotificationChannels(
    val id: String,
    @StringRes val titleId: Int,
    @StringRes val descId: Int,
    val importance: Int,
) {
    Alert(
        id = "Alert",
        titleId = R.string.alert,
        descId = R.string.alert_notification_desc,
        importance = NotificationManagerCompat.IMPORTANCE_HIGH,
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
