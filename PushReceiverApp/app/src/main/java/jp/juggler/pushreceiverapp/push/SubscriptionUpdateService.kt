package jp.juggler.pushreceiverapp.push

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import jp.juggler.pushreceiverapp.ActMain
import jp.juggler.pushreceiverapp.R
import jp.juggler.pushreceiverapp.alert.showAlertNotification
import jp.juggler.pushreceiverapp.notification.NotificationChannels
import jp.juggler.util.AdbLog
import jp.juggler.util.AppDispatchers
import jp.juggler.util.EmptyScope
import jp.juggler.util.withCaption
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.lang.ref.WeakReference

class SubscriptionUpdateService : Service() {
    companion object {
        fun launch(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SubscriptionUpdateService::class.java)
            )
        }

        var refService: WeakReference<SubscriptionUpdateService>? = null

        val channel by lazy {
            Channel<Context>(capacity = Channel.CONFLATED)
        }

        val job by lazy {
            EmptyScope.launch(AppDispatchers.MainImmediate) {
                var lastContext: WeakReference<Context>? = null
                while (true) {
                    try {
                        val c = when (refService?.get()) {
                            null -> channel.receive()
                            else -> try{
                                withTimeout(333L) {
                                    channel.receive()
                                }
                            }catch(_:TimeoutCancellationException){
                                null
                            }
                        }.also { it ?: refService?.get()?.onEmpty() }
                        if (c != null) {
                            lastContext = WeakReference(c)
                            withContext(AppDispatchers.IO) {
                                c.pushRepo.subscriptionUpdate(c)
                            }
                        }
                    } catch (ex: Throwable) {
                        when (ex) {
                            is ClosedReceiveChannelException -> break
                            else -> {
                                AdbLog.w(ex)
                                lastContext?.get()
                                    ?.showAlertNotification(
                                        ex.withCaption("subscription update failed.")
                                    )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        refService = WeakReference(this)

        super.onCreate()

        val nc = NotificationChannels.SubscriptionUpdate
        val context = this

        val notification = NotificationCompat.Builder(this, nc.id).apply {
            priority = nc.priority
            setSmallIcon(R.drawable.refresh_24)
            setContentTitle(getString(nc.titleId))
            setContentText(getString(nc.descId))
            setWhen(System.currentTimeMillis())
            setOngoing(true)
            val iTap = Intent(context, ActMain::class.java)
            val piTap =
                PendingIntent.getActivity(context, nc.pircTap, iTap, PendingIntent.FLAG_IMMUTABLE)
            setContentIntent(piTap)
        }.build()
        if (Build.VERSION.SDK_INT >= 33) {
            startForeground(
                nc.notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(nc.notificationId, notification)
        }
        EmptyScope.launch {
            channel.send(applicationContext)
            AdbLog.i("job.isActive=${job.isActive}")
        }
    }

    override fun onDestroy() {
        refService = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return super.onStartCommand(intent, flags, startId)
    }

    fun onEmpty() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
