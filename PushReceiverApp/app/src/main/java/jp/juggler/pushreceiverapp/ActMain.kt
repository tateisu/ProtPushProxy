package jp.juggler.pushreceiverapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import jp.juggler.pushreceiverapp.ActAccountList.Companion.intentActAccountList
import jp.juggler.pushreceiverapp.ActMessage.Companion.intentActMessage
import jp.juggler.pushreceiverapp.auth.authRepo
import jp.juggler.pushreceiverapp.databinding.ActMainBinding
import jp.juggler.pushreceiverapp.databinding.LvMessageBinding
import jp.juggler.pushreceiverapp.db.PushMessage
import jp.juggler.pushreceiverapp.db.appDatabase
import jp.juggler.pushreceiverapp.dialog.actionsDialog
import jp.juggler.pushreceiverapp.dialog.runInProgress
import jp.juggler.pushreceiverapp.notification.launchAndShowError
import jp.juggler.pushreceiverapp.notification.notificationIconId
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
import jp.juggler.util.encodeBase64Url
import jp.juggler.util.formatTime
import jp.juggler.util.notBlank
import jp.juggler.util.saveToDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.unifiedpush.android.connector.UnifiedPush
import java.io.PrintWriter

class ActMain : AppCompatActivity() {
    companion object {
        fun Context.intentActMain() =
            Intent(this, ActMain::class.java)
    }

    private val views by lazy {
        ActMainBinding.inflate(layoutInflater)
    }

    private val listAdapter = MyAdapter()

    private val layoutManager by lazy {
        LinearLayoutManager(this)
    }

    private val prNotification = permissionSpecNotification.requester()

    private val authRepo by lazy {
        applicationContext.authRepo
    }

    private val pushRepo by lazy {
        applicationContext.pushRepo
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(views.root)
        setSupportActionBar(views.toolbar)

        prNotification.register(this)
        prNotification.checkOrLaunch()

        views.btnAlertTest.setOnClickListener {
            showAlertNotification("this is a test.")
        }
        views.btnManageAccount.setOnClickListener {
            startActivity(intentActAccountList())
        }
        views.btnPushDistributor.setOnClickListener {
            launchAndShowError {
                pushDistributor()
            }
        }

        views.rvMessages.also {
            it.adapter = listAdapter
            it.layoutManager = layoutManager
        }

        lifecycleScope.launch {
            fcmHandler.fcmToken.collect {
                showFcmToken(it)
            }
        }

        lifecycleScope.launch {
            appDatabase.pushMessageAccess().listFlow().collect {
                AdbLog.i("listFlow ${it.javaClass.simpleName}")
                listAdapter.items = it
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
            val a = authRepo.updateAccount(auth2Result)
            showAlertNotification(
                title = a.acct,
                message = getString(R.string.approved),
            )
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

    private suspend fun pushDistributor() {
        val context = this
        val prefDevice = prefDevice
        val lastDistributor = prefDevice.pushDistributor

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

    fun itemActions(pm: PushMessage) {
        launchAndShowError {
            actionsDialog {
                action("詳細") {
                    startActivity(intentActMessage(pm.messageDbId))
                }
                action("再解釈") {
                    pushRepo.reDecode(pm)
                }
                action("エクスポート") {
                    export(pm)
                }
            }
        }
    }

    /**
     * エクスポート、というか端末のダウンロードフォルダに保存する
     */
    private suspend fun export(pm: PushMessage) {
        runInProgress {
            withContext(AppDispatchers.DEFAULT) {
                val a = appDatabase.accountAccess().find(pm.loginAcct)
                    ?: error("missing login account")
                saveToDownload(
                    displayName = "PushMessageDump-${pm.messageDbId}.txt",
                ) { out ->
                    PrintWriter(out).apply {
                        println("messageJson=${pm.messageJson.toString(1,sort = true)}")
                        println("receiverPrivateBytes=${a.pushKeyPrivate?.encodeBase64Url()}")
                        println("receiverPublicBytes=${a.pushKeyPublic?.encodeBase64Url()}")
                        println("senderPublicBytes=${a.pushServerKey?.encodeBase64Url()}")
                        println("authSecret=${a.pushAuthSecret?.encodeBase64Url()}")
                        println("headerJson=${pm.headerJson}")
                        println("rawBody=${pm.rawBody?.encodeBase64Url()}")
                    }.flush()
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private inner class MyViewHolder(
        parent: ViewGroup,
        val views: LvMessageBinding = LvMessageBinding.inflate(layoutInflater, parent, false)
    ) : RecyclerView.ViewHolder(views.root) {

        var lastItem: PushMessage? = null

        init {
            views.root.setOnClickListener { lastItem?.let { itemActions(it) } }
        }

        fun bind(pm: PushMessage?) {
            pm ?: return
            lastItem = pm

            val iconId = pm.notificationIconId()
            Glide.with(views.ivSmall)
                .load(pm.iconSmall)
                .error(iconId)
                .into(views.ivSmall)

            Glide.with(views.ivLarge)
                .load(pm.iconLarge)
                .into(views.ivLarge)

            views.tvText.text = arrayOf<String?>(
                "to ${pm.loginAcct}",
                "when ${pm.timestamp.formatTime()}",
                pm.timeDismiss.takeIf { it > 0L }?.let{"既読 ${it.formatTime()}"},
                pm.messageLong
            ).mapNotNull { it.notBlank() }.joinToString("\n")
        }
    }

    private inner class MyAdapter : RecyclerView.Adapter<MyViewHolder>() {
        var items: List<PushMessage> = emptyList()
            set(value) {
                val oldScrollPos = layoutManager.findFirstVisibleItemPosition()
                    .takeIf { it != RecyclerView.NO_POSITION }
                val oldItems = field
                field = value
                DiffUtil.calculateDiff(
                    object : DiffUtil.Callback() {
                        override fun getOldListSize() = oldItems.size
                        override fun getNewListSize() = value.size

                        override fun areItemsTheSame(
                            oldItemPosition: Int,
                            newItemPosition: Int
                        ) = oldItems[oldItemPosition] == value[newItemPosition]

                        override fun areContentsTheSame(
                            oldItemPosition: Int,
                            newItemPosition: Int
                        ) = false
                    },
                    true
                ).dispatchUpdatesTo(this)
                if (oldScrollPos == 0) {
                    launchAndShowError {
                        delay(50L)
                        views.rvMessages.smoothScrollToPosition(0)
                    }
                }
            }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = MyViewHolder(parent)

        override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
            holder.bind(items.elementAtOrNull(position))
        }
    }
}
