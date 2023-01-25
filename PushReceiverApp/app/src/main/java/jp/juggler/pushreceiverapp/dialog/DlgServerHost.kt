package jp.juggler.pushreceiverapp.dialog

import android.app.Activity
import android.app.Dialog
import androidx.core.widget.addTextChangedListener
import jp.juggler.pushreceiverapp.databinding.DlgServerHostBinding
import jp.juggler.util.dismissSafe
import jp.juggler.util.isEnabledAlpha
import jp.juggler.util.vg

class DlgServerHost(
    activity: Activity,
    private val validator: (String) -> String?,
    private val onOk: (String, closer: () -> Unit) -> Unit
) {
    private val views = DlgServerHostBinding.inflate(activity.layoutInflater)
    private val dialog = Dialog(activity)

    init {
        views.btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        views.btnOk.setOnClickListener {
            val text = views.etHost.text?.toString()?.trim() ?: ""
            val error = validator(text)
            if (error != null) return@setOnClickListener
            onOk(text) { dialog.dismissSafe() }
        }
        views.etHost.addTextChangedListener {
            val text = it?.toString()?.trim() ?: ""
            val error = validator(text)
            views.tvError.vg(error != null)?.text = error
            views.btnOk.isEnabledAlpha = error == null
        }

        dialog.setContentView(views.root)
        dialog.show()
    }
}

fun Activity.dialogServerHost(
    validator: (String) -> String?,
    onOk: (String, onComplete: () -> Unit) -> Unit
) {
    DlgServerHost(this, validator, onOk)
}
