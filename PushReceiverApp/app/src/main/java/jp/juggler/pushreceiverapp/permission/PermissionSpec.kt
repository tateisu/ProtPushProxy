package jp.juggler.pushreceiverapp.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import jp.juggler.pushreceiverapp.R

class PermissionSpec(
    /**
     * 必要なパーミッションのリスト
     */
    val permissions: List<String>,

    /**
     * 要求が拒否された場合に表示するメッセージのID
     */
    @StringRes val deniedId: Int,

    /**
     * なぜ権限が必要なのか説明するメッセージのID。
     */
    @StringRes val rationalId: Int,
) {
    fun listNotGranted(context: Context) =
        permissions.filter {
            ContextCompat.checkSelfPermission(context, it) !=
                    PackageManager.PERMISSION_GRANTED
        }
}

val permissionSpecNotification = if (Build.VERSION.SDK_INT >= 33) {
    PermissionSpec(
        permissions = listOf(
            Manifest.permission.POST_NOTIFICATIONS,
        ),
        deniedId = R.string.permission_notifications_denied,
        rationalId = R.string.permission_notifications_rational,
    )
} else {
    PermissionSpec(
        permissions = emptyList(),
        deniedId = R.string.permission_notifications_denied,
        rationalId = R.string.permission_notifications_rational,
    )
}
