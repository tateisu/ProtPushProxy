package util

import io.ktor.server.application.ApplicationCall

fun String?.isTruth() = when (this?.lowercase()) {
    null, "", "0", "false", "off", "none", "no" -> false
    else -> true
}

val ApplicationCall.log
    get() = application.environment.log

fun Throwable.withCaption(caption: String? = null) = when {
    caption.isNullOrBlank() -> "${javaClass.simpleName} $message"
    else -> "$caption : ${javaClass.simpleName} $message"
}
