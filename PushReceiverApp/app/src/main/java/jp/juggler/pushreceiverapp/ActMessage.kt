package jp.juggler.pushreceiverapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import jp.juggler.pushreceiverapp.notification.launchAndShowError
import jp.juggler.pushreceiverapp.databinding.ActMessageBinding
import jp.juggler.pushreceiverapp.db.PushMessage
import jp.juggler.pushreceiverapp.db.SavedAccount
import jp.juggler.pushreceiverapp.db.appDatabase
import jp.juggler.pushreceiverapp.notification.notificationTypeToIconId
import jp.juggler.util.formatTime
import jp.juggler.util.setNavigationBack
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ActMessage : AppCompatActivity() {

    companion object {

        private const val EXTRA_MESSAGE_DB_ID = "messageDbId"

        /**
         * この画面を開くIntentを作成する
         */
        fun Context.intentActMessage(
            messageDbId: Long,
        ) = Intent(this, ActMessage::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            data = "app://message/$messageDbId".toUri()
            putExtra(EXTRA_MESSAGE_DB_ID, messageDbId)
        }
    }

    private val views by lazy {
        ActMessageBinding.inflate(layoutInflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(views.root)
        setSupportActionBar(views.toolbar)
        setNavigationBack(views.toolbar)

        val messageId = intent.getLongExtra(EXTRA_MESSAGE_DB_ID, 0L)

        launchAndShowError {
            val pushMessageAccess = appDatabase.pushMessageAccess()
            val accountAccess = appDatabase.accountAccess()

            // 画面を開いたら項目をdismissする
            pushMessageAccess.dismiss(messageId)

            // loginAcctを調べる
            val loginAcct = pushMessageAccess.find(messageId)?.loginAcct

            // DB項目２つを監視する
            lifecycleScope.launch {
                combine(
                    pushMessageAccess.findFlow(messageId),
                    accountAccess.findFlow(loginAcct),
                    ::Pair
                ).collect { (pm, a) ->
                    showMessage(messageId, pm, a)
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showMessage(
        messageDbId: Long,
        pm: PushMessage?,
        a: SavedAccount?
    ) {
        if (pm == null) {
            showError(getString(R.string.missing_message_of, messageDbId))
            return
        }
        if (a == null) {
            showError(getString(R.string.missing_login_account_of, pm.loginAcct))
            return
        }

        title = getString(R.string.notification_to, pm.loginAcct)

        val iconId = notificationTypeToIconId(pm.messageJson.string("notification_type"))
        Glide.with(views.ivSmall)
            .load(pm.iconSmall)
            .error(iconId)
            .into(views.ivSmall)

        Glide.with(views.ivLarge)
            .load(pm.iconLarge)
            .into(views.ivLarge)

        views.etMessage.setText(
            """
                |loginAcct=${pm.loginAcct}
                |messageShort=${pm.messageShort}
                |messageLong=${pm.messageLong}
                |iconSmall=${pm.iconSmall}
                |iconLarge=${pm.iconLarge}
                |timestamp=${pm.timestamp.formatTime()}
                |timeSave=${pm.timeSave.formatTime()}
                |timeDismiss=${pm.timeDismiss.formatTime()}
                |messageDbId=${pm.messageDbId}
                |messageJson=${pm.messageJson.toString(indentFactor = 1, sort = true)}
                |headerJson=${pm.headerJson.toString(indentFactor = 1, sort = true)}
                |rawBody.size=${pm.rawBody?.size}
            """.trimMargin()
        )
    }

    private fun showError(msg: String) {
        title = getString(R.string.error)
        views.etMessage.setText(msg)
    }
}
