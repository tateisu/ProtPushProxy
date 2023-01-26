package jp.juggler.pushreceiverapp.push

import android.content.Context
import androidx.startup.AppInitializer
import androidx.startup.Initializer
import java.util.*

class PrefDevice(context: Context) {
    companion object {
        // この設定ファイルはバックアップ対象から除外するべき
        const val SHARED_PREFERENCE_NAME = "prefDevice"

        private const val PREF_FCM_TOKEN = "fcmToken"
        private const val PREF_INSTALL_ID_V2 = "installIdV2"
        private const val PREF_UP_ENDPOINT = "upEndpoint"
        private const val PREF_ENDPOINT_ID = "endpointId"
    }

    private val sp = context.getSharedPreferences(SHARED_PREFERENCE_NAME, Context.MODE_PRIVATE)

    val installIdv2: String
        get() = synchronized(this) {
            sp.getString(PREF_INSTALL_ID_V2, null)
                ?: UUID.randomUUID().toString().also {
                    sp.edit().putString(PREF_INSTALL_ID_V2, it).apply()
                }
        }

    var fcmToken: String?
        get() = sp.getString(PREF_FCM_TOKEN, null)
        set(value) {
            sp.edit().putString(PREF_FCM_TOKEN, value).apply()
        }

    var upEndpoint: String?
        get() = sp.getString(PREF_UP_ENDPOINT, null)
        set(value) {
            sp.edit().putString(PREF_UP_ENDPOINT, value).apply()
        }
}

class PrefDeviceInitializer : Initializer<PrefDevice> {
    override fun dependencies(): List<Class<out Initializer<*>>> =
        emptyList()

    override fun create(context: Context) =
        PrefDevice(context.applicationContext)
}

val Context.prefDevice: PrefDevice
    get() = AppInitializer.getInstance(this)
        .initializeComponent(PrefDeviceInitializer::class.java)
