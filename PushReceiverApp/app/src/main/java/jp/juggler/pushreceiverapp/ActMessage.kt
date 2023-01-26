package jp.juggler.pushreceiverapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import com.bumptech.glide.Glide
import jp.juggler.pushreceiverapp.alert.launchAndShowError
import jp.juggler.pushreceiverapp.databinding.ActMessageBinding
import jp.juggler.pushreceiverapp.db.PushMessage
import jp.juggler.pushreceiverapp.db.appDatabase
import jp.juggler.pushreceiverapp.notification.notificationTypeToIconId
import jp.juggler.util.loadIcon
import jp.juggler.util.notEmpty
import jp.juggler.util.setNavigationBack

class ActMessage : AppCompatActivity() {

    companion object {
        private const val EXTRA_MESSAGE_DB_ID = "messageDbId"
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

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(views.root)
        setSupportActionBar(views.toolbar)
        setNavigationBack(views.toolbar)

        launchAndShowError {
            val pushMessageAccess = appDatabase.pushMessageAccess()
            val accountAccess = appDatabase.accountAccess()

            val messageId = intent.getLongExtra(EXTRA_MESSAGE_DB_ID, 0L)
            val pm = pushMessageAccess.find(messageId)
            if (pm == null) {
                showError(getString(R.string.missing_message_of, messageId))
                return@launchAndShowError
            }
            val a = accountAccess.find(pm.loginAcct)
            if (a == null) {
                showError(getString(R.string.missing_login_account_of, pm.loginAcct))
                return@launchAndShowError
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
                |timestamp=${pm.timestamp}
                |timeSave=${pm.timeSave}
                |timeDismiss=${pm.timeDismiss}
                |messageDbId=${pm.messageDbId}
                |messageJson=${pm.messageJson.toString(indentFactor = 1, sort = true)}
                |headerJson=${pm.headerJson.toString(indentFactor = 1, sort = true)}
                |rawBody.size=${pm.rawBody?.size}
            """.trimMargin()
            )
        }
    }

    private fun showError(msg: String) {
        title = getString(R.string.error)
        views.etMessage.setText(msg)
    }
}
