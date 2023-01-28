package jp.juggler.pushreceiverapp.push

import jp.juggler.pushreceiverapp.R
import jp.juggler.pushreceiverapp.api.ApiError
import jp.juggler.pushreceiverapp.api.ApiMisskey
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

class PushMisskey(
    private val api: ApiMisskey,
    private val crypt: WebPushCrypt,
    private val prefDevice: PrefDevice,
    private val accountAccess: SavedAccount.Access,
) : PushBase() {
    companion object {
        const val JSON_LAST_ENDPOINT_URL = "<>lastEndpointUrl"
    }

    override suspend fun updateSubscription(
        subLog: SubscriptionLogger,
        a: SavedAccount,
        willRemoveSubscription: Boolean,
    ) {
        val deviceHash =
            "${prefDevice.installIdv2},${a.acct}".encodeUTF8().digestSHA256().encodeBase64Url()
        val newUrl = a.appServerHash?.notEmpty()?.let {
            "${appServerUrlPrefix}/a_${it}/dh_${deviceHash}"
        }

        val lastEndpointUrl = a.tokenJson.string(JSON_LAST_ENDPOINT_URL) ?: newUrl
        var hasEmptySubscription = false
        if (!lastEndpointUrl.isNullOrEmpty()) {
            val lastSubscription = when (lastEndpointUrl) {
                null, "" -> null
                else -> try {
                    // Misskeyは2022/12/18に現在の購読を確認するAPIができた
                    api.getPushSubscription(a, lastEndpointUrl)
                    // 購読がない => 空オブジェクト (v13 drdr.club でそんな感じ)
                } catch (ex: Throwable) {
                    // APIがない => 404 (v10 めいすきーのソースと動作で確認)
                    when ((ex as? ApiError)?.response?.code) {
                        in 400 until 500 -> null
                        else -> throw ex
                    }
                }
            }

            if (lastSubscription != null) {
                if( lastSubscription.size == 0){
                    // 購読がないと空レスポンスになり、アプリ側で空オブジェクトに変換される
                    hasEmptySubscription = true
                }else if (lastEndpointUrl == newUrl && !willRemoveSubscription) {
                    when (lastSubscription.boolean("sendReadMessage")) {
                        false -> subLog.i(R.string.push_subscription_keep_using)
                        else -> {
                            // 未読クリア通知はオフにしたい
                            api.updatePushSubscription(a, newUrl, sendReadMessage = false)
                            subLog.i(R.string.push_subscription_off_unread_notification)
                        }
                    }
                    return
                }else{
                    // 古い購読はあったが、削除したい
                    api.deletePushSubscription(a, lastEndpointUrl)
                    a.tokenJson.remove(JSON_LAST_ENDPOINT_URL)
                    accountAccess.save(a)
                    if (willRemoveSubscription) {
                        subLog.i(R.string.push_subscription_delete_current)
                        return
                    }
                }
            }
        }
        if (newUrl == null) {
            if (willRemoveSubscription) {
                subLog.i(R.string.push_subscription_app_server_hash_missing_but_ok)
            } else {
                subLog.e(R.string.push_subscription_app_server_hash_missing_error)
            }
            return
        } else if (willRemoveSubscription) {
            // 購読を解除したい。
            // hasEmptySubscription が真なら購読はないが、
            // とりあえず何か届いても確実に読めないようにする
            when (a.pushKeyPrivate) {
                null -> subLog.i("購読は不要な状態です")
                else -> {
                    a.pushKeyPrivate = null
                    accountAccess.save(a)
                    subLog.i("購読が不要なので解読用キーを削除しました")
                }
            }
            return
        }

        // 鍵がなければ作る
        if (a.pushKeyPrivate == null ||
            a.pushKeyPublic == null ||
            a.pushAuthSecret == null
        ) {
            subLog.i("秘密鍵を生成します…")
            val keyPair = crypt.generateKeyPair()
            val auth = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val p256dh = crypt.encodeP256Dh(keyPair.public as ECPublicKey)
            a.pushKeyPrivate = keyPair.private.encoded
            a.pushKeyPublic = p256dh
            a.pushAuthSecret = auth
            accountAccess.save(a)
        }
        // 購読する
        val json = api.createPushSubscription(
            a = a,
            endpoint = newUrl,
            auth = a.pushAuthSecret!!.encodeBase64Url(),
            publicKey = a.pushKeyPublic!!.encodeBase64Url(),
            sendReadMessage = false,
        )
        // https://github.com/syuilo/misskey/issues/2541
        // https://github.com/syuilo/misskey/commit/4c6fb60dd25d7e2865fc7c4d97728593ffc3c902
        // 2018/9/1 の上記コミット以降、Misskeyでもサーバ公開鍵を得られるようになった
        val serverKey = json.string("key")
            ?.notEmpty()?.decodeBase64()
            ?: error("missing server key in response of sw/register API.")
        if (!serverKey.contentEquals(a.pushServerKey)) {
            a.pushServerKey = serverKey
            accountAccess.save(a)
            subLog.i("server key has been changed.")
        }
        subLog.i("subscription complete.")
    }

    /*
       https://github.com/syuilo/misskey/blob/master/src/services/create-notification.ts#L46
       Misskeyは通知に既読の概念があり、イベント発生後2秒たっても未読の時だけプッシュ通知が発生する。
       STでプッシュ通知を試すにはSTの画面を非表示にする必要があるのでWebUIを使って投稿していたが、
       WebUIを開いていると通知はすぐ既読になるのでプッシュ通知は発生しない。
       プッシュ通知のテスト時はST2台を使い、片方をプッシュ通知の受信チェック、もう片方を投稿などの作業に使うことになる。
    */
    override suspend fun formatPushMessage(
        a: SavedAccount,
        pm: PushMessage,
    ) {
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
