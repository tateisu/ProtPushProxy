package jp.juggler.pushreceiverapp.auth

import android.net.Uri
import androidx.core.net.toUri
import jp.juggler.pushreceiverapp.api.ApiMisskey
import jp.juggler.pushreceiverapp.api.JSON_SERVER_TYPE
import jp.juggler.pushreceiverapp.api.SERVER_MISSKEY
import jp.juggler.pushreceiverapp.db.Client
import jp.juggler.pushreceiverapp.push.PrefDevice
import jp.juggler.util.AdbLog
import jp.juggler.util.JsonObject
import jp.juggler.util.digestSHA256
import jp.juggler.util.encodeUTF8
import jp.juggler.util.notBlank
import jp.juggler.util.notEmpty
import jp.juggler.util.toJsonArray

open class AuthMisskey10(
    private val clientAccess: Client.Access,
    protected val prefDevice: PrefDevice,
    protected val api: ApiMisskey,
) : AuthBase() {
    companion object {
        const val CALLBACK_URL_MISSKEY10 = "$OAUTH_CALLBACK_URL/misskey10"

        private const val appNameId = "PropPushReceiver"
        private const val appDescription = "prototype push receiver app."

        const val KEY_MISSKEY_APP_SECRET = "secret"

        fun getScopeArrayMisskey(serverJson: JsonObject?): List<String> {
            val misskeyMajorVersion = serverJson?.misskeyMajorVersion ?: 0
            return if (misskeyMajorVersion >= 11) {
                // Misskey 11以降
                // https://github.com/syuilo/misskey/blob/master/src/server/api/kinds.ts
                setOf(
                    "read:account",
                    "write:account",
                    "read:blocks",
                    "write:blocks",
                    "read:drive",
                    "write:drive",
                    "read:favorites",
                    "write:favorites",
                    "read:following",
                    "write:following",
                    "read:messaging",
                    "write:messaging",
                    "read:mutes",
                    "write:mutes",
                    "write:notes",
                    "read:notifications",
                    "write:notifications",
                    "read:reactions",
                    "write:reactions",
                    "write:votes"
                )
            } else {
                // https://github.com/syuilo/misskey/issues/2341
                // Misskey 10まで
                setOf(
                    "account-read",
                    "account-write",
                    "account/read",
                    "account/write",
                    "drive-read",
                    "drive-write",
                    "favorite-read",
                    "favorite-write",
                    "favorites-read",
                    "following-read",
                    "following-write",
                    "messaging-read",
                    "messaging-write",
                    "note-read",
                    "note-write",
                    "notification-read",
                    "notification-write",
                    "reaction-read",
                    "reaction-write",
                    "vote-read",
                    "vote-write"
                )
            }.toList().sorted()
        }

        fun List<String>.encodeScopeArray() =
            sorted().joinToString(",")

        private const val hexLower ="0123456789abcdef"

        fun ByteArray.encodeHexLower(): String {
            val size = this.size
            val sb = StringBuilder(size * 2)
            for (i in 0 until size) {
                val value = this[i].toInt()
                sb.append(hexLower[(value shr 4) and 15])
                sb.append(hexLower[value and 15])
            }
            return sb.toString()
        }
    }

    override suspend fun authStep1(
        prefDevice: PrefDevice,
        apiHost: String,
        serverJson: JsonObject?,
        forceUpdateClient: Boolean
    ): Uri {
        // val sessionId = UUID.randomUUID().toString()
        // prefDevice.saveAuthStart(apiHost, sessionId)

        // スコープ一覧を取得する
        val scopeArray = getScopeArrayMisskey(serverJson)

        // クライアントが既に登録されているか
        val clientName = clientName
        val clientInfo = clientAccess.find(apiHost, clientName)
        val clientId = clientInfo?.clientId
        val clientSecret = clientInfo?.clientSecret
        val authVersion = clientInfo?.authVersion
        val serverType = clientInfo?.clientJson?.string(JSON_SERVER_TYPE)

        if (AUTH_VERSION == authVersion &&
            serverType == SERVER_MISSKEY &&
            !clientSecret.isNullOrEmpty() &&
            !clientId.isNullOrEmpty()
        ) {
            try {
                api.appShow(apiHost, clientId)
                // app/show の応答はsecretを含まないので保存してはいけない
            } catch (ex: Throwable) {
                // アプリ情報の取得に失敗しても致命的ではない
                AdbLog.w(ex, "can't get app info, but continue…")
                null
            }?.let { tmpClientInfo ->
                val savedPermission = tmpClientInfo.jsonArray("permission")
                    ?.stringList()?.encodeScopeArray()
                // - アプリが登録済みで
                // - クライアント名が一致してて
                // - パーミッションが同じ
                // ならクライアント情報を再利用する
                if (tmpClientInfo.string("name") == clientName &&
                    savedPermission == scopeArray.encodeScopeArray()
                ) {
                    return createAuthUri(apiHost, clientSecret)
                }
            }
        }
        // XXX appSecretを使ってクライアント情報を削除できるようにするべきだが、該当するAPIが存在しない

        val appJson = api.appCreate(
            apiHost = apiHost,
            appNameId = appNameId,
            appDescription = appDescription,
            clientName = clientName,
            scopeArray = scopeArray.toJsonArray(),
            callbackUrl = CALLBACK_URL_MISSKEY10,
        )

        val client = Client(
            apiHost = apiHost,
            clientName = clientName,
            clientJson = appJson.apply {
                put("JSON_SERVER_TYPE", SERVER_MISSKEY)
            },
            authVersion = AUTH_VERSION
        ).also {
            it.clientId = appJson.string("id")
                .notBlank() ?: error("missing app id")
            it.clientSecret = appJson.string(KEY_MISSKEY_APP_SECRET)
                .notBlank() ?: error("missing app secret in app/create api response.")
            // clientCredential はmisskeyでは使わない
            it.scope = scopeArray.encodeScopeArray()
        }

        clientAccess.save(client)

        return createAuthUri(apiHost, client.clientSecret!!)
    }

    /**
     * Misskey v12 までの認証に使うURLを生成する
     *
     * {"token":"0ba88e2d-4b7d-4599-8d90-dc341a005637","url":"https://misskey.xyz/auth/0ba88e2d-4b7d-4599-8d90-dc341a005637"}
     */
    private suspend fun createAuthUri(apiHost: String, appSecret: String): Uri {
        prefDevice.saveAuthStart(
            apiHost = apiHost,
            sessionId = appSecret,
        )
        return api.authSessionGenerate(apiHost, appSecret)
            .string("url").notEmpty()?.toUri()
            ?: error("missing 'url' in session/generate.")
    }

    override suspend fun authStep2(
        prefDevice: PrefDevice,
        uri: Uri
    ): Auth2Result {
        val token = uri.getQueryParameter("token")
            ?.notBlank() ?: error("missing token in callback URL")

        val apiHost = prefDevice.authApiHost
            ?.notBlank() ?: error("missing instance name.")

        val appSecret = prefDevice.authSessionId
            ?.notBlank() ?: error("missing appSecret.")

        val tokenInfo = api.authSessionUserKey(
            apiHost,
            appSecret,
            token,
        )
        // {"accessToken":"...","user":{…}}

        val accessToken = tokenInfo.string("accessToken")
            ?.notBlank() ?: error("missing accessToken in the userkey response.")
        val apiKey = "$accessToken$appSecret".encodeUTF8().digestSHA256().encodeHexLower()

        val accountJson = tokenInfo.jsonObject("user")
            ?: error("missing user in the userkey response.")

        return Auth2Result(
            apiHost = apiHost,
            apDomain = apiHost,
            userName = accountJson.string("username")?.notEmpty() ?: error("missing username"),
            serverJson = api.getServerInformation(apiHost, apiKey),
            tokenJson = tokenInfo.apply {
                put(JSON_AUTH_VERSION, AUTH_VERSION)
                put(JSON_ACCESS_TOKEN, apiKey)
                remove("user")
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
