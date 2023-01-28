package jp.juggler.pushreceiverapp.push

import jp.juggler.pushreceiverapp.api.ApiError
import jp.juggler.pushreceiverapp.api.ApiMastodon
import jp.juggler.pushreceiverapp.db.PushMessage
import jp.juggler.pushreceiverapp.db.SavedAccount
import jp.juggler.pushreceiverapp.push.PushRepo.Companion.followDomain
import jp.juggler.util.decodeBase64
import jp.juggler.util.digestSHA256
import jp.juggler.util.encodeBase64Url
import jp.juggler.util.encodeUTF8
import jp.juggler.util.notBlank
import jp.juggler.util.notEmpty
import jp.juggler.util.parseTime
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey

class PushMastodon(
    private val api:ApiMastodon,
    private val crypt:WebPushCrypt,
    private val prefDevice: PrefDevice,
    private val accountAccess:SavedAccount.Access,
) :PushBase(){
    override suspend fun updateSubscription(
        subLog:SubscriptionLogger,
        a: SavedAccount,
        willRemoveSubscription: Boolean,
    ){
        val appServerHash = a.appServerHash
        if (appServerHash.isNullOrEmpty()) {
            subLog.e("アプリサーバにエンドポイントが登録されていません。プッシュディストリビュータを選択しなおしてください。")
            return
        }
        val deviceHash = "${prefDevice.installIdv2},${a.acct}".encodeUTF8().digestSHA256().encodeBase64Url()

        val oldSubscription = try {
            api.getPushSubscription(a)
        } catch (ex: Throwable) {
            if ((ex as? ApiError)?.response?.code == 404) {
                null
            } else {
                throw ex
            }
        }
        subLog.i("${a.acct} oldSubscription=${oldSubscription}")
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
                    subLog.e("subscription deviceHash not match. keep it for other devices. ${a.acct} $oldEndpointUrl")
                    return
                }
            }
        }

        if (willRemoveSubscription) {
            when (oldSubscription) {
                null -> {
                    subLog.i("subscription is not exist, not required. nothing to do.")
                }
                else -> {
                    subLog.i("removing unnecessary subscription.")
                    api.deletePushSubscription(a)
                }
            }
            return
        }

        val alerts = ApiMastodon.alertTypes.associateWith { true }
        if (newUrl != oldEndpointUrl) {
            val keyPair = crypt.generateKeyPair()
            val auth = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val p256dh = crypt.encodeP256Dh(keyPair.public as ECPublicKey)

            subLog.i("api.createPushSubscription")
            val response = api.createPushSubscription(
                a = a,
                endpointUrl = newUrl,
                p256dh = p256dh.encodeBase64Url(),
                auth = auth.encodeBase64Url(),
                alerts = alerts,
                policy = "all",
            )
            val serverKeyStr = response.string("server_key")
                ?: error("missing server_key.")

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
            subLog.i("Push subscription has been updated.")
        } else {
            // エンドポイントURLに変化なし
            // Alertの更新はしたいかもしれない
            // XXX
            subLog.i("Push subscription endpoint URL is not changed. keep..")
        }
    }

    override  suspend fun formatPushMessage(
        a: SavedAccount,
        pm: PushMessage,
    ){
        val json = pm.messageJson
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
    }
}
