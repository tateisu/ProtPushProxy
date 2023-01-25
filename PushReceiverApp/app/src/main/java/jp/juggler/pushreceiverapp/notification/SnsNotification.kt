package jp.juggler.pushreceiverapp.notification

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import jp.juggler.pushreceiverapp.R
import jp.juggler.util.AdbLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException

/**
 * SNSからの通知
 */
private const val piTapRequestCode = 1
private const val notificationId = 2

@OptIn(ExperimentalCoroutinesApi::class)
suspend fun Context.showSnsNotification(
    message: String,
    title: String,
    imageUrl: String?,
    priority: Int = NotificationCompat.PRIORITY_DEFAULT,
) {
    val bitmap = try {
        suspendCancellableCoroutine<Bitmap> { cont ->
            @Suppress("ThrowableNotThrown")
            val target = object : CustomTarget<Bitmap>() {
                override fun onLoadFailed(errorDrawable: Drawable?) {
                    cont.resumeWithException(IllegalStateException("load failed."))
                }

                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    cont.resume(resource) {
                        resource.recycle()
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    cont.resumeWithException(IllegalStateException("load cleared."))
                }
            }
            Glide.with(this)
                .asBitmap()
                .load(imageUrl)
                .into(target)
            cont.invokeOnCancellation {
                Glide.with(this).clear(target)
            }
        }
    }catch(ex:Throwable){
        AdbLog.w(ex,"url=$imageUrl")
        null
    }

    if (ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        AdbLog.w("missing POST_NOTIFICATIONS. alert=$message")
        bitmap?.recycle()
        return
    }

    val now = System.currentTimeMillis()

    val tag = "${System.currentTimeMillis()}/${message.hashCode()}"

//    // Create an explicit intent for an Activity in your app
//    val iTap = intentActAlert(tag = tag, message = message, title = title)
//
//    val piTap =
//        PendingIntent.getActivity(this, piTapRequestCode, iTap, PendingIntent.FLAG_IMMUTABLE)

    val builder = NotificationCompat.Builder(this, NotificationChannels.Alert.id).apply{
        setSmallIcon(R.drawable.nc_error)
        setContentTitle(title)
        setContentText(message)
        setPriority(priority)
        setWhen(now)
//        .setContentIntent(piTap)
        setAutoCancel(true)
        bitmap?.let{ setLargeIcon(it)}
    }

    NotificationManagerCompat.from(this).notify(tag, notificationId, builder.build())
}
