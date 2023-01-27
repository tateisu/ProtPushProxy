import db.AppDatabase
import db.Endpoint
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import util.buildJsonObject
import util.decodeJsonObject
import util.decodeUTF8
import util.encodeBase64UrlSafe
import util.encodeUTF8
import util.i
import util.jsonObjectOf
import util.log
import util.notEmpty
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

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

val sendFcm = SendFcm()
val sendUnifiedPush = SendUnifiedPush(client)

fun main(args: Array<String>) {
    val pid = ProcessHandle.current().pid()
    val log = LoggerFactory.getLogger("Main")
    log.i("main start! pid=$pid, pwd=${File(".").canonicalFile.parent}")

    config.parseArgs(args)

    File(config.pidFile).writeText(pid.toString())

    AppDatabase.dbUrl = config.dbUrl
    AppDatabase.dbDriver = config.dbDriver
    AppDatabase.dbUser = config.dbUser
    AppDatabase.dbPassword = config.dbPassword

    sendFcm.loadCredential(config.fcmCredentialPath)

    AppDatabase.initializeSchema()

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
    routing {
        get("/ping") {
            jsonApi {
                jsonObjectOf("ping" to "pong")
            }
        }

        delete("/endpoint/remove") {
            jsonApi {
                val query = call.request.queryParameters
                val upUrl = query["upUrl"]
                val fcmToken = query["fcmToken"]
                val dao = Endpoint.AccessImpl()
                val count = dao.delete(upUrl = upUrl, fcmToken = fcmToken)
                jsonObjectOf("count" to count)
            }
        }

        post("/endpoint/upsert") {
            jsonApi {
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
                buildJsonObject {
                    Endpoint.AccessImpl().upsert(
                        acctHashList = acctHashList,
                        upUrl = upUrl,
                        fcmToken = fcmToken
                    ).entries.forEach { e -> put(e.key, e.value) }
                }
            }
        }

        post("/m/{...}") {
            jsonApi {
                val params = buildMap {
                    call.request.uri.substring(3).split("/").forEach { pair ->
                        val cols = pair.split("_", limit = 2)
                        cols.elementAtOrNull(0).notEmpty()?.let { k ->
                            put(k, cols.elementAtOrNull(1) ?: "")
                        }
                    }
                }
                val appServerHash = params["a"]
                    ?: error("missing json parameter 'a'")

                val dao = Endpoint.AccessImpl()
                val endpoint = dao.find(appServerHash)
                    ?: "missing endpoint for this hash.".gone()

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
                    upUrl != null -> withContext(Dispatchers.IO) {
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
                        log.i("size=${newBody.size} url=${upUrl}")
                        sendUnifiedPush.send(newBody, upUrl)
                    }.let {
                        return@jsonApi jsonObjectOf("result" to "sent to UnifiedPush server.")
                    }

                    fcmToken != null -> withContext(Dispatchers.IO) {
                        sendFcm.send(fcmToken) {
                            var sum = 0
                            putData("ah", endpoint.acctHash.also { sum += it.length })
                            putData("hj", headerJson.toString().also { sum += it.length })
                            putData("b", body.encodeBase64UrlSafe().also { sum += it.length })
                            log.i("size=${sum * 2} fcm")
                        }
                    }.let {
                        return@jsonApi jsonObjectOf("result" to "sent to FCM. messageId=$it")
                    }

                    else -> "missing redirect destination.".gone()
                }
            }
        }
    }
}
