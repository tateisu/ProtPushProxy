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
import androidx.work.WorkManager
import jp.juggler.pushreceiverapp.ActMessageList.Companion.intentActMessageList
import jp.juggler.pushreceiverapp.auth.authRepo
import jp.juggler.pushreceiverapp.databinding.ActMainBinding
import jp.juggler.pushreceiverapp.databinding.LvAccountBinding
import jp.juggler.pushreceiverapp.db.SavedAccount
import jp.juggler.pushreceiverapp.dialog.actionsDialog
import jp.juggler.pushreceiverapp.dialog.confirm
import jp.juggler.pushreceiverapp.dialog.dialogServerHost
import jp.juggler.pushreceiverapp.dialog.runInProgress
import jp.juggler.pushreceiverapp.notification.launchAndShowError
import jp.juggler.pushreceiverapp.notification.showAlertNotification
import jp.juggler.pushreceiverapp.permission.permissionSpecNotification
import jp.juggler.pushreceiverapp.permission.requester
import jp.juggler.pushreceiverapp.push.PrefDevice
import jp.juggler.pushreceiverapp.push.fcmHandler
import jp.juggler.pushreceiverapp.push.prefDevice
import jp.juggler.pushreceiverapp.push.pushRepo
import jp.juggler.util.AdbLog
import jp.juggler.util.AppDispatchers
import jp.juggler.util.EmptyScope
import jp.juggler.util.cast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
            launchAndShowError {
                pushDistributor()
            }
        }
        views.btnMessageList.setOnClickListener {
            startActivity(intentActMessageList())
        }

        views.lvAccounts.adapter = accountsAdapter
        views.lvAccounts.onItemClickListener = accountsAdapter

        launchAndShowError {
            fcmHandler.fcmToken.collect {
                showFcmToken(it)
            }
        }

        lifecycleScope.launch {
            authRepo.accountListFlow().collect {
                accountsAdapter.items = it
            }
        }

        if (savedInstanceState == null) {
            handleIntent(intent)
        }

        // Workの掃除
        WorkManager.getInstance(this).pruneWork()

        // 定期的にendpointを再登録したい
        pushRepo.launchEndpointRegistration(keepAliveMode = true)

        EmptyScope.launch(AppDispatchers.IO) {
            try {
                pushRepo.sweepOldMessage()
            } catch (ex: Throwable) {
                AdbLog.e(ex, "sweepOldMessage failed.")
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        launchAndShowError {
            AdbLog.i("handleIntent: uri = $uri")
            val auth2Result = authRepo.authStep2(uri)
            authRepo.updateAccount(auth2Result)
            // アカウントを追加/更新したらappServerHashの取得をやりなおす
            when {
                fcmHandler.noFcm && prefDevice.pushDistributor.isNullOrEmpty() -> {
                    try {
                        pushDistributor()
                        // 選択したら
                    } catch (_: CancellationException) {
                        // 選択しなかった場合は購読の更新を行わない
                    }
                }
                else -> pushRepo.launchEndpointRegistration()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showFcmToken(token: String?) {
        views.tvStatus.text = "fcmToken=$token"
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

    private suspend fun pushDistributor() {
        val context = this@ActMain
        val prefDevice = prefDevice
        val lastDistributor = prefDevice.pushDistributor
        val fcmToken = fcmHandler.fcmToken.value

        fun String.appendChecked(checked: Boolean) = when (checked) {
            true -> "$this ✅"
            else -> this
        }

        actionsDialog(
            title = getString(R.string.select_push_delivery_service)
        ) {

            if (fcmHandler.hasFcm) {
                action(
                    getString(R.string.firebase_cloud_messaging)
                        .appendChecked(lastDistributor == PrefDevice.PUSH_DISTRIBUTOR_FCM)
                ) {
                    runInProgress(cancellable = false) { reporter ->
                        withContext(AppDispatchers.DEFAULT) {
                            pushRepo.switchDistributor(
                                PrefDevice.PUSH_DISTRIBUTOR_FCM,
                                reporter = reporter
                            )
                        }
                    }
                }
            }

            for (packageName in UnifiedPush.getDistributors(
                context,
                features = ArrayList(listOf(UnifiedPush.FEATURE_BYTES_MESSAGE))
            )) {
                action(
                    packageName
                        .appendChecked(lastDistributor == packageName)
                ) {
                    runInProgress(cancellable = false) { reporter ->
                        withContext(AppDispatchers.DEFAULT) {
                            pushRepo.switchDistributor(
                                packageName,
                                reporter = reporter
                            )
                        }
                    }
                }
            }
            action(
                getString(R.string.none)
                    .appendChecked(lastDistributor == PrefDevice.PUSH_DISTRIBUTOR_NONE)
            ) {
                runInProgress(cancellable = false) { reporter ->
                    withContext(AppDispatchers.DEFAULT) {
                        pushRepo.switchDistributor(
                            PrefDevice.PUSH_DISTRIBUTOR_NONE,
                            reporter = reporter
                        )
                    }
                }
            }
        }
    }

    private fun accountActions(a: SavedAccount) {
        launchAndShowError {
            actionsDialog {
                action("アクセストークンの更新") {
                    val uri = authRepo.authStep1(a.apiHost, forceUpdate = true)
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                action("このリストから削除") {
                    confirm(getString(R.string.account_remove_confirm_of, a.acct))
                    pushRepo.updateSubscription(a, willRemoveSubscription = true)
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
