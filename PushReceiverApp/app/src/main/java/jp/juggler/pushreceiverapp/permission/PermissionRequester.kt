package jp.juggler.pushreceiverapp.permission

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import jp.juggler.pushreceiverapp.R
import jp.juggler.pushreceiverapp.notification.showError
import jp.juggler.util.AdbLog
import jp.juggler.util.dismissSafe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException

/**
 * ActivityResultLauncherを使ってパーミッション要求とその結果の処理を行う
 */
class PermissionRequester(
    /**
     * 権限の詳細
     */
    private val spec: PermissionSpec,
    /**
     * 権限が与えられた際に処理を再開するラムダ
     * - ラムダの引数にこのPermissionRequester自身が渡される
     */
    private val onGrant: ((PermissionRequester) -> Unit)? = null,
) : ActivityResultCallback<Map<String, Boolean>> {

    private var launcher: ActivityResultLauncher<Array<String>>? = null

    private var getContext: (() -> Context?)? = null

    private val activity
        get() = getContext?.invoke() as? FragmentActivity

    // ActivityのonCreate()から呼び出す
    fun register(activity: FragmentActivity) {
        getContext = { activity }
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
            this,
        )
    }

    // FragmentのonCreate()から呼び出す
    fun register(fragment: Fragment) {
        getContext = { fragment.activity ?: fragment.context }
        launcher = fragment.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
            this,
        )
    }

    /**
     * 実行時権限が全て揃っているならtrueを返す
     * そうでなければ権限の要求を行い、falseを返す
     */
    fun checkOrLaunch(): Boolean {
        val activity = activity ?: error("missing activity.")

        val listNotGranted = spec.listNotGranted(activity)

        if (listNotGranted.isEmpty()) {
            AdbLog.i("checkOrLaunch: already has permissions: ${spec.permissions.joinToString(",")}")
            return true
        }

        activity.lifecycleScope.launch {
            try {
                val shouldShowRational = listNotGranted.any {
                    shouldShowRequestPermissionRationale(activity, it)
                }

                if (shouldShowRational && spec.rationalId != 0) {
                    suspendCancellableCoroutine { cont ->
                        AlertDialog.Builder(activity)
                            .setMessage(spec.rationalId)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                if (cont.isActive) cont.resumeWith(Result.success(Unit))
                            }
                            .setOnDismissListener {
                                if (cont.isActive) cont.resumeWithException(CancellationException())
                            }
                            .create()
                            .also { dialog -> cont.invokeOnCancellation { dialog.dismissSafe() } }
                            .show()
                    }
                }
                when (val launcher = launcher) {
                    null -> error("launcher not registered.")
                    else -> launcher.launch(listNotGranted.toTypedArray())
                }
            } catch (ex: Throwable) {
                if (ex !is CancellationException) {
                    activity.showError(ex, "can't request permissions.")
                }
            }
        }
        return false
    }

    /**
     * 権限要求の結果を処理する
     * @param result 「パーミッション名」と「それが許可されているなら真」のマップ
     */
    override fun onActivityResult(result: Map<String, Boolean>?) {
        try {
            result ?: error("missing result.")
            val listNotGranted = result.entries.filter { !it.value }.map { it.key }
            if (listNotGranted.isEmpty()) {
                // すべて許可されている
                onGrant?.invoke(this)
                return
            }
            // 許可されなかった。
            val activity = activity ?: error("missing activity.")
            AlertDialog.Builder(activity)
                .setMessage(spec.deniedId)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.setting) { _, _ ->
                    openAppSetting(activity)
                }
                .show()
        } catch (ex: Throwable) {
            AdbLog.w(ex, "onActivityResult: can't handle result.")
        }
    }

    private fun openAppSetting(activity: FragmentActivity) {
        try {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:${activity.packageName}".toUri()
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }.let { activity.startActivity(it) }
        } catch (ex: Throwable) {
            activity.showError(ex, "openAppSetting failed.")
        }
    }
}

fun PermissionSpec.requester(onGrant: ((PermissionRequester) -> Unit)? = null) =
    PermissionRequester(this, onGrant)
