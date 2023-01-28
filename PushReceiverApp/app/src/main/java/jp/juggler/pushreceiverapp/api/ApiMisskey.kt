package jp.juggler.pushreceiverapp.api

import android.net.Uri
import androidx.core.net.toUri
import jp.juggler.pushreceiverapp.db.SavedAccount
import jp.juggler.util.JsonArray
import jp.juggler.util.JsonObject
import jp.juggler.util.buildJsonObject
import jp.juggler.util.encodeQuery
import jp.juggler.util.jsonObjectOf
import jp.juggler.util.toPostRequestBuilder
import okhttp3.OkHttpClient

class ApiMisskey(
    private val okHttp: OkHttpClient,
) {
    suspend fun getServerInformation(
        apiHost: String,
        accessToken: String? = null,
    ): JsonObject = buildJsonObject {
        accessToken?.let { put("i", it) }
    }.toPostRequestBuilder()
        .url("https://${apiHost}/api/meta")
        .build()
        .await(okHttp)
        .readJsonObject()
        .apply {
            put(JSON_SERVER_TYPE, SERVER_MISSKEY)
        }

    /**
     * miauth のブラウザ認証URLを作成する
     */
    fun createAuthUrl(
        apiHost: String,
        sessionId: String,
        permission: String,
        callbackUrl: String,
        clientName: String? = null,
        iconUrl: String? = null,
    ): Uri = buildJsonObject {
        put("permission", permission)
        put("callback", callbackUrl)
        clientName?.let { put("name", it) }
        iconUrl?.let { put("icon", it) }
    }.encodeQuery()
        .let { "https://$apiHost/miauth/$sessionId?$it" }
        .toUri()

    /**
     * miauthの認証結果を確認する
     */
    suspend fun checkAuthSession(
        apiHost: String,
        sessionId: String,
    ): JsonObject = JsonObject(/*empty*/)
        .toPostRequestBuilder()
        .url("https://${apiHost}/api/miauth/${sessionId}/check")
        .build()
        .await(okHttp)
        .readJsonObject()

    ////////////////////////////////////////
    // misskey 10 auth

    suspend fun appCreate(
        apiHost: String,
        appNameId: String,
        appDescription: String,
        clientName: String,
        scopeArray: JsonArray,
        callbackUrl: String,
    ) = buildJsonObject {
        put("nameId", appNameId)
        put("name", clientName)
        put("description", appDescription)
        put("callbackUrl", callbackUrl)
        put("permission", scopeArray)
    }.toPostRequestBuilder()
        .url("https://${apiHost}/api/app/create")
        .build()
        .await(okHttp)
        .readJsonObject()

    suspend fun appShow(apiHost: String, appId: String) =
        jsonObjectOf("appId" to appId)
            .toPostRequestBuilder()
            .url("https://${apiHost}/api/app/show")
            .build()
            .await(okHttp)
            .readJsonObject()

    suspend fun authSessionGenerate(
        apiHost: String,
        appSecret: String,
    ): JsonObject = jsonObjectOf(
        "appSecret" to appSecret
    ).toPostRequestBuilder()
        .url("https://${apiHost}/api/auth/session/generate")
        .build()
        .await(okHttp)
        .readJsonObject()

    suspend fun authSessionUserKey(
        apiHost: String,
        appSecret: String,
        token: String,
    ): JsonObject = jsonObjectOf(
        "appSecret" to appSecret,
        "token" to token,
    ).toPostRequestBuilder()
        .url("https://${apiHost}/api/auth/session/userkey")
        .build()
        .await(okHttp)
        .readJsonObject()

    ////////////////////////////////////////
    suspend fun verifyAccount(
        apiHost: String,
        accessToken: String,
    ): JsonObject = jsonObjectOf("i" to accessToken)
        .toPostRequestBuilder()
        .url("https://${apiHost}/api/i")
        .build()
        .await(okHttp)
        .readJsonObject()

    /**
     * エンドポイントURLを指定してプッシュ購読の情報を取得する
     */
    suspend fun getPushSubscription(
        a: SavedAccount,
        endpoint: String,
    ): JsonObject = buildJsonObject {
        a.accessToken?.let { put("i", it) }
        put("endpoint", endpoint)
    }.toPostRequestBuilder()
        .url("https://${a.apiHost}/api/sw/show-registration")
        .build()
        .await(okHttp)
        .readJsonObject()

    suspend fun deletePushSubscription(
        a: SavedAccount,
        endpoint: String,
    ): JsonObject = buildJsonObject {
        a.accessToken?.let { put("i", it) }
        put("endpoint", endpoint)
    }.toPostRequestBuilder()
        .url("https://${a.apiHost}/api/sw/unregister")
        .build()
        .await(okHttp)
        .readJsonObject()

    /**
     * プッシュ購読を更新する。
     * endpointのURLはクエリに使われる。変更できるのはsendReadMessageだけ。
     */
    suspend fun updatePushSubscription(
        a: SavedAccount,
        endpoint: String,
        sendReadMessage: Boolean
    ): JsonObject = buildJsonObject {
        a.accessToken?.let { put("i", it) }
        put("endpoint", endpoint)
        put("sendReadMessage", sendReadMessage)
    }.toPostRequestBuilder()
        .url("https://${a.apiHost}/api/sw/update-registration")
        .build()
        .await(okHttp)
        .readJsonObject()

    suspend fun createPushSubscription(
        a: SavedAccount,
        endpoint: String,
        auth:String,
        publicKey:String,
        sendReadMessage:Boolean,
    ) :JsonObject =buildJsonObject {
        a.accessToken?.let { put("i", it) }
        put("endpoint", endpoint)
        put("auth", auth)
        put("publickey", publicKey)
        put("sendReadMessage", sendReadMessage)
    }.toPostRequestBuilder()
        .url("https://${a.apiHost}/api/sw/register")
        .build()
        .await(okHttp)
        .readJsonObject()
}
