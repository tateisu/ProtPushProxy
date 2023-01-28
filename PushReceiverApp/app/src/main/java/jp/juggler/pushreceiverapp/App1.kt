package jp.juggler.pushreceiverapp

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.await
import jp.juggler.util.AdbLog
import jp.juggler.util.EmptyScope
import kotlinx.coroutines.launch

class App1 : Application(), Configuration.Provider {
    override fun getWorkManagerConfiguration() =
        Configuration.Builder().apply {
            setMinimumLoggingLevel(
                when {
                    BuildConfig.DEBUG -> Log.WARN
                    else -> Log.WARN
                }
            )
        }.build()

    override fun onCreate() {
        super.onCreate()
        AdbLog.i("onCreate")

        val context = this
        EmptyScope.launch {
            // カスタム getWorkManagerConfiguration() 実装を有効にするため、
            // androidx.startup ではなくApplication作成のタイミングで初期化する
            // 完了済みの仕事を削除する
            WorkManager.getInstance(context)
                .pruneWork().await()
        }
    }
}
