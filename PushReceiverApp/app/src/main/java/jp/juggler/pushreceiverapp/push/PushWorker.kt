package jp.juggler.pushreceiverapp.push

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import jp.juggler.pushreceiverapp.R
import jp.juggler.pushreceiverapp.notification.NotificationChannels
import jp.juggler.util.AdbLog
import jp.juggler.util.AppDispatchers
import jp.juggler.util.notEmpty
import kotlinx.coroutines.withContext

class PushWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_ACTION = "action"
        const val ACTION_UP_ENDPOINT = "upEndpoint"
        const val ACTION_UP_MESSAGE = "upMessage"
        const val ACTION_REGISTER_ENDPOINT = "endpointRegister"

        const val KEY_ENDPOINT = "endpoint"
        const val KEY_MESSAGE_ID = "messageId"
        const val KEY_KEEP_ALIVE_MODE = "keepAliveMode"

        fun Data.launchUpWorker(context: Context) {
            // EXPEDITED だと制約の種類が限られる
            // すぐ起動してほしいので制約は少なめにする
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()

            val request = OneTimeWorkRequestBuilder<PushWorker>().apply {
                setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                setConstraints(constraints)
                setInputData(this@launchUpWorker)
            }
            WorkManager.getInstance(context).enqueue(request.build())
        }
    }

    override suspend fun doWork(): Result = try {
        setForegroundAsync(createForegroundInfo())
        withContext(AppDispatchers.IO) {
            val pushRepo = applicationContext.pushRepo
            when (val action = inputData.getString(KEY_ACTION)) {
                ACTION_UP_ENDPOINT -> {
                    val endpoint = inputData.getString(KEY_ENDPOINT)
                        ?.notEmpty() ?: error("missing endpoint.")
                    pushRepo.newUpEndpoint(endpoint)
                }
                ACTION_REGISTER_ENDPOINT -> {
                    val keepAliveMode = inputData.getBoolean(KEY_KEEP_ALIVE_MODE, false)
                    pushRepo.registerEndpoint(keepAliveMode)
                }
                ACTION_UP_MESSAGE -> {
                    val messageId = inputData.getLong(KEY_MESSAGE_ID, 0L)
                        .takeIf { it != 0L } ?: error("missing message id.")
                    pushRepo.handleUpMessage(messageId)
                }
                else -> error("invalid action $action")
            }
        }
        Result.success()
    } catch (ex: Throwable) {
        AdbLog.e(ex, "doWork failed.")
        Result.failure()
    }

    // Creates an instance of ForegroundInfo which can be used to update the
    // ongoing notification.
    private fun createForegroundInfo() = applicationContext.run {
        val nc = NotificationChannels.PushMessageWorker
        val builder = NotificationCompat.Builder(this, nc.id).apply {
            priority = nc.priority
            setSmallIcon(R.drawable.refresh_24)
            setContentTitle(getString(nc.titleId))
            setContentText(getString(nc.descId))
            setWhen(System.currentTimeMillis())
            setOngoing(true)
        }
        ForegroundInfo(nc.notificationId, builder.build())
    }
}
