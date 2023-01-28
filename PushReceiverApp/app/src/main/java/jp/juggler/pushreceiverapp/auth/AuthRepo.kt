package jp.juggler.pushreceiverapp.auth

import android.content.Context
import android.net.Uri
import jp.juggler.pushreceiverapp.api.ApiError
import jp.juggler.pushreceiverapp.api.ApiMastodon
import jp.juggler.pushreceiverapp.api.ApiMisskey
import jp.juggler.pushreceiverapp.api.JSON_SERVER_TYPE
import jp.juggler.pushreceiverapp.api.SERVER_MISSKEY
import jp.juggler.pushreceiverapp.auth.AuthBase.Companion.misskeyMajorVersion
import jp.juggler.pushreceiverapp.db.Client
import jp.juggler.pushreceiverapp.db.SavedAccount
import jp.juggler.pushreceiverapp.db.appDatabase
import jp.juggler.pushreceiverapp.push.PrefDevice
import jp.juggler.pushreceiverapp.push.prefDevice
import jp.juggler.util.JsonObject
import okhttp3.OkHttpClient

val Context.authRepo: AuthRepo
    get() {
        val db = appDatabase
        val ohHttp = OkHttpClient()
        return AuthRepo(
            prefDevice = prefDevice,
            apiMastodon = ApiMastodon(ohHttp),
            apiMisskey = ApiMisskey(ohHttp),
            clientAccess = db.clientAccess(),
            accountAccess = db.accountAccess(),
        )
    }

class AuthRepo(
    private val prefDevice: PrefDevice,
    private val apiMastodon: ApiMastodon,
    private val apiMisskey: ApiMisskey,
    private val clientAccess: Client.Access,
    private val accountAccess: SavedAccount.Access,
) {

    private suspend fun serverInfoSub(apiHost: String): JsonObject? {
        try {
            return apiMisskey.getServerInformation(apiHost)
        } catch (ex: Throwable) {
            // Mastodonだと404になる
            val is404 = (ex as? ApiError)?.response?.code == 404
            if (!is404) throw ex
        }
        try {
            return apiMastodon.getServerInformation(apiHost)
        } catch (ex: Throwable) {
            // ホワイトリストモードのサーバでは認証エラーになる
            val is401 = (ex as? ApiError)?.response?.code == 401
            if (is401) return null
            throw ex
        }
    }

    private val authMastodon by lazy {
        AuthMastodon(clientAccess, apiMastodon)
    }
    private val authMisskey10 by lazy {
        AuthMisskey10(clientAccess, prefDevice, apiMisskey)
    }
    private val authMisskey13 by lazy {
        AuthMisskey13(clientAccess, prefDevice, apiMisskey)
    }

    /**
     * サーバ情報の種類により認証処理を切り替える
     */
    private fun authBase(serverJson: JsonObject?) =
        when (serverJson?.string(JSON_SERVER_TYPE)) {
            SERVER_MISSKEY -> when {
                serverJson.misskeyMajorVersion >= 13 -> authMisskey13
                else -> authMisskey10
            }
            else -> authMastodon
        }
    /**
     * コールバックURLの種類により認証処理を切り替える
     */
    private fun authBase(uri: Uri): AuthBase {
        val uriString = uri.toString()
        return when {
            uriString.startsWith(AuthMisskey13.CALLBACK_URL_MIAUTH13) -> authMisskey13
            uriString.startsWith(AuthMisskey10.CALLBACK_URL_MISSKEY10) -> authMisskey10
            else -> authMastodon
        }
    }

    suspend fun serverInfo(apiHost: String) =
        serverInfoSub(apiHost)
            ?.apply { string(JSON_SERVER_TYPE) ?: error("missing server type") }

    suspend fun authStep1(
        apiHost: String,
        serverJson: JsonObject?,
        forceUpdate: Boolean = false,
    ): Uri = authBase(serverJson).authStep1(
        prefDevice = prefDevice,
        apiHost = apiHost,
        serverJson = serverJson,
        forceUpdateClient = forceUpdate
    )

    /**
     * OAuthコールバックを受け取って認証の続きを行う
     */
    suspend fun authStep2(uri: Uri): Auth2Result =
        authBase(uri).authStep2(prefDevice, uri)

    /**
     * アプリ内DBのアカウント情報を更新する
     */
    suspend fun updateAccount(auth2Result: Auth2Result): SavedAccount {
        var account = accountAccess.find(
            userName = auth2Result.userName,
            apDomain = auth2Result.apDomain
        )
        when (account) {
            null -> account = SavedAccount(
                apiHost = auth2Result.apiHost,
                apDomain = auth2Result.apDomain,
                userName = auth2Result.userName,
                tokenJson = auth2Result.tokenJson,
                accountJson = auth2Result.accountJson,
                serverJson = auth2Result.serverJson,
            )
            else -> {
                account.apiHost = auth2Result.apiHost
                account.accountJson = auth2Result.accountJson
                account.tokenJson = auth2Result.tokenJson
                account.serverJson = auth2Result.serverJson
            }
        }
        accountAccess.save(account)
        return account
    }

    suspend fun removeAccount(a: SavedAccount) =
        accountAccess.delete(a)

    fun accountListFlow() = accountAccess.listFlow()
}
