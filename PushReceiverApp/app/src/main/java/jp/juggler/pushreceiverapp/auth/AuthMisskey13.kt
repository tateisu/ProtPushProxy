package jp.juggler.pushreceiverapp.auth

import android.net.Uri
import jp.juggler.pushreceiverapp.api.ApiMisskey
import jp.juggler.pushreceiverapp.db.Client
import jp.juggler.pushreceiverapp.push.PrefDevice
import jp.juggler.util.JsonObject
import jp.juggler.util.notEmpty
import java.util.*

class AuthMisskey13(
    clientAccess: Client.Access,
    prefDevice: PrefDevice,
    api: ApiMisskey,
) : AuthMisskey10(
    clientAccess,
    prefDevice,
    api,
) {
    companion object {
        const val CALLBACK_URL_MIAUTH13 = "$OAUTH_CALLBACK_URL/miauth13"
    }

    override suspend fun authStep1(
        prefDevice: PrefDevice,
        apiHost: String,
        serverJson: JsonObject?,
        forceUpdateClient: Boolean
    ): Uri {
        val sessionId = UUID.randomUUID().toString()

        prefDevice.saveAuthStart(apiHost, sessionId)

        return api.createAuthUrl(
            apiHost = apiHost,
            sessionId = sessionId,
            permission = getScopeArrayMisskey(serverJson).encodeScopeArray(),
            callbackUrl = CALLBACK_URL_MIAUTH13,
            clientName = clientName,
            iconUrl = appIconUrl,
        )
    }

    override suspend fun authStep2(
        prefDevice: PrefDevice,
        uri: Uri
    ): Auth2Result {

        // コールバックURLに含まれるセッションID
        val sessionId = uri.getQueryParameter("session")
            .notEmpty() ?: error("missing sessionId in callback URL")

        // 認証開始時に保存した情報
        val savedSessionId = prefDevice.authSessionId
            .notEmpty() ?: error("missing savedSessionId")

        val apiHost = prefDevice.authApiHost
            .notEmpty() ?: error("missing apiHost")

        if (sessionId != savedSessionId) {
            error("auth session id not match.")
        }

        val data = api.checkAuthSession(apiHost, sessionId)
        val ok = data.boolean("ok")
        if (ok != true) {
            error("Authentication result is not ok. [$ok]")
        }
        val apiKey = data.string("token")
            ?: error("missing token.")

        val accountJson = data.jsonObject("user")
            ?: error("missing user.")

        return Auth2Result(
            apiHost = apiHost,
            apDomain = apiHost,
            userName = accountJson.string("username")?.notEmpty() ?: error("missing username"),
            serverJson = api.getServerInformation(apiHost),
            tokenJson = JsonObject().apply {
                put(JSON_AUTH_VERSION, AUTH_VERSION)
                put(JSON_ACCESS_TOKEN, apiKey)
            },
            accountJson = accountJson,
        )
    }

    private suspend fun verifyAccount(
        apiHost: String,
        accessToken: String,
        outTokenJson: JsonObject?,
    ): JsonObject = api.verifyAccount(
        apiHost = apiHost,
        accessToken = accessToken,
    ).also {
        // APIレスポンスが成功したら、そのデータとは無関係に
        // アクセストークンをtokenInfoに格納する。
        outTokenJson?.apply {
            put(JSON_AUTH_VERSION, AUTH_VERSION)
            put(JSON_ACCESS_TOKEN, accessToken)
        }
    }
}
