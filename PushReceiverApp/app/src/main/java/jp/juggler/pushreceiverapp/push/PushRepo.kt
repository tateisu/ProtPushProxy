package jp.juggler.pushreceiverapp.push

import android.content.Context
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import jp.juggler.pushreceiverapp.api.ApiError
import jp.juggler.pushreceiverapp.api.AppServerApi
import jp.juggler.pushreceiverapp.api.PushSubscriptionApi
import jp.juggler.pushreceiverapp.db.PushMessage
import jp.juggler.pushreceiverapp.db.SavedAccount
import jp.juggler.pushreceiverapp.db.appDatabase
import jp.juggler.pushreceiverapp.dialog.ProgressDialog
import jp.juggler.pushreceiverapp.notification.showSnsNotification
import jp.juggler.pushreceiverapp.push.PushWorker.Companion.launchUpWorker
import jp.juggler.pushreceiverapp.push.WebPushCrypt.Companion.parseSemicoron
import jp.juggler.util.AdbLog
import jp.juggler.util.JsonObject
import jp.juggler.util.decodeBase64
import jp.juggler.util.decodeJsonObject
import jp.juggler.util.decodeUTF8
import jp.juggler.util.digestSHA256
import jp.juggler.util.encodeBase64Url
import jp.juggler.util.encodeUTF8
import jp.juggler.util.notBlank
import jp.juggler.util.notEmpty
import jp.juggler.util.parseTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.unifiedpush.android.connector.UnifiedPush
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.util.concurrent.TimeUnit

val Context.pushRepo: PushRepo
    get() {
        val okHttp = OkHttpClient()
        val appDatabase = appDatabase
        return PushRepo(
            context = applicationContext,
            accountAccess = appDatabase.accountAccess(),
            pushMessageAccess = appDatabase.pushMessageAccess(),
            pushApi = PushSubscriptionApi(okHttp),
            appServerApi = AppServerApi(okHttp)
        )
    }

class PushRepo(
    private val context: Context,
    private val pushApi: PushSubscriptionApi,
    private val appServerApi: AppServerApi,
    private val accountAccess: SavedAccount.Access,
    private val pushMessageAccess: PushMessage.Access,
    private val crypt: WebPushCrypt = WebPushCrypt(),
    private val prefDevice: PrefDevice = context.prefDevice,
    private val fcmHandler: FcmHandler = context.fcmHandler,
) {
    companion object {
        private val reHttp = """https?://""".toRegex()

        @Suppress("RegExpSimplifiable")
        private val reTailDigits = """([0-9]+)\z""".toRegex()

        const val JSON_CAME_FROM = "<>cameFrom"
        const val CAME_FROM_UNIFIED_PUSH = "unifiedPush"
        const val CAME_FROM_FCM = "fcm"

        fun String?.followDomain(apiHost: String) = when {
            isNullOrEmpty() -> null
            reHttp.containsMatchIn(this) -> this
            this[0] == '/' -> "https://$apiHost$this"
            else -> "https://$apiHost/$this"
        }
    }

    /**
     * UPでプッシュサービスを選ぶと呼ばれる
     */
    suspend fun switchDistributor(
        pushDistributor:String,
        reporter: ProgressDialog.ProgressReporter,
    ) {
        AdbLog.i("switchDistributor: pushDistributor=$pushDistributor")
        prefDevice.pushDistributor = pushDistributor

        withContext(Dispatchers.IO) {
            // WorkManagerの完了済みのジョブを捨てる
            reporter.setMessage("WorkManager.pruneWork")
            WorkManager.getInstance(context).pruneWork().await()

            // Unified購読の削除
            // 後でブロードキャストを受け取るかもしれない
            reporter.setMessage("UnifiedPush.unregisterApp")
            UnifiedPush.unregisterApp(context)

            // FCMトークンの削除。これでこの端末のこのアプリへの古いエンドポイント登録はgoneになり消えるはず
            reporter.setMessage("fcmHandler.deleteFcmToken")
            fcmHandler.deleteFcmToken()

            when(pushDistributor) {
                PrefDevice.PUSH_DISTRIBUTOR_NONE->{
                    // 購読解除
                    reporter.setMessage("SubscriptionUpdateService.launch")
                    launchEndpointRegistration()
                }
                PrefDevice.PUSH_DISTRIBUTOR_FCM->{
                    // 特にイベントは来ないので、プッシュ購読をやりなおす
                    reporter.setMessage("SubscriptionUpdateService.launch")
                    launchEndpointRegistration()
                }
                else->{
                    reporter.setMessage("UnifiedPush.saveDistributor")
                    UnifiedPush.saveDistributor(context, pushDistributor)
                    // 何らかの理由で登録は壊れることがあるため、登録し直す
                    reporter.setMessage("UnifiedPush.registerApp")
                    UnifiedPush.registerApp(context)
                    // 少し後にonNewEndpointが発生するので、続きはそこで
                }
            }
        }
    }

    /**
     * UnifiedPushのエンドポイントが決まったら呼ばれる
     */
    suspend fun newUpEndpoint(upEndpoint: String) {
        val upPackageName = UnifiedPush.getDistributor(context).notEmpty()
            ?: error("missing upPackageName")
        if (upPackageName != prefDevice.pushDistributor) {
            AdbLog.w("newEndpoint: race condition detected!")
        }

        // 古いエンドポイントを別プロパティに覚えておく
        prefDevice.upEndpoint
            ?.takeIf { it.isNotEmpty() && it != upEndpoint }
            ?.let { prefDevice.upEndpointExpired = it }

        prefDevice.upEndpoint = upEndpoint

        // 購読の更新
        registerEndpoint(keepAliveMode = false)
    }

    fun launchEndpointRegistration(keepAliveMode: Boolean = false) {
        workDataOf(
            PushWorker.KEY_ACTION to PushWorker.ACTION_REGISTER_ENDPOINT,
            PushWorker.KEY_KEEP_ALIVE_MODE to keepAliveMode,
        ).launchUpWorker(context)
    }

    /**
     * サービスから呼ばれる。購読の更新を行う
     */
    suspend fun registerEndpoint(
        keepAliveMode: Boolean
    ) {
        // 古いFCMトークンの情報はアプリサーバ側で勝手に消えるはず
        try {
            // 期限切れのUPエンドポイントがあればそれ経由の中継を解除する
            prefDevice.fcmTokenExpired.notEmpty()?.let {
                AdbLog.i("remove fcmTokenExpired")
                appServerApi.endpointRemove(fcmToken = it)
                prefDevice.fcmTokenExpired = null
            }
        } catch (ex: Throwable) {
            AdbLog.w(ex, "can't forgot fcmTokenExpired")
        }

        try {
            // 期限切れのUPエンドポイントがあればそれ経由の中継を解除する
            prefDevice.upEndpointExpired.notEmpty()?.let {
                AdbLog.i("remove upEndpointExpired")
                appServerApi.endpointRemove(upUrl = it)
                prefDevice.upEndpointExpired = null
            }
        } catch (ex: Throwable) {
            AdbLog.w(ex, "can't forgot upEndpointExpired")
        }

        val accounts = accountAccess.load()

        // map of acctHash to account
        val acctHashMap = accounts.associateBy {
            it.acct.encodeUTF8().digestSHA256().encodeBase64Url()
        }
        val acctHashList = acctHashMap.keys.toList()
        if (acctHashList.isEmpty()) {
            AdbLog.w("acctHashMap is empty. no need to update register endpoint")
            return
        }

        if (keepAliveMode) {
            val lastUpdated = prefDevice.timeLastEndpointRegister
            val now = System.currentTimeMillis()
            if (now - lastUpdated < TimeUnit.DAYS.toMillis(3)) {
                AdbLog.i("lazeMode: skip re-registration.")
            }
        }

        var willRemoveSubscription = false

        // アプリサーバにendpointを登録する
        AdbLog.i("pushDistributor=${prefDevice.pushDistributor}")
        val json = when (prefDevice.pushDistributor) {
            null, "" -> when {
                fcmHandler.hasFcm -> registerEndpointFcm(acctHashList)
                else -> {
                    AdbLog.w("pushDistributor not selected. but can't select default distributor from background service.")
                    null
                }
            }
            PrefDevice.PUSH_DISTRIBUTOR_NONE -> {
                willRemoveSubscription = true
                null
            }
            PrefDevice.PUSH_DISTRIBUTOR_FCM -> registerEndpointFcm(acctHashList)
            else -> registerEndpointUnifiedPush(acctHashList)
        }
        when {
            json.isNullOrEmpty() ->
                AdbLog.i("no information of appServerHash.")

            else -> {
                // acctHash => appServerHash のマップが返ってくる
                // アカウントに覚える
                var saveCount = 0
                for (acctHash in json.keys) {
                    val a = acctHashMap[acctHash] ?: continue
                    a.appServerHash = json.string(acctHash) ?: continue
                    accountAccess.save(a)
                    ++saveCount
                }
                AdbLog.i("appServerHash updated. saveCount=$saveCount")
            }
        }

        accounts.forEach {
            try {
                updateSubscription(it, willRemoveSubscription = willRemoveSubscription)
            } catch (ex: Throwable) {
                AdbLog.e(ex, "[${it.acct}] updateSubscription failed.")
            }
        }

        prefDevice.timeLastEndpointRegister = System.currentTimeMillis()
    }

    private suspend fun registerEndpointUnifiedPush(acctHashList: List<String>) =
        when (val upEndpoint = prefDevice.upEndpoint) {
            null, "" -> {
                AdbLog.w("missing upEndpoint. can't register endpoint.")
                null
            }
            else -> {
                AdbLog.i("endpointUpsert up ")
                appServerApi.endpointUpsert(
                    upUrl = upEndpoint,
                    fcmToken = null,
                    acctHashList = acctHashList
                )
            }
        }

    private suspend fun registerEndpointFcm(acctHashList: List<String>) =
        when (val fcmToken = fcmHandler.loadFcmToken()) {
            null, "" -> {
                AdbLog.w("missing fcmToken. can't register endpoint.")
                null
            }
            else -> {
                AdbLog.i("endpointUpsert fcm ")
                appServerApi.endpointUpsert(
                    upUrl = null,
                    fcmToken = fcmToken,
                    acctHashList = acctHashList
                )
            }
        }

    /**
     * SNSサーバに購読を行う
     *
     * willRemoveSubscription=trueの場合、購読を削除する。
     * アクセストークン更新やアカウント削除の際に古い購読を捨てたい場合に使う。
     */
    suspend fun updateSubscription(
        a: SavedAccount,
        willRemoveSubscription: Boolean,
    ) {
        val isMastodon = true

        when {
            isMastodon -> updateSubscriptionMastodon(
                a = a,
                willRemoveSubscription = willRemoveSubscription
            )
            else -> error("not implemented")
        }
    }

    private suspend fun updateSubscriptionMastodon(
        a: SavedAccount,
        willRemoveSubscription: Boolean,
    ) {
        val appServerHash = a.appServerHash
        if (appServerHash.isNullOrEmpty()) {
            AdbLog.i("${a.acct} has no appServerHash.")
            return
        }
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
        AdbLog.i("${a.acct} oldSubscription=${oldSubscription}")

        val appServerUrlPrefix = "https://mastodon-msg.juggler.jp/api/v2/m"
        val newUrl = "${appServerUrlPrefix}/a_${appServerHash}/dh_${deviceHash}"

        val oldEndpointUrl = oldSubscription?.string("endpoint")
        when (oldEndpointUrl) {
            // 購読がない。作ってもよい
            null -> Unit
            else -> {
                val params = buildMap {
                    if (oldEndpointUrl.startsWith(appServerUrlPrefix)) {
                        oldEndpointUrl.substring(appServerUrlPrefix.length)
                            .split("/")
                            .forEach { pair ->
                                val cols = pair.split("_", limit = 2)
                                cols.elementAtOrNull(0)?.notEmpty()?.let { k ->
                                    put(k, cols.elementAtOrNull(1) ?: "")
                                }
                            }
                    }
                }
                if (params["dh"] != deviceHash) {
                    // この端末で作成した購読ではない。
                    AdbLog.w("subscription deviceHash not match. keep it for other devices. ${a.acct} $oldEndpointUrl")
                    return
                }
            }
        }

        if (willRemoveSubscription) {
            when (oldSubscription) {
                null -> {
                    AdbLog.i("subscription is not exist, not required. nothing to do. ${a.acct}")
                }
                else -> {
                    AdbLog.i("removing unnecessary subscription. ${a.acct}")
                    pushApi.deletePushSubscription(a)
                }
            }
            return
        }

        val alerts = PushSubscriptionApi.alertTypes.associateWith { true }
        if (newUrl != oldEndpointUrl) {
            AdbLog.i("${a.acct} createPushSubscription")
            val keyPair = crypt.generateKeyPair()
            val auth = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val p256dh = crypt.encodeP256Dh(keyPair.public as ECPublicKey)
            val response = pushApi.createPushSubscription(
                a = a,
                endpointUrl = newUrl,
                p256dh = p256dh.encodeBase64Url(),
                auth = auth.encodeBase64Url(),
                alerts = alerts,
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
        } else {
            // エンドポイントURLに変化なし
            // Alertの更新はしたいかもしれない
            // XXX
        }
        AdbLog.i("updateUpSubscriptionMastodon complete. ${a.acct}")
    }

    //////////////////////////////////////////////////////////////////////////////
    // メッセージの処理

    suspend fun handleFcmMessage(data: Map<String, String>) {
        val pm = saveRawMessage(
            acctHash = data["ah"].notEmpty() ?: error("missing ah"),
            headerJson = data["hj"]?.decodeJsonObject() ?: error("missing hj"),
            body = data["b"]?.decodeBase64() ?: error("missing b"),
            cameFrom = CAME_FROM_FCM
        )
        decodeMessageContent(pm)
        // 解読できた(例外が出なかった)なら通知を再度出す
        context.showSnsNotification(pm)
    }

    /**
     * UpMessageReceiverから呼ばれる。
     * とりあえず受信データをDBに保存する。
     */
    suspend fun saveUpMessage(message: ByteArray): PushMessage {
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

        // 順に読む
        val acctHash = readSubBytes()
        val headerJson = readSubBytes()
        val body = readSubBytes()

        return saveRawMessage(
            acctHash = acctHash.decodeUTF8(),
            headerJson = headerJson.decodeUTF8().decodeJsonObject(),
            body = body,
            cameFrom = CAME_FROM_UNIFIED_PUSH
        )
    }

    /**
     * UpWorkerから呼ばれる。
     * 保存データを解釈して通知を出す。
     */
    suspend fun handleUpMessage(messageId: Long) {
        val pm = pushMessageAccess.find(messageId)
            ?: error("missing pushMessage")
        decodeMessageContent(pm)
        // 解読できた(例外が出なかった)なら通知を再度出す
        context.showSnsNotification(pm)
    }

    /**
     * UIで再解読を選択した
     *
     * - 実際のアプリでは解読できたものだけを保存したいが、これは試験アプリなので…
     */
    suspend fun reDecode(pm: PushMessage) {
        decodeMessageContent(pm)
        // 解読できた(例外が出なかった)なら通知を再度出す
        context.showSnsNotification(pm)
    }

    /**
     * 解読前のプッシュデータを保存する
     *
     * - 実際のアプリでは解読できたものだけを保存したいが、これは試験アプリなので…
     */
    private suspend fun saveRawMessage(
        acctHash: String,
        headerJson: JsonObject,
        body: ByteArray,
        cameFrom: String,
    ): PushMessage {
        val a = accountAccess.load().find {
            acctHash == it.acct.encodeUTF8().digestSHA256().encodeBase64Url()
        } ?: error("missing account for acctHash $acctHash")

        headerJson[JSON_CAME_FROM] = cameFrom

        val pm = PushMessage(
            loginAcct = a.acct,
            headerJson = headerJson,
            rawBody = body,
        )
        pushMessageAccess.save(pm)
        return pm
    }

    /**
     * プッシュされたデータを解読してDB上の項目を更新する
     *
     * - 実際のアプリでは解読できたものだけを保存したいが、これは試験アプリなので…
     */
    private suspend fun decodeMessageContent(pm: PushMessage) {
        val a = accountAccess.find(pm.loginAcct)
            ?: error("missing login account ${pm.loginAcct}")

        val body = pm.rawBody
            ?: error("missing raw data.")

        val headerJson = pm.headerJson
        AdbLog.i("headerJson=$headerJson")
        //        headerJson={
        //            "Digest":"SHA-256=nnn",
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
    }

    /**
     * 通知をスワイプして削除した。
     * - URLからDB上の項目のIDを取得
     * - timeDismissを更新する
     */
    suspend fun onDeleteNotification(uri: String) {
        val messageDbId = reTailDigits.find(uri)?.groupValues?.elementAtOrNull(0)
            ?.toLongOrNull()
            ?: error("missing messageDbId in $uri")
        pushMessageAccess.dismiss(messageDbId)
    }

    suspend fun sweepOldMessage() {
        pushMessageAccess.sweepOld(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30))
    }
}
