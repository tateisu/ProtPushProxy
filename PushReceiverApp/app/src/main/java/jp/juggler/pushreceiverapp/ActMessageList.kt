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
import com.bumptech.glide.Glide
import jp.juggler.pushreceiverapp.ActMessage.Companion.intentActMessage
import jp.juggler.pushreceiverapp.databinding.ActMessageListBinding
import jp.juggler.pushreceiverapp.databinding.LvMessageBinding
import jp.juggler.pushreceiverapp.db.PushMessage
import jp.juggler.pushreceiverapp.db.appDatabase
import jp.juggler.pushreceiverapp.dialog.actionsDialog
import jp.juggler.pushreceiverapp.dialog.runInProgress
import jp.juggler.pushreceiverapp.notification.launchAndShowError
import jp.juggler.pushreceiverapp.notification.notificationTypeToIconId
import jp.juggler.pushreceiverapp.push.pushRepo
import jp.juggler.util.AdbLog
import jp.juggler.util.AppDispatchers
import jp.juggler.util.encodeBase64Url
import jp.juggler.util.formatTime
import jp.juggler.util.saveToDownload
import jp.juggler.util.setNavigationBack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.PrintWriter

class ActMessageList : AppCompatActivity() {
    companion object {
        fun Context.intentActMessageList() =
            Intent(this, ActMessageList::class.java)
    }

    private val views by lazy {
        ActMessageListBinding.inflate(layoutInflater)
    }
    private val listAdapter = MyAdapter()

    private val layoutManager by lazy {
        LinearLayoutManager(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(views.root)
        setSupportActionBar(views.toolbar)
        setNavigationBack(views.toolbar)

        views.rvMessages.also {
            it.adapter = listAdapter
            it.layoutManager = layoutManager
        }

        lifecycleScope.launch {
            appDatabase.pushMessageAccess().listFlow().collect {
                AdbLog.i("listFlow ${it.javaClass.simpleName}")
                listAdapter.items = it
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

            val iconId = notificationTypeToIconId(pm.messageJson.string("notification_type"))
            Glide.with(views.ivSmall)
                .load(pm.iconSmall)
                .error(iconId)
                .into(views.ivSmall)

            Glide.with(views.ivLarge)
                .load(pm.iconLarge)
                .into(views.ivLarge)

            views.tvText.text = """
                |loginAcct=${pm.loginAcct}
                |timestamp=${pm.timestamp.formatTime()}
                |timeDismiss=${pm.timeDismiss.takeIf { it > 0L }?.formatTime() ?: ""}
                |messageLong=${pm.messageLong}
            """.trimMargin()
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
