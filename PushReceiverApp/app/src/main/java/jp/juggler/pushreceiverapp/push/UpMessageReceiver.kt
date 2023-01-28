package jp.juggler.pushreceiverapp.push

import android.content.Context
import androidx.work.workDataOf
import jp.juggler.pushreceiverapp.notification.showAlertNotification
import jp.juggler.pushreceiverapp.notification.showError
import jp.juggler.pushreceiverapp.push.PushWorker.Companion.launchUpWorker
import jp.juggler.util.AdbLog
import jp.juggler.util.checkAppForeground
import kotlinx.coroutines.runBlocking
import org.unifiedpush.android.connector.MessagingReceiver

/**
 * UnifiedPush のイベントを処理するレシーバー。
 * - メインスレッドで呼ばれてコルーチン的に辛い。
 * - データ保存だけして残りはUpWorkでバックグラウンド処理する。
 */
class UpMessageReceiver : MessagingReceiver() {
    /**
     * registerApp が完了すると呼ばれる
     * - メインスレッドで呼ばれる
     * - UIから操作した直後なので、だいたいフォアグラウンド状態だからrunBlockingしなくてもいいかな。
     */
    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        try {
            workDataOf(
                PushWorker.KEY_ACTION to PushWorker.ACTION_UP_ENDPOINT,
                PushWorker.KEY_ENDPOINT to endpoint,
            ).launchUpWorker(context)
        } catch (ex: Throwable) {
            AdbLog.e(ex, "onNewEndpoint failed.")
        }
    }

    /**
     * registerAppに失敗した
     * - 呼ばれるのを見たことがない…
     */
    override fun onRegistrationFailed(context: Context, instance: String) {
        checkAppForeground("UpMessageReceiver.onRegistrationFailed")
        context.showAlertNotification("onRegistrationFailed: instance=$instance, thread=${Thread.currentThread().name}")
    }

    /**
     * 登録解除が完了したら呼ばれる
     * - メインスレッドで呼ばれる
     * - ntfyアプリ上から購読を削除した場合に呼ばれた。
     * - 特に何もしなくていいかな…
     */
    override fun onUnregistered(context: Context, instance: String) {
        checkAppForeground("UpMessageReceiver.onUnregistered")
    }

    /**
     * メッセージを受信した
     * - メインスレッドで呼ばれる
     * - runBlocking するべきかしないべきか迷う…
     * - これ契機でのサービス起動とかないはず。
     * - アイコンのロードが失敗するのかもしれない
     */
    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        checkAppForeground("UpMessageReceiver.onMessage")
        runBlocking {
            try {
                // DBへの保存を急いで行う
                val pm = context.pushRepo.saveUpMessage(message)
                // 後の処理はワーカーでやる
                workDataOf(
                    PushWorker.KEY_ACTION to PushWorker.ACTION_UP_MESSAGE,
                    PushWorker.KEY_MESSAGE_ID to pm.messageDbId,
                ).launchUpWorker(context)
            } catch (ex: Throwable) {
                context.showError(ex, "onMessage failed.")
            }
        }
    }
}
