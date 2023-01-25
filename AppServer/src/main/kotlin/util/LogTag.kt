package util

import io.ktor.server.application.ApplicationCall
import org.slf4j.Logger
import org.slf4j.LoggerFactory.getLogger
import kotlin.reflect.full.companionObject

interface LogTag

inline fun <reified T : LogTag> T.logTag(): Logger =
    getLogger(getLogTagClass(T::class.java))

fun <T : Any> getLogTagClass(javaClass: Class<T>): Class<*> {
    return javaClass.enclosingClass
        ?.takeIf {it.kotlin.companionObject?.java == javaClass }
            ?: javaClass
}

