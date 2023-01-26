import db.AppDatabase
import db.Endpoint
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import util.buildJsonObject
import util.decodeJsonObject
import util.decodeUTF8
import util.e
import util.encodeUTF8
import util.i
import util.jsonObjectOf
import util.log
import util.notEmpty
import util.w
import util.withCaption
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

val config = Config()
val verbose get() = config.verbose

val client = HttpClient(CIO) {
    install(Logging) {
        logger = Logger.DEFAULT
        level = LogLevel.HEADERS
    }
    install(HttpTimeout) {
        requestTimeoutMillis = TimeUnit.SECONDS.toMillis(20)
        connectTimeoutMillis = TimeUnit.SECONDS.toMillis(20)
        socketTimeoutMillis = TimeUnit.SECONDS.toMillis(20)
    }
}

val ignoreHeaders = setOf(
    "Accept-Encoding",
    "Connection",
    "Content-Length",
    "Date",
    "Host",
    "User-Agent",
    "X-Forwarded-For",
    "X-Forwarded-Proto",
    "X-Real-IP",
    "Content-Type",
    "Ttl",
    "Urgency",
)

fun main(args: Array<String>) {
    val pid = ProcessHandle.current().pid()
    val log = LoggerFactory.getLogger("Main")

    config.parseArgs(args)

    File(config.pidFile).writeText(pid.toString())

    AppDatabase.dbUrl = config.dbUrl
    AppDatabase.dbDriver = config.dbDriver
    AppDatabase.dbUser = config.dbUser
    AppDatabase.dbPassword = config.dbPassword

    AppDatabase.initializeSchema()

    log.i("App Start. pid=${pid}, pwd=${File(".").canonicalFile.parent}, try to listen on host=${config.listenHost}, port=${config.listenPort}")
    embeddedServer(
        Netty,
        host = config.listenHost,
        port = config.listenPort,
        module = Application::module
    ).start(wait = true)
}

fun Application.module() {
    install(DoubleReceive) {
    }
    install(CallLogging) {
        level = Level.INFO
    }
    routePing()
    routeClientTest()
    routeRegister()
    routeUnregister()
}

fun Application.routePing() {
    routing {
        get("/ping") {
            call.respondText("Hello from routePing!", ContentType.Text.Html)
        }
    }
}

fun Application.routeClientTest() {
    routing {
        get("/test") {
            try {
                val response = client.get("https://juggler.jp")
                call.respondBytes(
                    status = response.status,
                    contentType = response.contentType(),
                ) { response.body() }
            } catch (ex: Throwable) {
                call.respondText(
                    text = ex.withCaption("proxy error."),
                    contentType = ContentType.Text.Plain,
                    status = HttpStatusCode.InternalServerError,
                )
            }
        }
    }
}

fun Application.routeRegister() {
    routing {
        delete("/endpoint/remove") {
            try {
                val query = call.request.queryParameters
                val upUrl = query["upUrl"]
                val fcmToken = query["fcmToken"]
                val dao = Endpoint.AccessImpl()
                val count = dao.delete(upUrl = upUrl, fcmToken = fcmToken)
                call.respondText(
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK,
                    text = jsonObjectOf("count" to count).toString()
                )
            } catch (ex: Throwable) {
                log.e(ex, "${call.request.uri} failed.")
                call.respondText(
                    text = ex.withCaption("endpoint/remove failed."),
                    contentType = ContentType.Text.Plain,
                    status = HttpStatusCode.InternalServerError,
                )
            }
        }
        post("/endpoint/upsert") {
            try {
                val reqJson = call.receive<ByteArray>().decodeUTF8().decodeJsonObject()

                val upUrl = reqJson.string("upUrl")
                val fcmToken = reqJson.string("fcmToken")
                if ((upUrl == null).xor(fcmToken == null).not()) {
                    error("upUrl and fcmToken must specify one. reqJson=$reqJson")
                }

                val acctHashList = reqJson.jsonArray("acctHashList")?.stringList()
                if (acctHashList.isNullOrEmpty()) {
                    error("acctHashList is null or empty")
                }
                val dao = Endpoint.AccessImpl()
                val json = buildJsonObject {
                    dao.upsert(
                        acctHashList = acctHashList,
                        upUrl = upUrl,
                        fcmToken = fcmToken
                    ).entries.forEach { e ->
                        put(e.key, e.value)
                    }
                }
                jsonObjectOf()
                call.respondText(
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK,
                    text = json.toString()
                )
            } catch (ex: Throwable) {
                log.e(ex, "${call.request.uri} failed.")
                call.respondText(
                    text = ex.withCaption("endpoint/upsert failed."),
                    contentType = ContentType.Text.Plain,
                    status = HttpStatusCode.InternalServerError,
                )
            }
        }
        post("/m/{...}") {
            try {
                val uri = call.request.uri
                log.i("uri=$uri")
                val params = buildMap {
                    uri.substring(3).split("/").forEach { pair ->
                        val cols = pair.split("_", limit = 2)
                        cols.elementAtOrNull(0).notEmpty()?.let { k ->
                            put(k, cols.elementAtOrNull(1) ?: "")
                        }
                    }
                }
                val appServerHash = params["a"]
                    ?: error("missing query parameter 'a'")

                val dao = Endpoint.AccessImpl()
                val endpoint = dao.find(appServerHash)
                if (endpoint == null) {
                    log.w("missing endpoint for appServerHash.")
                    call.respondText(
                        status = HttpStatusCode.Gone,
                        contentType = ContentType.Text.Plain,
                        text = "gone",
                    )
                    return@post
                }

                val headerJson = buildJsonObject {
                    for (e in call.request.headers.entries()) {
                        if (ignoreHeaders.contains(e.key)) continue
                        e.value.firstOrNull()?.let { put(e.key, it) }
                    }
                }

                val body = call.receive<ByteArray>()



                val upUrl = endpoint.upUrl
                val fcmToken = endpoint.fcmToken
                when {
                    upUrl != null -> {
                        val newBody = ByteArrayOutputStream().also {
                            fun ByteArray.writeSection() {
                                val n = size
                                it.write(n.and(255))
                                it.write(n.ushr(8).and(255))
                                it.write(n.ushr(16).and(255))
                                it.write(n.ushr(24).and(255))
                                it.write(this)
                            }
                            endpoint.acctHash.encodeUTF8().writeSection()
                            headerJson.toString().encodeUTF8().writeSection()
                            body.writeSection()
                        }.toByteArray()
                        log.i("sendToUp newBody size=${newBody.size}")
                        val response = client.post(upUrl) { setBody(newBody) }
                        when {
                            response.status.isSuccess() ->
                                call.respondText(
                                    status = HttpStatusCode.OK,
                                    contentType = ContentType.Text.Plain,
                                    text = "ok",
                                )

                            response.status.value in 400 until 500 ->
                                call.respondText(
                                    status = HttpStatusCode.Gone,
                                    contentType = ContentType.Text.Plain,
                                    text = "gone",
                                )

                            else -> error("temporary error? ${response.status}")
                        }
                    }

                    fcmToken != null -> {
                        call.respondText(
                            status = HttpStatusCode.InternalServerError,
                            contentType = ContentType.Text.Plain,
                            text = "not yet implemented",
                        )
                    }

                    else -> {
                        call.respondText(
                            status = HttpStatusCode.Gone,
                            contentType = ContentType.Text.Plain,
                            text = "missing redirect destination.",
                        )
                    }
                }
            } catch (ex: Throwable) {
                log.e(ex, "${call.request.uri} failed.")
                call.respondText(
                    status = HttpStatusCode.InternalServerError,
                    contentType = ContentType.Text.Plain,
                    text = ex.withCaption("can't redirect message."),
                )
            }
        }
    }
}

fun Application.routeUnregister() {
    routing {
        post("/unregister") {
            call.respondText("Hello from routeUnregister!")
        }
    }
}
