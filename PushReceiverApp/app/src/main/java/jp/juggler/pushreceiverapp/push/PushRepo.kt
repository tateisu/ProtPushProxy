package jp.juggler.pushreceiverapp.push

import android.content.Context
import android.os.SystemClock
import jp.juggler.pushreceiverapp.api.ApiError
import jp.juggler.pushreceiverapp.api.AppServerApi
import jp.juggler.pushreceiverapp.api.PushSubscriptionApi
import jp.juggler.pushreceiverapp.db.PushMessage
import jp.juggler.pushreceiverapp.db.SavedAccount
import jp.juggler.pushreceiverapp.db.appDatabase
import jp.juggler.pushreceiverapp.notification.showSnsNotification
import jp.juggler.pushreceiverapp.push.WebPushCrypt.Companion.parseSemicoron
import jp.juggler.util.AdbLog
import jp.juggler.util.AppDispatchers
import jp.juggler.util.EmptyScope
import jp.juggler.util.decodeBase64
import jp.juggler.util.decodeJsonObject
import jp.juggler.util.decodeUTF8
import jp.juggler.util.digestSHA256
import jp.juggler.util.encodeBase64Url
import jp.juggler.util.encodeUTF8
import jp.juggler.util.notBlank
import jp.juggler.util.notEmpty
import jp.juggler.util.parseTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.unifiedpush.android.connector.UnifiedPush
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey

val Context.pushRepo: PushRepo
    get() {
        val okHttp = OkHttpClient()
        val appDatabase = appDatabase
        return PushRepo(
            accountAccess = appDatabase.accountAccess(),
            pushMessageAccess = appDatabase.pushMessageAccess(),
            pushApi = PushSubscriptionApi(okHttp),
            appServerApi = AppServerApi(okHttp)
        )
    }

class PushRepo(
    private val pushApi: PushSubscriptionApi,
    private val appServerApi: AppServerApi,
    private val accountAccess: SavedAccount.Access,
    private val pushMessageAccess: PushMessage.Access,
    private val crypt: WebPushCrypt = WebPushCrypt(),
) {
    companion object {
        val reHttp = """https?://""".toRegex()

        @Suppress("RegExpSimplifiable")
        private val reTailDigits = """([0-9]+)\z""".toRegex()

        const val JSON_CAME_FROM = "<>cameFrom"
        const val CAME_FROM_UNIFIED_PUSH = "unifiedPush"

        val messageUpdate = MutableStateFlow(0L)

        suspend fun fireMessageUpdated() {
            EmptyScope.launch(AppDispatchers.IO) {
                try {
                    messageUpdate.emit(SystemClock.elapsedRealtime())
                } catch (ex: Throwable) {
                    AdbLog.w(ex, "fireMessageUpdated failed.")
                }
            }
        }

        fun String?.followDomain(apiHost: String) = when {
            isNullOrEmpty() -> null
            reHttp.containsMatchIn(this) -> this
            this[0] == '/' -> "https://$apiHost$this"
            else -> "https://$apiHost/$this"
        }
    }

    fun switchDistributor(context: Context, packageName: String) {
        // UPの全インスタンスの登録解除
        // ブロードキャストを何もなげておらず、イベントは発生しない
        UnifiedPush.forceRemoveDistributor(context)
        // UPは全体で同じdistributorを持つ
        UnifiedPush.saveDistributor(context, packageName)
        // 何らかの理由で登録は壊れることがあるため、登録し直す
        UnifiedPush.registerApp(context)
    }

    fun removePush(context: Context) {
        EmptyScope.launch(AppDispatchers.DEFAULT) {
            try {
                // UPの全インスタンスの登録解除
                // ブロードキャストを何もなげておらず、イベントは発生しない
                UnifiedPush.forceRemoveDistributor(context)
                // FCMの解除
                context.fcmHandler.deleteToken(context)
            } catch (ex: Throwable) {
                AdbLog.e(ex, "removePush failed.")
            }
        }
    }

    /**
     * UnifiedPushのエンドポイントが決まったら呼ばれる
     */
    suspend fun newEndpoint(context: Context, upEndpoint: String) {
        val prefDevice = context.prefDevice
        val oldFcmToken = prefDevice.fcmToken
        if (!oldFcmToken.isNullOrEmpty()) {
            try {
                appServerApi.endpointRemove(
                    upUrl = null,
                    fcmToken = oldFcmToken,
                )
            } catch (ex: Throwable) {
                AdbLog.w(ex, "can't forgot oldFcmToken")
                // エラーにしない
            }
        }
        val oldEndpoint = prefDevice.upEndpoint
        if (!oldEndpoint.isNullOrEmpty() && oldEndpoint != upEndpoint) {
            try {
                appServerApi.endpointRemove(
                    upUrl = oldEndpoint,
                    fcmToken = null,
                )
            } catch (ex: Throwable) {
                AdbLog.w(ex, "can't forgot oldFcmToken")
                // エラーにしない
            }
        }
        prefDevice.upEndpoint = upEndpoint
        val accounts = accountAccess.load()
        // acctHash => account
        val acctHashMap = accounts.associateBy {
            it.acct.encodeUTF8().digestSHA256().encodeBase64Url()
        }
        if (acctHashMap.isNotEmpty()) {
            // アプリサーバにendpointを登録する
            val json = appServerApi.endpointUpsert(
                upUrl = upEndpoint,
                fcmToken = null,
                acctHashList = acctHashMap.keys.toList(),
            )
            // acctHash => appServerHash のマップが返ってくる
            // アカウントに覚える
            for (acctHash in json.keys) {
                val a = acctHashMap[acctHash] ?: continue
                a.appServerHash = json.string(acctHash) ?: continue
                accountAccess.save(a)
            }
            accounts.forEach {
                try {
                    updateUpSubscriptionMastodon(context, it)
                } catch (ex: Throwable) {
                    AdbLog.w(ex, "updateUpSubscriptionMastodon failed. ${it.acct}")
                }
            }
        }
    }

    /**
     * SNSサーバに購読を行う
     */
    private suspend fun updateUpSubscriptionMastodon(context: Context, a: SavedAccount) {

        val appServerHash = a.appServerHash
        if (appServerHash.isNullOrEmpty()) {
            AdbLog.i("${a.acct} has no appServerHash.")
            return
        }
        val prefDevice = context.prefDevice
        val deviceHash =
            "${prefDevice.installIdv2},${a.acct}".encodeUTF8().digestSHA256().encodeBase64Url()

        val oldSubscription = try {
            pushApi.getPushSubscription(a)
        } catch (ex: Throwable) {
            if ((ex as? ApiError)?.response?.code == 404) {
                null
            } else {
                throw ex
            }
        }

        val urlPrefix = "https://mastodon-msg.juggler.jp/api/v2/m"
        val newUrl = "${urlPrefix}/a_${appServerHash}/dh_${deviceHash}"
        AdbLog.i("newUrl=$newUrl")

        val oldEndpointUrl = oldSubscription?.string("endpoint")
        if (oldEndpointUrl == null) {
            // 購読がない。作ってもよい
        } else {
            val params = buildMap {
                if (oldEndpointUrl.startsWith(urlPrefix)) {
                    oldEndpointUrl.substring(urlPrefix.length)
                        .split("/")
                        .forEach { pair ->
                            val cols = pair.split("_", limit = 2)
                            cols.elementAtOrNull(0).notEmpty()?.let { k ->
                                put(k, cols.elementAtOrNull(1) ?: "")
                            }
                        }
                }
            }
            val oldDeviceHash = params["dh"]
            if (oldDeviceHash != deviceHash) {
                // この端末で作成した購読ではない。
                AdbLog.w("updateUpSubscriptionMastodon deviceHash not match. acct=${a.acct}, oldEndpoint=${oldEndpointUrl}")
                return
            }
        }

        if (newUrl != oldEndpointUrl.toString()) {
            val keyPair = crypt.generateKeyPair()

            val auth = ByteArray(16).also { SecureRandom().nextBytes(it) }

            val p256dh = crypt.encodeP256Dh(keyPair.public as ECPublicKey)

            val response = pushApi.createPushSubscription(
                a = a,
                endpointUrl = newUrl,
                p256dh = p256dh.encodeBase64Url(),
                auth = auth.encodeBase64Url(),
                alerts = PushSubscriptionApi.alertTypes.associateWith { true },
                policy = "all",
            )
            val serverKeyStr = response.string("server_key")
                ?: error("missing server_key. ${a.acct}")

            val serverKey = serverKeyStr.decodeBase64()

            // p256dhは65バイトのはず
            // authは16バイトのはず
            // serverKeyは65バイトのはず

            // 登録できたらアカウントに覚える
            a.pushKeyPrivate = keyPair.private.encoded
            a.pushKeyPublic = p256dh
            a.pushAuthSecret = auth
            a.pushServerKey = serverKey
            accountAccess.save(a)
        }
        // XXX: alertの更新を行う可能性がある
        // val alerts = PushSubscriptionApi.alertTypes.associateWith { true }
    }

    suspend fun handleUpMessage(context: Context, message: ByteArray) {
        var pos = 0
        fun readUInt32(): Int {
            if (pos + 4 > message.size) error("unexpected end.")
            val b0 = message[pos].toInt().and(255)
            val b1 = message[pos + 1].toInt().and(255).shl(8)
            val b2 = message[pos + 2].toInt().and(255).shl(16)
            val b3 = message[pos + 3].toInt().and(255).shl(24)
            pos += 4
            return b0.or(b1).or(b2).or(b3)
        }

        fun readSubBytes(): ByteArray {
            val size = readUInt32()
            if (pos + size > message.size) error("unexpected end.")
            val subBytes = ByteArray(size)
            System.arraycopy(
                message, pos,
                subBytes, 0,
                size
            )
            pos += size
            return subBytes
        }

        val targetAcctHash = readSubBytes().decodeUTF8()
        val headerJson = readSubBytes().decodeUTF8().decodeJsonObject()
        val body = readSubBytes()
        val a = accountAccess.load().find {
            targetAcctHash == it.acct.encodeUTF8().digestSHA256().encodeBase64Url()
        } ?: error("missing account for acctHash $targetAcctHash")

        headerJson[JSON_CAME_FROM] = CAME_FROM_UNIFIED_PUSH

        val pm = PushMessage(
            loginAcct = a.acct,
            headerJson = headerJson,
            rawBody = body,
        )
        pushMessageAccess.save(pm)
        fireMessageUpdated()
        decodeUpPushMessage(pm)
        context.showSnsNotification(pm)
    }

    suspend fun reDecode(context: Context, pm: PushMessage) {
        decodeUpPushMessage(pm)
        context.showSnsNotification(pm)
    }

    private suspend fun decodeUpPushMessage(pm: PushMessage) {
        val a = accountAccess.find(pm.loginAcct)
            ?: error("missing login account ${pm.loginAcct}")

        val body = pm.rawBody
            ?: error("missing raw data.")

        val headerJson = pm.headerJson
        AdbLog.i("headerJson=$headerJson")
        //        headerJson={
        //            "Digest":"SHA-256=XXXXXXXXX",
        //            "Content-Encoding":"aesgcm",
        //            "Encryption":"salt=75n4Si2vAVv2xZFXnIh5Ww",
        //            "Crypto-Key":"dh=XXX;p256ecdsa=XXX",
        //            "Authorization":"WebPush XXX.XXX.XXX"
        //        }

        // Encryption からsaltを読む
        val saltBytes = headerJson.string("Encryption")?.parseSemicoron()
            ?.get("salt")?.decodeBase64()
            ?: error("missing Encryption.salt")

        // Crypt-Key から dh と p256ecdsa を見る
        val cryptKeys = headerJson.string("Crypto-Key")?.parseSemicoron()
            ?: error("missing Crypto-Key")
        val dh = cryptKeys["dh"]?.decodeBase64()
            ?: a.pushServerKey ?: error("missing pushServerKey")

        val result = crypt.decodeBody(
            body = body,
            saltBytes = saltBytes,
            receiverPrivateBytes = a.pushKeyPrivate ?: error("missing pushKeyPrivate"),
            receiverPublicBytes = a.pushKeyPublic ?: error("missing pushKeyPublic"),
            senderPublicBytes = dh,
            authSecret = a.pushAuthSecret ?: error("missing pushAuthSecret"),
        )
        val text = result.toString(StandardCharsets.UTF_8)
        val json = text.decodeJsonObject()

        val apiHost = a.apiHost

        if (json.containsKey("notification_type")) {
            // Mastodon 4.0
            pm.messageShort = json.string("title")?.trim()?.notBlank()
            pm.messageLong = StringBuilder().apply {
                json.string("title")?.notBlank()
                    ?.let { append(it).append("\n") }
                json.string("body")?.notBlank()
                    ?.let { append(it).append("\n") }
            }.trim().notBlank()?.toString()
            pm.iconLarge = json.string("icon").followDomain(apiHost)
            // iconSmall は通知タイプに合わせてアプリが用意するらしい
        } else {
            // old mastodon
            pm.timestamp = json.string("timestamp")
                ?.parseTime() ?: pm.timestamp
            pm.messageShort = json.string("title")?.trim()?.notBlank()
            pm.messageLong = StringBuilder().apply {
                json.string("title")?.notBlank()
                    ?.let { append(it).append("\n") }
                json.jsonObject("data")?.string("url")?.notBlank()
                    ?.let { append(it).append("\n") }
                json.jsonObject("data")?.string("message")?.notBlank()
                    ?.let { append(it).append("\n") }
            }.trim().notBlank()?.toString()
            pm.iconSmall = json.string("badge").followDomain(apiHost)
            pm.iconLarge = json.string("icon").followDomain(apiHost)
        }
        pm.messageJson = json
        pushMessageAccess.save(pm)
        fireMessageUpdated()
    }

    suspend fun handleFcmMessage(context: Context, a: String) {
        delay(10)
//        val pm = PushMessage(
//            loginAcct = a.acct,
//            messageJson = json,
//            timestamp = timestamp ?: System.currentTimeMillis(),
//            messageLong = messageLong,
//            messageShort = messageShort,
//            iconSmall =  json.string("badge").followDomain(),
//            iconLarge =  json.string("icon").followDomain(),
//        )
//        context.showSnsNotification(
//            message = a,
//            title = "no title",
//            iconUrlLarge = null,
//        )
    }

    suspend fun onDeleteNotification(uri: String) {
        val messageDbId = reTailDigits.find(uri)?.groupValues?.elementAtOrNull(0)
            ?.toLongOrNull()
            ?: error("missing messageDbId in $uri")
        val pm = pushMessageAccess.find(messageDbId)
            ?: error("missing PushMessage id=$messageDbId")
        if (pm.timeDismiss == 0L) {
            pm.timeDismiss = System.currentTimeMillis()
            pushMessageAccess.save(pm)
            fireMessageUpdated()
        }
    }
}

