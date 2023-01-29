package jp.juggler.util

import android.app.ActivityManager
import android.content.Context
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.codec.binary.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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

fun <T : CharSequence> T?.notBlank() = if (this.isNullOrBlank()) null else this
fun <T : CharSequence> T?.notEmpty() = if (this.isNullOrEmpty()) null else this
fun <T : List<*>> T?.notEmpty() = if (this.isNullOrEmpty()) null else this
fun ByteArray?.notEmpty() = if (this == null || this.isEmpty()) null else this
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
    Base64.encodeBase64String(this)

fun ByteArray.encodeBase64Url(): String =
    Base64.encodeBase64URLSafeString(this)

fun String.decodeBase64(): ByteArray =
    Base64.decodeBase64(this)!!

fun ByteArray.digestSHA256(): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.reset()
    return digest.digest(this)
}

fun String.encodeUTF8() = toByteArray(StandardCharsets.UTF_8)

fun ByteArray.decodeUTF8() = toString(StandardCharsets.UTF_8)

@OptIn(ExperimentalCoroutinesApi::class)
suspend fun Context.loadIcon(url: String?, size: Int? = null): Bitmap? = try {
    suspendCancellableCoroutine { cont ->
        @Suppress("ThrowableNotThrown")
        val target = object : CustomTarget<Bitmap>() {
            override fun onLoadFailed(errorDrawable: Drawable?) {
                if (cont.isActive) cont.resume(null) {}
                if (!url.isNullOrEmpty()) AdbLog.w("onLoadFailed. url=$url")
            }

            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                if (cont.isActive) cont.resume(resource) { resource.recycle() }
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                if (cont.isActive) cont.resume(null) {}
                if (!url.isNullOrEmpty()) AdbLog.w("onLoadCleared. url=$url")
            }
        }
        Glide.with(this)
            .asBitmap()
            .load(url)
            .apply {
                @Suppress("CheckResult")
                size?.let { override(it) }
            }
            .into(target)
        cont.invokeOnCancellation {
            Glide.with(this).clear(target)
        }
    }
} catch (ex: Throwable) {
    AdbLog.w(ex, "url=$url")
    null
}

fun String.parseTime() = if (isBlank()) {
    null
} else try {
    Instant.parse(this)
} catch (ex: Throwable) {
    AdbLog.w("parseTime failed. $this")
    null
}?.toEpochMilliseconds()

fun Long.formatTime(): String {
    val tz = TimeZone.currentSystemDefault()
    val lt = Instant.fromEpochMilliseconds(this).toLocalDateTime(tz)
    return "%d/%02d/%02d %02d:%02d:%02d.%03d".format(
        lt.year,
        lt.monthNumber,
        lt.dayOfMonth,
        lt.hour,
        lt.minute,
        lt.second,
        lt.nanosecond / 1_000_000,
    )
}

private val importanceMap = listOf(
    100 to "IMPORTANCE_FOREGROUND",
    125 to "IMPORTANCE_FOREGROUND_SERVICE",
    130 to "IMPORTANCE_PERCEPTIBLE_PRE_26",
    150 to "IMPORTANCE_TOP_SLEEPING_PRE_28",
    170 to "IMPORTANCE_CANT_SAVE_STATE_PRE_26",
    200 to "IMPORTANCE_VISIBLE",
    230 to "IMPORTANCE_PERCEPTIBLE",
    300 to "IMPORTANCE_SERVICE",
    325 to "IMPORTANCE_TOP_SLEEPING",
    350 to "IMPORTANCE_CANT_SAVE_STATE",
    400 to "IMPORTANCE_CACHED",
    500 to "IMPORTANCE_EMPTY",
    1000 to "IMPORTANCE_GONE",
)

fun importanceString(n: Int): String =
    importanceMap.firstOrNull { it.first >= n }?.second ?: "(not found)"

fun checkAppForeground(caption: String) {
    val appProcessInfo = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(appProcessInfo)
    when (val importance = appProcessInfo.importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> {
            AdbLog.i("$caption: app is foreground. $importance ${importanceString(importance)} thread=${Thread.currentThread().name}")
        }
        else -> {
            AdbLog.w("$caption: app is background. $importance ${importanceString(importance)} thread=${Thread.currentThread().name}")
        }
    }
}
