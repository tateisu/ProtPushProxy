import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.util.pipeline.PipelineContext
import util.JsonObject
import util.e
import util.jsonObjectOf
import util.log
import util.withCaption

suspend fun JsonObject.respond(
    call: ApplicationCall,
    status: HttpStatusCode = HttpStatusCode.OK,
) = call.respondText(
    contentType = ContentType.Application.Json,
    status = status,
    text = this.toString()
)

suspend fun String.respondError(
    call: ApplicationCall,
    status: HttpStatusCode = HttpStatusCode.InternalServerError,
) = jsonObjectOf("error" to this).respond(call, status)

class GoneError(
    message: String,
    ex: Throwable? = null,
) : IllegalStateException(message, ex)

fun String.gone(): Nothing {
    throw GoneError(this)
}

suspend inline fun PipelineContext<*, ApplicationCall>.jsonApi(
    block: () -> JsonObject,
) {
    try {
        block().respond(call)
    } catch (ex: Throwable) {
        if (ex is GoneError) {
            log.e("gone. ${ex.message}")
            (ex.message ?: "gone").respondError(call, HttpStatusCode.Gone)
        } else {
            log.e(ex, "${call.request.uri} failed.")
            val message = ex.message
            if (!message.isNullOrBlank() && ex.cause == null && ex is IllegalStateException) {
                message.respondError(call)
            } else {
                ex.withCaption("API internal error.").respondError(call)
            }
        }
    }
}
