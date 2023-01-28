package jp.juggler.pushreceiverapp.auth

import android.net.Uri
import jp.juggler.pushreceiverapp.push.PrefDevice
import jp.juggler.util.JsonObject

abstract class AuthBase {
    companion object {
        const val clientName = "PushReceiverApp"
        const val appIconUrl = "https://m1j.zzz.ac/subwaytooter-miauth-icon.png"

        // アプリ内に保存する認証データのバージョン
        const val AUTH_VERSION = 1

        // Oauth クライアントの権限
        const val SCOPES = "read write follow push"

        // OAuth コールバックURL
        const val OAUTH_CALLBACK_URL = "pushreceiverapp://oauth_callback"

        const val JSON_AUTH_VERSION = "<>authVersion"
        const val JSON_ACCESS_TOKEN = "<>accessToken"

        @Suppress("RegExpSimplifiable")
        private val reHeadDigits = """([0-9]+)""".toRegex()

        /**
         * serverJson 中のMisskeyバージョンを読む
         */
        val JsonObject.misskeyMajorVersion: Int
            get() = reHeadDigits.find(string("version") ?: "")
                ?.groupValues?.elementAtOrNull(0)?.toIntOrNull() ?: 0

    }

    abstract suspend fun authStep1(
        prefDevice: PrefDevice,
        apiHost: String,
        serverJson: JsonObject?,
        forceUpdateClient: Boolean,
    ): Uri

    abstract suspend fun authStep2(
        prefDevice: PrefDevice,
        uri: Uri,
    ): Auth2Result
}
