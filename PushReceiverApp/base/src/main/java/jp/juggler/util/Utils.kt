package jp.juggler.util

import android.content.DialogInterface
import android.net.Uri
import android.util.Base64
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import kotlinx.coroutines.CoroutineScope
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.jvm.isAccessible

fun Throwable.withCaption(caption: String? = null) =
    when {
        caption.isNullOrBlank() -> "${javaClass.simpleName} $message"
        else -> "$caption :${javaClass.simpleName} $message"
    }

fun <T : CharSequence> T?.notEmpty() = if (this.isNullOrEmpty()) null else this
fun <T : CharSequence> T?.notBlank() = if (this.isNullOrBlank()) null else this
fun <T : List<*>> T?.notEmpty() = if (this.isNullOrEmpty()) null else this
// fun <T : Collection<*>> T?.notEmpty() = if (this.isNullOrEmpty()) null else this

fun Int?.notZero() = if (this == null || this == 0) null else this

fun DialogInterface.dismissSafe() {
    try {
        dismiss()
    } catch (ignored: Throwable) {
        // 非同期処理の後などではDialogがWindowTokenを失っている場合があり、IllegalArgumentException がたまに出る
    }
}

fun AppCompatActivity.setNavigationBack(toolbar: Toolbar) =
    toolbar.setNavigationOnClickListener {
        onBackPressedDispatcher.onBackPressed()
    }

fun String.encodePercent(): String = Uri.encode(this)

private val rePercent20 = """%20""".toRegex()

// %HH エンコードした後に %20 を + に変換する
fun String.encodePercentPlus(allow: String? = null): String =
    Uri.encode(this, allow).replace(rePercent20, "+")

fun <T : View> T.vg(v: Boolean): T? = when (v) {
    true -> apply { visibility = View.VISIBLE }
    else -> {
        visibility = View.GONE
        null
    }
}

var View.isEnabledAlpha: Boolean
    get() = isEnabled
    set(enabled) {
        this.isEnabled = enabled
        this.alpha = when (enabled) {
            true -> 1f
            else -> 0.3f
        }
    }

inline fun <reified T : Any> Any?.cast(): T? = (this as? T)

val MEDIA_TYPE_FORM_URL_ENCODED: MediaType =
    "application/x-www-form-urlencoded".toMediaType()

val MEDIA_TYPE_JSON: MediaType =
    "application/json;charset=UTF-8".toMediaType()

fun String.toFormRequestBody() = toRequestBody(MEDIA_TYPE_FORM_URL_ENCODED)

fun JsonObject.toRequestBody(mediaType: MediaType = MEDIA_TYPE_JSON): RequestBody =
    toString().toRequestBody(contentType = mediaType)

fun RequestBody.toPost(): Request.Builder =
    Request.Builder().post(this)

fun RequestBody.toPut(): Request.Builder =
    Request.Builder().put(this)

fun RequestBody.toDelete(): Request.Builder =
    Request.Builder().delete(this)

fun RequestBody.toPatch(): Request.Builder =
    Request.Builder().patch(this)

fun RequestBody.toRequest(methodArg: String): Request.Builder =
    Request.Builder().method(methodArg, this)

fun JsonObject.toPostRequestBuilder(): Request.Builder = toRequestBody().toPost()
fun JsonObject.toPutRequestBuilder(): Request.Builder = toRequestBody().toPut()
fun JsonObject.toDeleteRequestBuilder(): Request.Builder = toRequestBody().toDelete()

object EmptyScope : CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = EmptyCoroutineContext + AppDispatchers.MainImmediate
}

suspend fun Any.invokeSuspendFunction(methodName: String, vararg args: Any?): Any? =
    this::class.memberFunctions.find { it.name == methodName }?.also {
        it.isAccessible = true
        return it.callSuspend(this, *args)
    }

fun String.ellipsize(limit: Int = 128) = when {
    this.length <= limit -> this
    else -> "${substring(0, limit - 1)}…"
}

fun ByteArray.encodeBase64(): String =
    Base64.encodeToString(this, Base64.NO_PADDING or Base64.NO_WRAP)

fun ByteArray.encodeBase64Url(): String =
    Base64.encodeToString(this, Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE)

fun String.decodeBase64(): ByteArray? = try {
    Base64.decode(this, Base64.DEFAULT)
} catch (ex: Throwable) {
    AdbLog.e("decodeBase64 failed. ${ellipsize(128)}")
    null
}

fun String.decodeBase64Url(): ByteArray? = try {
    Base64.decode(this, Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE)
} catch (ex: Throwable) {
    AdbLog.e("decodeBase64Url failed. ${ellipsize(128)}")
    null
}
