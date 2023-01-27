package jp.juggler.pushreceiverapp

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import androidx.appcompat.app.AppCompatActivity
import jp.juggler.pushreceiverapp.ActMessageList.Companion.intentActMessageList
import jp.juggler.pushreceiverapp.alert.launchAndShowError
import jp.juggler.pushreceiverapp.alert.showAlertNotification
import jp.juggler.pushreceiverapp.auth.authRepo
import jp.juggler.pushreceiverapp.databinding.ActMainBinding
import jp.juggler.pushreceiverapp.databinding.LvAccountBinding
import jp.juggler.pushreceiverapp.db.SavedAccount
import jp.juggler.pushreceiverapp.dialog.actionsDialog
import jp.juggler.pushreceiverapp.dialog.confirm
import jp.juggler.pushreceiverapp.dialog.dialogServerHost
import jp.juggler.pushreceiverapp.dialog.runInProgress
import jp.juggler.pushreceiverapp.permission.permissionSpecNotification
import jp.juggler.pushreceiverapp.permission.requester
import jp.juggler.pushreceiverapp.push.PrefDevice
import jp.juggler.pushreceiverapp.push.fcmHandler
import jp.juggler.pushreceiverapp.push.prefDevice
import jp.juggler.pushreceiverapp.push.pushRepo
import jp.juggler.util.*
import kotlinx.coroutines.withContext
import org.unifiedpush.android.connector.UnifiedPush

class ActMain : AppCompatActivity() {

    private val views by lazy {
        ActMainBinding.inflate(layoutInflater)
    }

    private val prNotification = permissionSpecNotification.requester()

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

        prNotification.register(this)
        prNotification.checkOrLaunch()

        views.btnAlertTest.setOnClickListener {
            showAlertNotification("this is a test.")
        }

        views.btnAddAccount.setOnClickListener {
            addAccount()
        }
        views.btnPushDistributor.setOnClickListener {
            pushDistributor()
        }
        views.btnMessageList.setOnClickListener {
            startActivity(intentActMessageList())
        }

        views.lvAccounts.adapter = accountsAdapter
        views.lvAccounts.onItemClickListener = accountsAdapter

        launchAndShowError {
            fcmHandler.fcmToken.collect { showStatus() }
        }

        if (savedInstanceState == null) {
            handleIntent(intent)
        }

        loadAccountList()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    @SuppressLint("SetTextI18n")
    private fun showStatus() {
        val fcmToken = fcmHandler.fcmToken.value
        AdbLog.i("fcmToken=$fcmToken")
        views.tvStatus.text = """
            fcmToken=${fcmToken}
        """.trimIndent()
    }

    private fun addAccount() = launchAndShowError {
        dialogServerHost(
            validator = {
                when {
                    it.isBlank() -> getString(R.string.hostname_empty)
                    else -> null
                }
            }
        ) { hostName, closeHost ->
            launchAndShowError {
                val uri = authRepo.authStep1(hostName)
                val intent = Intent(Intent.ACTION_VIEW, uri)
                startActivity(intent)
                closeHost()
            }
        }
    }

    private fun handleIntent(intent: Intent?) = launchAndShowError {
        val uri = intent?.data ?: return@launchAndShowError
        AdbLog.i("handleIntent: uri = $uri")
        val auth2Result = authRepo.authStep2(uri)
        authRepo.updateAccount(auth2Result)
        loadAccountList()
    }

    private fun loadAccountList() = launchAndShowError {
        accountsAdapter.items = authRepo.accountList()
    }

    private fun pushDistributor() = launchAndShowError {
        val context = this@ActMain
        val prefDevice = context.prefDevice
        val lastDistributor = prefDevice.pushDistributor
        val fcmToken = fcmHandler.fcmToken.value

        fun String.appendChecked(checked: Boolean) = when (checked) {
            true -> "$this ✅"
            else -> this
        }

        actionsDialog {

            if (fcmHandler.hasFcm && !fcmToken.isNullOrEmpty()) {
                val lastSelected = false // XXX
                action("FCM".appendChecked(lastDistributor == PrefDevice.PUSH_DISTRIBUTOR_FCM)) {
                    runInProgress(cancellable = false) {
                        withContext(AppDispatchers.DEFAULT) {
                            pushRepo.switchDistributor(context, fcmToken = fcmToken)
                        }
                    }
                }
            }

            for (packageName in UnifiedPush.getDistributors(
                context,
                features = ArrayList(listOf(UnifiedPush.FEATURE_BYTES_MESSAGE))
            )) {
                action(packageName.appendChecked(lastDistributor == packageName)) {
                    runInProgress(cancellable = false) {
                        withContext(AppDispatchers.DEFAULT) {
                            pushRepo.switchDistributor(context, upPackageName = packageName)
                        }
                    }
                }
            }
            action(getString(R.string.none).appendChecked(lastDistributor == PrefDevice.PUSH_DISTRIBUTOR_NONE)) {
                runInProgress(cancellable = false) {
                    withContext(AppDispatchers.DEFAULT) {
                        pushRepo.switchDistributor(context, null, null)
                    }
                }
            }
        }
    }

    fun accountActions(a: SavedAccount) {
        launchAndShowError {
            actionsDialog {
                action("アクセストークンの更新") {
                    val uri = authRepo.authStep1(a.apiHost, forceUpdate = true)
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                action("このリストから削除") {
                    confirm(getString(R.string.account_remove_conrirm_of, a.acct))
                    authRepo.removeAccount(a)
                    loadAccountList()
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
            getItem(position)?.let{ accountActions(it) }
        }
    }
}
