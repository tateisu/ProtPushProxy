package jp.juggler.pushreceiverapp.notification

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import jp.juggler.pushreceiverapp.ActMessage.Companion.intentActMessage
import jp.juggler.pushreceiverapp.db.PushMessage
import jp.juggler.pushreceiverapp.notification.NotificationDeleteReceiver.Companion.intentNotificationDelete
import jp.juggler.util.AdbLog
import jp.juggler.util.loadIcon
import jp.juggler.util.notEmpty

/**
 * SNSからの通知を表示する
 */
suspend fun Context.showSnsNotification(
    pm: PushMessage,
) {
    if (Build.VERSION.SDK_INT >= 33) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            AdbLog.w("missing POST_NOTIFICATIONS.")
            return
        }
    }

    val nc = NotificationChannels.PushMessage
    val density = resources.displayMetrics.density

    suspend fun PushMessage.loadSmallIcon(context: Context): IconCompat {
        iconSmall?.notEmpty()
            ?.let { loadIcon(pm.iconSmall, (24f * density + 0.5f).toInt()) }
            ?.let { return IconCompat.createWithBitmap(it) }
        val iconId = notificationTypeToIconId(messageJson.string("notification_type"))
        return IconCompat.createWithResource(context, iconId)
    }

    val iconSmall = pm.loadSmallIcon(this)
    val iconBitmapLarge = loadIcon(pm.iconLarge, (48f * density + 0.5f).toInt())

    val url = "${nc.uriPrefixDelete}/${pm.messageDbId}"
    val iDelete = intentNotificationDelete(url.toUri())
    val piDelete =
        PendingIntent.getBroadcast(this, nc.pircDelete, iDelete, PendingIntent.FLAG_IMMUTABLE)

    val iTap = intentActMessage(pm.messageDbId)
    val piTap = PendingIntent.getActivity(this, nc.pircTap, iTap, PendingIntent.FLAG_IMMUTABLE)

    val builder = NotificationCompat.Builder(this, NotificationChannels.PushMessage.id).apply {
        priority = nc.priority
        setSmallIcon(iconSmall)
        iconBitmapLarge?.let { setLargeIcon(it) }
        setContentTitle(pm.loginAcct)
        setContentText(pm.messageShort)
        setWhen(pm.timestamp)
        setContentIntent(piTap)
        setDeleteIntent(piDelete)
        setAutoCancel(true)
    }

    NotificationManagerCompat.from(this).notify(url, nc.notificationId, builder.build())
}

/**
 * 通知を消す
 *
 * - 試験アプリなのであまり積極的に消さない…
 */
fun Context.deleteSnsNotification(messageDbId: Long) {
    try {
        val nc = NotificationChannels.PushMessage
        val url = "${nc.uriPrefixDelete}/${messageDbId}"
        NotificationManagerCompat.from(this).cancel(url, nc.notificationId)
    } catch (ex: Throwable) {
        AdbLog.e(ex, "deleteSnsNotification failed. messageDbId=$messageDbId")
    }
}
