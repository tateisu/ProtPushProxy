package jp.juggler.pushreceiverapp.fcm

import android.content.Context
import androidx.startup.Initializer
import com.google.firebase.messaging.RemoteMessage
import jp.juggler.pushreceiverapp.BuildConfig
import jp.juggler.pushreceiverapp.fcm.FcmHandler.hasFcm
import jp.juggler.pushreceiverapp.notification.showSnsNotification
import jp.juggler.util.AdbLog
import jp.juggler.util.cast
import jp.juggler.util.invokeSuspendFunction
import jp.juggler.util.notEmpty
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object FcmHandler {
    // この設定ファイルはバックアップ対象から除外するべき
    private const val fcmPrefFileName = "fcm_pref"
    private const val PREF_TOKEN = "token"
    private val mutex = Mutex()

    var onMessageReceived: (RemoteMessage) -> Unit = {}
    val fcmToken = MutableStateFlow<String?>(null)

    val hasFcm: Boolean
        get() = ! """noFcm""".toRegex(RegexOption.IGNORE_CASE)
            .containsMatchIn(BuildConfig.FLAVOR)

    fun saveFcmToken(context: Context, token: String) {
        context.getSharedPreferences(fcmPrefFileName, Context.MODE_PRIVATE)
            .edit().putString(PREF_TOKEN, token).apply()
    }

    suspend fun loadFcmToken(context: Context): String? {
        if (!hasFcm) return null

        val prefDevice = context.getSharedPreferences(fcmPrefFileName, Context.MODE_PRIVATE)

        // 設定ファイルに保持されていたらそれを使う
        prefDevice.getString(PREF_TOKEN, null)
            ?.notEmpty()?.let { return it }

        return try {
            mutex.withLock {
                val token = Class.forName("jp.juggler.fcmreceiver.FcmTokenLoader")
                    .getConstructor().run {
                        isAccessible = true
                        newInstance()
                    }.invokeSuspendFunction("getToken")
                    ?.cast<String>()
                    ?.notEmpty() ?: error("loadFcmToken: missing device token.")

                prefDevice.edit().putString(PREF_TOKEN, token).apply()

                token
            }
        } catch (ex: Throwable) {
            AdbLog.w(ex, "loadFcmToken failed")
            null
        }
    }

}

/**
 * AndroidManifest.xml で androidx.startup.InitializationProvider から参照される
 */
@Suppress("unused")
class FcmHandlerInitializer : Initializer<FcmHandler> {
    override fun dependencies(): List<Class<out Initializer<*>>> =
        emptyList()

    override fun create(context: Context): FcmHandler {
        runBlocking {
            val token = FcmHandler.loadFcmToken(context)
            token.notEmpty()?.let { FcmHandler.fcmToken.emit(it) }
            AdbLog.i("FcmHandlerInitializer hasFcm=${hasFcm}, BuildConfig.FLAVOR=${BuildConfig.FLAVOR}, token=${token?.length}")

            FcmHandler.onMessageReceived = {
                runBlocking {
                    context.showSnsNotification(
                        message = it.data["text"] ?: "no message",
                        title = it.data["title"] ?: "no title",
                        imageUrl = it.data["imageUrl"],
                    )
                }
            }
        }
        return FcmHandler
    }
}
