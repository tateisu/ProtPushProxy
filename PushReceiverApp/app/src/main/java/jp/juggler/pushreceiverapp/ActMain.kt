package jp.juggler.pushreceiverapp

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import jp.juggler.pushreceiverapp.alert.dialogOrAlert
import jp.juggler.pushreceiverapp.alert.launchAndShowError
import jp.juggler.pushreceiverapp.alert.showAlertNotification
import jp.juggler.pushreceiverapp.api.AuthApi
import jp.juggler.pushreceiverapp.api.PushSubscriptionApi
import jp.juggler.pushreceiverapp.auth.AuthRepo
import jp.juggler.pushreceiverapp.databinding.ActMainBinding
import jp.juggler.pushreceiverapp.databinding.LvAccountBinding
import jp.juggler.pushreceiverapp.db.SavedAccount
import jp.juggler.pushreceiverapp.db.appDatabase
import jp.juggler.pushreceiverapp.dialog.actionsDialog
import jp.juggler.pushreceiverapp.dialog.dialogServerHost
import jp.juggler.pushreceiverapp.dialog.runInProgress
import jp.juggler.pushreceiverapp.fcm.FcmHandler
import jp.juggler.pushreceiverapp.permission.permissionSpecNotification
import jp.juggler.pushreceiverapp.permission.requester
import jp.juggler.pushreceiverapp.push.PushRepo
import jp.juggler.util.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.unifiedpush.android.connector.UnifiedPush

class ActMain : AppCompatActivity() {

    private val views by lazy {
        ActMainBinding.inflate(layoutInflater)
    }

    private val prNotification = permissionSpecNotification.requester {
    }

    private val authRepo by lazy {
        AuthRepo(
            api = AuthApi(okHttp = OkHttpClient()),
            clientAccess = appDatabase.clientAccess(),
            accountAccess = appDatabase.accountAccess(),
        )
    }
    val pushRepo by lazy {
        PushRepo(
            api = PushSubscriptionApi(OkHttpClient()),
            accountAccess = appDatabase.accountAccess()
        )
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

        views.lvAccounts.adapter = accountsAdapter
        views.lvAccounts.onItemClickListener = accountsAdapter

        lifecycleScope.launch {
            FcmHandler.fcmToken.collect {
                showStatus()
            }
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
        val fcmToken = FcmHandler.fcmToken.value
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
        actionsDialog {
//            if (FcmHandler.hasFcm) {
//                val lastSelected = false // XXX
//                action("FCM") {
//                    // アプリサーバのエンドポイントがまだないので何もできない
//                }
//            }
            for (packageName in UnifiedPush.getDistributors(context)) {
                val lastSelected = false // XXX
                action("$packageName ${if (lastSelected) " *" else ""}") {
                    runInProgress(cancellable = false) {
                        withContext(AppDispatchers.DEFAULT) {
                            pushRepo.switchDistributor(context, packageName)
                        }
                    }
                }
            }
            action(getString(R.string.none)) {
                runInProgress(cancellable = false) {
                    withContext(AppDispatchers.DEFAULT) {
                        pushRepo.switchDistributor(context, null)
                    }
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
            dialogOrAlert("onItemClick not implemented.")
        }
    }
}
