package jp.juggler.pushreceiverapp.api

import jp.juggler.pushreceiverapp.db.SavedAccount
import jp.juggler.util.JsonObject
import jp.juggler.util.buildJsonObject
import jp.juggler.util.toPostRequestBuilder
import jp.juggler.util.toPutRequestBuilder
import okhttp3.OkHttpClient
import okhttp3.Request

class PushSubscriptionApi(
    private val okHttp: OkHttpClient,
) {
    companion object {
        val alertTypes = arrayOf(
            "mention",
            "status",
            "reblog",
            "follow",
            "follow_request",
            "favourite",
            "poll",
            "update",
            "admin.sign_up",
            "admin.report",
        )
    }

    /**
     * アクセストークンに設定されたプッシュ購読を見る
     */
    suspend fun getPushSubscription(
        a: SavedAccount,
    ): JsonObject = Request.Builder()
        .url("https://${a.apiHost}/api/v1/push/subscription")
        .authorizationBearer(a.accessToken)
        .build()
        .await(okHttp)
        .readJsonObject()

    /**
     * アクセストークンに設定されたプッシュ購読を削除する
     */
    suspend fun deletePushSubscription(
        a: SavedAccount,
    ): JsonObject = Request.Builder()
        .delete()
        .url("https://${a.apiHost}/api/v1/push/subscription")
        .authorizationBearer(a.accessToken)
        .build()
        .await(okHttp)
        .readJsonObject()

    /**
     * アクセストークンに対してプッシュ購読を登録する
     */
    suspend fun createPushSubscription(
        a: SavedAccount,
        // REQUIRED String. The endpoint URL that is called when a notification event occurs.
        endpointUrl: String,
        // REQUIRED String. User agent public key.
        // Base64 encoded string of a public key from a ECDH keypair using the prime256v1 curve.
        p256dh: String,
        // REQUIRED String. Auth secret. Base64 encoded string of 16 bytes of random data.
        auth: String,
        // map of alert type to boolean, true to receive for alert type. false? null?
        alerts: Map<String, Boolean>,
        // whether to receive push notifications from all, followed, follower, or none users.
        policy: String,
    ): JsonObject = buildJsonObject {
        put("subscription", buildJsonObject {
            put("endpoint", endpointUrl)
            put("keys", buildJsonObject {
                put("p256dh", p256dh)
                put("auth", auth)
            })
        })
        put("data", buildJsonObject {
            put("alerts", buildJsonObject {
                for (t in alertTypes) {
                    alerts[t]?.let { put(t, it) }
                }
            })
        })
        put("policy", policy)
    }.toPostRequestBuilder()
        .url("https://${a.apiHost}/api/v1/push/subscription")
        .authorizationBearer(a.accessToken)
        .build()
        .await(okHttp)
        .readJsonObject()

    /**
     * 購読のdata部分を更新する
     */
    suspend fun updatePushSubscriptionData(
        a: SavedAccount,
        alerts: Map<String, Boolean>,
        policy: String,
    ): JsonObject = buildJsonObject {
        put("data", buildJsonObject {
            put("alerts", buildJsonObject {
                for (t in alertTypes) {
                    alerts[t]?.let { put(t, it) }
                }
            })
        })
        put("policy", policy)
    }.toPutRequestBuilder()
        .url("https://${a.apiHost}/api/v1/push/subscription")
        .authorizationBearer(a.accessToken)
        .build()
        .await(okHttp)
        .readJsonObject()
}
