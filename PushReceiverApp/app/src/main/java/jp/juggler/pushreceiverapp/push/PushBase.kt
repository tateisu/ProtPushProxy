package jp.juggler.pushreceiverapp.push

import android.content.Context
import androidx.annotation.StringRes
import jp.juggler.pushreceiverapp.db.PushMessage
import jp.juggler.pushreceiverapp.db.SavedAccount
import jp.juggler.util.digestSHA256
import jp.juggler.util.encodeBase64Url
import jp.juggler.util.encodeUTF8
import jp.juggler.util.notEmpty

/**
 * PushMastodon, PushMisskey13 のベースクラス
 */
abstract class PushBase {
    companion object {
        val appServerUrlPrefix = "https://mastodon-msg.juggler.jp/api/v2/m"
    }

    interface SubscriptionLogger {
        val context: Context
        fun i(msg: String)
        fun e(msg: String)
        fun w(ex: Throwable, msg: String)
        fun e(ex: Throwable, msg: String)

        fun i(@StringRes stringId: Int) = i(context.getString(stringId))
        fun e(@StringRes stringId: Int) = i(context.getString(stringId))
    }

    protected abstract val prefDevice: PrefDevice

    // 購読の確認と更新
    abstract suspend fun updateSubscription(
        subLog: SubscriptionLogger,
        a: SavedAccount,
        willRemoveSubscription: Boolean,
    )

    // プッシュメッセージのJSONデータを通知用に整形
    abstract suspend fun formatPushMessage(
        a: SavedAccount,
        pm: PushMessage,
    )

    fun deviceHash(a: SavedAccount) =
        "${prefDevice.installIdv2},${a.acct}".encodeUTF8().digestSHA256().encodeBase64Url()

    fun snsCallbackUrl(a: SavedAccount): String? =
        a.appServerHash?.notEmpty()?.let {
            "${appServerUrlPrefix}/a_${it}/dh_${deviceHash(a)}"
        }
}
