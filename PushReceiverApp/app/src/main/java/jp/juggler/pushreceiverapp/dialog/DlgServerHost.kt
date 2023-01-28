package jp.juggler.pushreceiverapp.dialog

import android.app.Dialog
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import jp.juggler.pushreceiverapp.databinding.DlgServerHostBinding
import jp.juggler.pushreceiverapp.notification.launchAndShowError
import jp.juggler.util.dismissSafe
import jp.juggler.util.isEnabledAlpha
import jp.juggler.util.vg

class DlgServerHost(
    private val activity: AppCompatActivity,
    private val validator: (String) -> String?,
    private val onOk: (String, closer: () -> Unit) -> Unit
) {
    private val views = DlgServerHostBinding.inflate(activity.layoutInflater)
    private val dialog = Dialog(activity)

    init {
        views.btnCancel.setOnClickListener {
            dialog.dismissSafe()
        }
        views.btnOk.setOnClickListener {
            validatedText()?.let {
                onOk(it) { dialog.dismissSafe() }
            }
        }
        views.etHost.addTextChangedListener {
            validatedText(it?.toString())
        }

        views.etHost.setOnEditorActionListener { _, actionId, _ ->
            when (actionId) {
                EditorInfo.IME_ACTION_DONE -> {
                    views.btnOk.performClick()
                    true
                }
                else -> false
            }
        }
        views.btnPresets.setOnClickListener { presetsDialog() }

        validatedText()

        dialog.setContentView(views.root)
        dialog.show()
    }

    /**
     * 入力内容を検査して、問題なければ内容を返す
     * 問題があればnull
     *
     * 副作用：エラー表示を更新する
     */
    private fun validatedText(
        src: String? = views.etHost.text?.toString()
    ): String? {
        val text = src?.trim() ?: ""
        val error = validator(text)
        views.tvError.vg(error != null)?.text = error
        views.btnOk.isEnabledAlpha = error == null
        return when (error) {
            null -> text
            else -> null
        }
    }

    private fun presetsDialog() = activity.launchAndShowError {
        activity.actionsDialog {
            arrayOf(
                "mastodon.juggler.jp",
                "drdr.club",
                "misskey.m544.net",
            ).forEach {
                action(it) {
                    views.etHost.setText(it)
                    validatedText()
                }
            }
        }
    }
}

fun AppCompatActivity.dialogServerHost(
    validator: (String) -> String?,
    onOk: (String, onComplete: () -> Unit) -> Unit
) {
    DlgServerHost(this, validator, onOk)
}
