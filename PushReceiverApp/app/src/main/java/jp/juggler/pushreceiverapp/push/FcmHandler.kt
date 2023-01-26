package jp.juggler.pushreceiverapp.push

import android.content.Context
import androidx.startup.AppInitializer
import androidx.startup.Initializer
import jp.juggler.fcm.FcmTokenLoader
import jp.juggler.pushreceiverapp.BuildConfig
import jp.juggler.pushreceiverapp.alert.showError
import jp.juggler.util.AdbLog
import jp.juggler.util.AppDispatchers
import jp.juggler.util.EmptyScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class FcmHandler(context: Context) {

    val fcmToken = MutableStateFlow(context.prefDevice.fcmToken)

    val hasFcm: Boolean
        get() = !"""noFcm""".toRegex(RegexOption.IGNORE_CASE)
            .containsMatchIn(BuildConfig.FLAVOR)

    suspend fun onTokenChanged(context: Context, token: String) {
        if (token == context.prefDevice.fcmToken && token == fcmToken.value) return
        context.prefDevice.fcmToken = token
        fcmToken.emit(token)
    }

    suspend fun onMessageReceived(context: Context, a: String) {
        try {
            context.pushRepo.handleFcmMessage(context, a)
        } catch (ex: Throwable) {
            context.showError(ex, "onMessage failed.")
        }
    }

    suspend fun deleteToken(context: Context) {
        FcmTokenLoader().deleteToken()
        context.prefDevice.fcmToken = null
        fcmToken.emit(null)
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
        val newHandler = FcmHandler(context)
        AdbLog.i("FcmHandlerInitializer hasFcm=${newHandler.hasFcm}, BuildConfig.FLAVOR=${BuildConfig.FLAVOR}")
        EmptyScope.launch(AppDispatchers.DEFAULT) {
            try {
                FcmTokenLoader().getToken()
                    ?.let { newHandler.onTokenChanged(context, it) }
            } catch (ex: Throwable) {
                // https://github.com/firebase/firebase-android-sdk/issues/4053
                // java.io.IOException: java.util.concurrent.ExecutionException: java.io.IOException: SERVICE_NOT_AVAILABLE

                //
                // java.lang.IllegalStateException: Default FirebaseApp is not initialized in this process jp.juggler.pushreceiverapp. Make sure to call FirebaseApp.initializeApp(Context) first.
                // at com.google.firebase.FirebaseApp.getInstance(FirebaseApp.java:186)

                AdbLog.w(ex, "loadFcmToken failed")
            }
        }
        return newHandler
    }
}

val Context.fcmHandler: FcmHandler
    get() = AppInitializer.getInstance(this)
        .initializeComponent(FcmHandlerInitializer::class.java)
