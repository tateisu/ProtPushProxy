package jp.juggler.pushreceiverapp.api

import android.net.Uri
import androidx.core.net.toUri
import jp.juggler.util.JsonObject
import jp.juggler.util.buildJsonObject
import jp.juggler.util.encodeQuery
import jp.juggler.util.jsonObjectOf
import jp.juggler.util.notEmpty
import jp.juggler.util.toFormRequestBody
import jp.juggler.util.toPost
import okhttp3.OkHttpClient
import okhttp3.Request

class AuthApi(
    private val okHttp: OkHttpClient,
) {
    suspend fun getServerInformation(
        apiHost: String,
        accessToken: String? = null,
    ): JsonObject = Request.Builder()
        .url("https://${apiHost}/api/v1/instance")
        .authorizationBearer(accessToken)
        .build()
        .await(okHttp)
        .readJsonObject()

    /**
     * クライアントアプリをサーバに登録する
     */
    suspend fun registerClient(
        apiHost: String,
        scopeString: String,
        clientName: String,
        callbackUrl: String,
    ): JsonObject = jsonObjectOf(
        "client_name" to clientName,
        "redirect_uris" to callbackUrl,
        "scopes" to scopeString,
    ).encodeQuery().toFormRequestBody().toPost()
        .url("https://${apiHost}/api/v1/apps")
        .build()
        .await(okHttp)
        .readJsonObject()

    /**
     * サーバ上に登録されたアプリを参照する client credential を作成する
     */
    suspend fun createClientCredential(
        apiHost: String,
        clientId: String,
        clientSecret: String,
        callbackUrl: String,
    ): JsonObject = buildJsonObject {
        put("grant_type", "client_credentials")
        put("scope", "read write") // 空白は + に変換されること
        put("client_id", clientId)
        put("client_secret", clientSecret)
        put("redirect_uri", callbackUrl)
    }.encodeQuery().toFormRequestBody().toPost()
        .url("https://${apiHost}/oauth/token")
        .build()
        .await(okHttp)
        .readJsonObject()

    /**
     * client credentialを使って、サーバ上に登録されたクライアントアプリの情報を取得する
     * - クライアント情報がまだ有効か調べるのに使う
     */
    // client_credentialがまだ有効か調べる
    suspend fun verifyClientCredential(
        apiHost: String,
        clientCredential: String,
    ): JsonObject = Request.Builder()
        .url("https://${apiHost}/api/v1/apps/verify_credentials")
        .authorizationBearer(clientCredential)
        .build()
        .await(okHttp)
        .readJsonObject()

    /**
     * client credentialを削除する
     * - クライアント情報そのものは消えない…
     */
    suspend fun revokeClientCredential(
        apiHost: String,
        clientId: String,
        clientSecret: String,
        clientCredential: String,
    ): JsonObject = buildJsonObject {
        put("client_id", clientId)
        put("client_secret", clientSecret)
        put("token", clientCredential)
    }.encodeQuery().toFormRequestBody().toPost()
        .url("https://${apiHost}/oauth/revoke")
        .build()
        .await(okHttp)
        .readJsonObject()

    // 認証ページURLを作る
    fun createAuthUrl(
        apiHost: String,
        scopeString: String,
        callbackUrl: String,
        clientId: String,
        state: String,
    ): Uri = buildJsonObject {
        put("client_id", clientId)
        put("response_type", "code")
        put("redirect_uri", callbackUrl)
        put("scope", scopeString)
        put("state", state)
        put("grant_type", "authorization_code")
        put("approval_prompt", "force")
        put("force_login", "true")
        //		+"&access_type=offline"
    }.encodeQuery()
        .let { "https://${apiHost}/oauth/authorize?$it" }
        .toUri()

    /**
     * ブラウザから帰ってきたコードを使い、認証の続きを行う
     */
    suspend fun authStep2(
        apiHost: String,
        clientId: String,
        clientSecret: String,
        scopeString: String,
        callbackUrl: String,
        code: String,
    ): JsonObject = jsonObjectOf(
        "grant_type" to "authorization_code",
        "code" to code,
        "client_id" to clientId,
        "client_secret" to clientSecret,
        "scope" to scopeString,
        "redirect_uri" to callbackUrl,
    ).encodeQuery().toFormRequestBody().toPost()
        .url("https://${apiHost}/oauth/token")
        .build()
        .await(okHttp)
        .readJsonObject()

    // 認証されたアカウントのユーザ情報を取得する
    suspend fun verifyAccount(
        apiHost: String,
        accessToken: String,
    ): JsonObject = Request.Builder()
        .url("https://${apiHost}/api/v1/accounts/verify_credentials")
        .authorizationBearer(accessToken)
        .build()
        .await(okHttp)
        .readJsonObject()

    /**
     * ユーザ登録API
     * アクセストークンはあるがアカウントIDがない状態になる。
     */
    suspend fun createUser(
        apiHost: String,
        clientCredential: String,
        params: CreateUserParams,
    ) = buildJsonObject {
        put("username", params.username)
        put("email", params.email)
        put("password", params.password)
        put("agreement", params.agreement)
        params.reason?.notEmpty()?.let { put("reason", it) }
    }.encodeQuery().toFormRequestBody().toPost()
        .url("https://${apiHost}/api/v1/accounts")
        .authorizationBearer(clientCredential)
        .build()
        .await(okHttp)
        .readJsonObject()
}
