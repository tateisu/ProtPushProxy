package jp.juggler.pushreceiverapp.auth

import android.content.Context
import android.net.Uri
import jp.juggler.pushreceiverapp.api.ApiError
import jp.juggler.pushreceiverapp.api.AuthApi
import jp.juggler.pushreceiverapp.db.Client
import jp.juggler.pushreceiverapp.db.SavedAccount
import jp.juggler.pushreceiverapp.db.appDatabase
import jp.juggler.util.AdbLog
import jp.juggler.util.AppDispatchers
import jp.juggler.util.JsonObject
import jp.juggler.util.notEmpty
import jp.juggler.util.notZero
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

val Context.authRepo: AuthRepo
    get() {
        val db = appDatabase
        return AuthRepo(
            api = AuthApi(okHttp = OkHttpClient()),
            clientAccess = db.clientAccess(),
            accountAccess = db.accountAccess(),
        )
    }

class AuthRepo(
    private val api: AuthApi,
    private val clientAccess: Client.Access,
    private val accountAccess: SavedAccount.Access,
) {
    companion object {
        const val clientName = "PushReceiverApp"

        // アプリ内に保存する認証データのバージョン
        const val AUTH_VERSION = 1

        // Oauth クライアントの権限
        const val SCOPES = "read write follow push"

        // OAuth コールバックURL
        const val OAUTH_CALLBACK_URL = "pushreceiverapp://oauth_callback/mastodon"

        const val JSON_AUTH_VERSION = "<>authVersion"
        const val JSON_ACCESS_TOKEN = "<>accessToken"
    }

    // クライアントアプリの登録を確認するためのトークンを生成する
    // oAuth2 Client Credentials の取得
    // https://github.com/doorkeeper-gem/doorkeeper/wiki/Client-Credentials-flow
    // このトークンはAPIを呼び出すたびに新しく生成される…
    private suspend fun createClientCredentialToken(
        apiHost: String,
        client: Client,
    ): String {
        val credentialInfo = api.createClientCredential(
            apiHost = apiHost,
            clientId = client.clientId
                ?: error("missing client_id"),
            clientSecret = client.clientSecret
                ?: error("missing client_secret"),
            callbackUrl = OAUTH_CALLBACK_URL,
        )
        // AdbLog.i("credentialInfo: $credentialInfo")
        return credentialInfo.string("access_token")
            ?.notEmpty() ?: error("missing client credential.")
    }

    private suspend fun prepareClientCredential(apiHost: String, client: Client): String? {
        // 既にcredentialを持っているならそれを返す
        client.clientCredential?.notEmpty()?.let { return it }

        // token in clientCredential
        val clientCredential = try {
            createClientCredentialToken(apiHost, client)
        } catch (ex: Throwable) {
            if ((ex as? ApiError)?.response?.code == 422) {
                // https://github.com/tateisu/SubwayTooter/issues/156
                // some servers not support to get client_credentials.
                // just ignore error and skip.
                return null
            } else {
                throw ex
            }
        }
        client.clientCredential = clientCredential
        clientAccess.save(client)
        return clientCredential
    }

    private suspend fun prepareClient(
        apiHost: String,
        clientName: String,
        scopeString: String,
        forceUpdate: Boolean = false,
    ): Client {

        val oldClient = clientAccess.find(clientName, apiHost)
        when {
            // 古いクライアント情報は使わない。削除もしない。
            AUTH_VERSION != oldClient?.authVersion -> Unit

            else -> {
                val clientCredential = oldClient.clientCredential
                // client_credential があるならcredentialがまだ使えるか確認する
                if (!clientCredential.isNullOrEmpty()) {

                    // 存在確認するだけで、結果は使ってない
                    api.verifyClientCredential(apiHost, clientCredential)

                    // 過去にはスコープを+で連結したものを保存していた
                    val oldScope = oldClient.scope?.replace("+", " ")

                    when {
                        // クライアント情報を再利用する
                        !forceUpdate && oldScope == scopeString -> return oldClient

                        else -> try {
                            // マストドン2.4でスコープが追加された
                            // 取得時のスコープ指定がマッチしない(もしくは記録されていない)ならクライアント情報を再利用してはいけない
                            clientAccess.delete(oldClient)

                            // クライアントアプリ情報そのものはまだサーバに残っているが、明示的に消す方法は現状存在しない
                            // client credential だけは消せる
                            api.revokeClientCredential(
                                apiHost = apiHost,
                                clientId = oldClient.clientId
                                    ?: error("revokeClientCredential: missing client_id"),
                                clientSecret = oldClient.clientSecret
                                    ?: error("revokeClientCredential: missing client_secret"),
                                clientCredential = clientCredential,
                            )
                        } catch (ex: Throwable) {
                            // クライアント情報の削除処理はエラーが起きても無視する
                            AdbLog.w(ex, "can't delete client information.")
                        }
                    }
                }
            }
        }

        val newClient = api.registerClient(apiHost, scopeString, clientName, OAUTH_CALLBACK_URL)
            .let {
                Client(
                    apiHost = apiHost,
                    clientJson = it,
                    clientName = clientName,
                ).apply {
                    scope = scopeString
                    clientId = it.string("client_id")
                    clientSecret = it.string("client_secret")
                    authVersion = AUTH_VERSION
                }
            }
        // client credentialを取得して保存する
        // この時点ではまだ client credential がないので、必ず更新と保存が行われる
        prepareClientCredential(apiHost, newClient)

        return newClient
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

    suspend fun authStep1(
        apiHost: String,
        forceUpdate: Boolean = false,
    ): Uri {
        val scopes = SCOPES

        // ホワイトリストモードだとトークンがないとサーバ情報を取れないが、今回は対応しない
        val serverJson = api.getServerInformation(apiHost)
        val version = serverJson.string("version")
        AdbLog.i("authStep1 $apiHost version=$version")

        val client = prepareClient(apiHost, clientName, scopes, forceUpdate)

        val state = listOf(
            "random:${System.currentTimeMillis()}",
            "host:${apiHost}",
        ).joinToString(",")

        return api.createAuthUrl(
            apiHost = apiHost,
            scopeString = scopes,
            callbackUrl = OAUTH_CALLBACK_URL,
            clientId = client.clientId ?: error("missing client_id"),
            state = state,
        ).also { AdbLog.i("auth1 uri=$it") }
    }

    /**
     * OAuthコールバックを受け取って認証の続きを行う
     */
    suspend fun authStep2(uri: Uri): Auth2Result {
        // Mastodon 認証コールバック

        // エラー時
        // subwaytooter://oauth(\d*)/
        // ?error=access_denied
        // &error_description=%E3%83%AA%E3%82%BD%E3%83%BC%E3%82%B9%E3%81%AE%E6%89%80%E6%9C%89%E8%80%85%E3%81%BE%E3%81%9F%E3%81%AF%E8%AA%8D%E8%A8%BC%E3%82%B5%E3%83%BC%E3%83%90%E3%83%BC%E3%81%8C%E8%A6%81%E6%B1%82%E3%82%92%E6%8B%92%E5%90%A6%E3%81%97%E3%81%BE%E3%81%97%E3%81%9F%E3%80%82
        // &state=db%3A3
        arrayOf("error_description", "error")
            .mapNotNull { uri.getQueryParameter(it)?.trim()?.notEmpty() }
            .notEmpty()
            ?.let { error(it.joinToString("\n")) }

        // subwaytooter://oauth(\d*)/
        //    ?code=113cc036e078ac500d3d0d3ad345cd8181456ab087abc67270d40f40a4e9e3c2
        //    &state=host%3Amastodon.juggler.jp

        val code = uri.getQueryParameter("code")
            ?.trim()?.notEmpty() ?: error("missing code in callback url.")

        val cols = uri.getQueryParameter("state")
            ?.trim()?.notEmpty() ?: error("missing state in callback url.")

        var apiHost: String? = null
        for (param in cols.split(",")) {
            when {
                param.startsWith("host:") -> {
                    apiHost = param.substring(5)
                }
                // ignore other parameter
            }
        }

        apiHost ?: error("can't get apiHost from callback parameter.")

        val client = clientAccess.find(clientName, apiHost)
            ?: error("can't find client info for apiHost=$apiHost, clientName=$clientName")

        val tokenJson = api.authStep2(
            apiHost = apiHost,
            clientId = client.clientId!!,
            clientSecret = client.clientSecret!!,
            scopeString = client.scope!!,
            callbackUrl = OAUTH_CALLBACK_URL,
            code = code,
        )

        val accessToken = tokenJson.string("access_token")
            ?.notEmpty() ?: error("can't parse access token.")

        val accountJson = verifyAccount(
            apiHost = apiHost,
            accessToken = accessToken,
            outTokenJson = tokenJson,
        )
        val serverJson = api.getServerInformation(
            apiHost,
            accessToken = accessToken
        )

        return Auth2Result(
            apiHost = apiHost,
            apDomain = serverJson.string("uri") ?: error("missing serverJson.uri"),
            serverJson = serverJson,
            tokenJson = tokenJson,
            accountJson = accountJson,
            userName = accountJson.string("username") ?: error("missing accountJson.username"),
        )
    }

    /**
     * アプリ内DBのアカウント情報を更新する
     */
    suspend fun updateAccount(auth2Result: Auth2Result) {
        val oldAccount = accountAccess.find(
            userName = auth2Result.userName,
            apDomain = auth2Result.apDomain
        )
        if (oldAccount != null) {
            oldAccount.apiHost = auth2Result.apiHost
            oldAccount.accountJson = auth2Result.accountJson
            oldAccount.tokenJson = auth2Result.tokenJson
            oldAccount.serverJson = auth2Result.serverJson
            accountAccess.save(oldAccount)
        } else {
            val a = SavedAccount(
                apiHost = auth2Result.apiHost,
                apDomain = auth2Result.apDomain,
                userName = auth2Result.userName,
                tokenJson = auth2Result.tokenJson,
                accountJson = auth2Result.accountJson,
                serverJson = auth2Result.serverJson,
            )
            accountAccess.save(a)
        }
    }

    /**
     * アプリ内DBのアカウント情報の一覧
     */
    suspend fun accountList() = withContext(AppDispatchers.IO) {
        accountAccess.load().sortedWith { a, b ->
            a.userName.compareTo(b.userName)
                .notZero()?.let { return@sortedWith it }
            a.apDomain.compareTo(b.apDomain)
                .notZero()?.let { return@sortedWith it }
            0
        }
    }

   suspend fun removeAccount(a: SavedAccount) {
        accountAccess.delete(a)
    }
}
