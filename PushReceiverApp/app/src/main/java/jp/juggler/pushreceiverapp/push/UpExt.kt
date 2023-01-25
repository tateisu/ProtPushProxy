package jp.juggler.pushreceiverapp.push

import android.content.Context
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.UnifiedPush.getDistributor

fun Context.registerUnifiedPush(
    forceDialog: Boolean = false
) {
    when {
        // 既にプッシュ配信者が選択済み
        // 再利用する
        getDistributor(this).isNotEmpty() && !forceDialog ->
            UnifiedPush.registerApp(this)

        // ダイアログで選択して登録する
        else -> UnifiedPush.registerAppWithDialog(this)
    }
}

fun Context.unregisterUnifiedPush() {
    UnifiedPush.unregisterApp(this)
}
