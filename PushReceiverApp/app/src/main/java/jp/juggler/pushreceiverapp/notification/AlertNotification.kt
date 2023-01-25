package jp.juggler.pushreceiverapp.alert

import android.Manifest
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import jp.juggler.pushreceiverapp.R
import jp.juggler.pushreceiverapp.ActAlert.Companion.intentActAlert
import jp.juggler.pushreceiverapp.notification.NotificationChannels
import jp.juggler.util.AdbLog
import jp.juggler.util.withCaption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * トーストの代わりに使えるような、単純なメッセージを表示する通知
 */
private const val piTapRequestCode = 0
private const val notificationId = 1

fun Context.showAlertNotification(
    message: String,
    title: String = getString(R.string.alert),
    priority: Int = NotificationCompat.PRIORITY_HIGH,
) {
    if (ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        AdbLog.w("missing POST_NOTIFICATIONS. alert=$message")
        return
    }

    val now = System.currentTimeMillis()
    val tag = "${System.currentTimeMillis()}/${message.hashCode()}"

    // Create an explicit intent for an Activity in your app
    val iTap = intentActAlert(tag = tag, message = message, title = title)

    val piTap =
        PendingIntent.getActivity(this, piTapRequestCode, iTap, PendingIntent.FLAG_IMMUTABLE)

    val builder = NotificationCompat.Builder(this, NotificationChannels.Alert.id)
        .setSmallIcon(R.drawable.nc_error)
        .setContentTitle(title)
        .setContentText(message)
        .setPriority(priority)
        .setWhen(now)
        .setContentIntent(piTap)
        .setAutoCancel(true)

    NotificationManagerCompat.from(this).notify(tag, notificationId, builder.build())
}

fun Context.dialogOrAlert(message: String) {
    try {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    } catch (_: Throwable) {
        showAlertNotification(message)
    }
}

fun Context.showError(ex: Throwable, message: String) {
    AdbLog.e(ex, message)
    if (ex is CancellationException) return
    dialogOrAlert(ex.withCaption(message))
}

fun AppCompatActivity.launchAndShowError(
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend () -> Unit
) {
    lifecycleScope.launch(context) {
        try {
            block()
        } catch (ex: Throwable) {
            showError(ex, "")
        }
    }
}
