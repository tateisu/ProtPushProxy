package jp.juggler.pushreceiverapp.api

import jp.juggler.util.AppDispatchers
import jp.juggler.util.JsonObject
import jp.juggler.util.buildJsonObject
import jp.juggler.util.encodeQuery
import jp.juggler.util.jsonArrayOf
import jp.juggler.util.toPostRequestBuilder
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * アプリサーバのAPI
 */
class ApiAppServer(
    private val okHttp: OkHttpClient,
    private val appServerPrefix: String = "https://mastodon-msg.juggler.jp/api/v2",
) {
    /**
     * 中継エンドポイントが無効になったら削除する
     */
    suspend fun endpointRemove(
        upUrl: String? = null,
        fcmToken: String? = null,
    ): JsonObject = buildJsonObject {
        upUrl?.let { put("upUrl", it) }
        fcmToken?.let { put("fcmToken", it) }
    }.encodeQuery().let {
        Request.Builder()
            .url("${appServerPrefix}/endpoint/remove?$it")
    }.delete().build()
        .await(okHttp)
        .readJsonObject()

    /**
     * エンドポイントとアカウントハッシュをアプリサーバに登録する
     */
    suspend fun endpointUpsert(
        upUrl: String?,
        fcmToken: String?,
        acctHashList: List<String>,
    ): JsonObject =
        buildJsonObject {
            upUrl?.let { put("upUrl", it) }
            fcmToken?.let { put("fcmToken", it) }
            put("acctHashList", jsonArrayOf(*(acctHashList.toTypedArray())))
        }.toPostRequestBuilder()
            .url("${appServerPrefix}/endpoint/upsert")
            .build()
            .await(okHttp)
            .readJsonObject()

    suspend fun getLargeObject(
        largeObjectId: String
    ): ByteArray? = withContext(AppDispatchers.IO) {
        Request.Builder()
            .url("${appServerPrefix}/l/$largeObjectId")
            .build()
            .await(okHttp)
            .body?.bytes()
    }
}
