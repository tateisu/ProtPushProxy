package jp.juggler.pushreceiverapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import jp.juggler.pushreceiverapp.auth.authRepo
import jp.juggler.pushreceiverapp.databinding.ActAccountListBinding
import jp.juggler.pushreceiverapp.databinding.LvAccountBinding
import jp.juggler.pushreceiverapp.db.SavedAccount
import jp.juggler.pushreceiverapp.dialog.actionsDialog
import jp.juggler.pushreceiverapp.dialog.confirm
import jp.juggler.pushreceiverapp.dialog.dialogServerHost
import jp.juggler.pushreceiverapp.notification.launchAndShowError
import jp.juggler.pushreceiverapp.push.PushBase
import jp.juggler.pushreceiverapp.push.pushRepo
import jp.juggler.util.AdbLog
import jp.juggler.util.cast
import jp.juggler.util.setNavigationBack
import jp.juggler.util.withCaption
import kotlinx.coroutines.launch

class ActAccountList : AppCompatActivity() {
    companion object {
        fun Context.intentActAccountList() =
            Intent(this, ActAccountList::class.java)
    }

    private val views by lazy {
        ActAccountListBinding.inflate(layoutInflater)
    }

    private val authRepo by lazy {
        applicationContext.authRepo
    }

    private val pushRepo by lazy {
        applicationContext.pushRepo
    }

    private val accountsAdapter = MyAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(views.root)
        setSupportActionBar(views.toolbar)
        setNavigationBack(views.toolbar)

        views.btnAddAccount.setOnClickListener {
            addAccount()
        }

        views.lvAccounts.adapter = accountsAdapter
        views.lvAccounts.onItemClickListener = accountsAdapter

        lifecycleScope.launch {
            authRepo.accountListFlow().collect {
                accountsAdapter.items = it
            }
        }
    }

    private fun addAccount() = launchAndShowError {
        @Suppress("RegExpSimplifiable")
        val reAllowedChars = """([^A-Za-z0-9.:_-]+)""".toRegex()
        fun String.badChars() =
            reAllowedChars.findAll(this).map { it.value }.joinToString("")

        dialogServerHost(
            validator = {
                val badChars = it.badChars()
                when {
                    it.isBlank() -> getString(R.string.hostname_empty)
                    badChars.isNotEmpty() -> getString(R.string.incorrect_chars, badChars)
                    else -> null
                }
            }
        ) { hostName, closeHost ->
            launchAndShowError {
                val serverJson = authRepo.serverInfo(hostName)
                val uri = authRepo.authStep1(hostName, serverJson)
                val intent = Intent(Intent.ACTION_VIEW, uri)
                startActivity(intent)
                closeHost()
            }
        }
    }

    private fun accountActions(a: SavedAccount) {
        val activity = this
        launchAndShowError {
            val lines = ArrayList<String>()
            val subLogger = object : PushBase.SubscriptionLogger {
                override val context = this@ActAccountList

                override fun i(msg: String) {
                    AdbLog.w(msg)
                    synchronized(lines) {
                        lines.add(msg)
                    }
                }

                override fun e(msg: String) {
                    AdbLog.e(msg)
                    synchronized(lines) {
                        lines.add(msg)
                    }
                }

                override fun w(ex: Throwable, msg: String) {
                    AdbLog.w(ex, msg)
                    synchronized(lines) {
                        lines.add(ex.withCaption(msg))
                    }
                }

                override fun e(ex: Throwable, msg: String) {
                    AdbLog.e(ex, msg)
                    synchronized(lines) {
                        lines.add(ex.withCaption(msg))
                    }
                }
            }

            actionsDialog {
                action("アクセストークンの更新") {
                    val serverJson = authRepo.serverInfo(a.apiHost)
                    val uri = authRepo.authStep1(a.apiHost, serverJson, forceUpdate = true)
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                action("プッシュ購読の更新") {
                    try {
                        pushRepo.updateSubscription(subLogger, a, willRemoveSubscription = false)
                    } catch (ex: Throwable) {
                        subLogger.e(ex, "updateSubscription failed.")
                    }
                    AlertDialog.Builder(activity)
                        .setMessage("${a.acct}:\n${lines.joinToString("\n")}")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                action("このリストから削除") {
                    confirm(getString(R.string.account_remove_confirm_of, a.acct))
                    try {
                        pushRepo.updateSubscription(subLogger, a, willRemoveSubscription = true)
                    } catch (ex: Throwable) {
                        subLogger.e(ex, "updateSubscription failed.")
                    }
                    authRepo.removeAccount(a)
                }
            }
        }
    }

    private inner class MyViewHolder(parent: ViewGroup?) {
        val views = LvAccountBinding.inflate(layoutInflater, parent, false)
            .also { it.root.tag = this }

        fun bind(item: SavedAccount?) {
            item ?: return
            views.tvText.text = item.toString()
        }
    }

    private inner class MyAdapter : BaseAdapter(), AdapterView.OnItemClickListener {
        var items: List<SavedAccount> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items.elementAtOrNull(position)
        override fun getItemId(position: Int) = items.elementAtOrNull(position)?.dbId ?: 0L
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?) =
            (convertView?.tag?.cast() ?: MyViewHolder(parent))
                .also { it.bind(items.elementAtOrNull(position)) }
                .views.root

        override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            getItem(position)?.let { accountActions(it) }
        }
    }
}