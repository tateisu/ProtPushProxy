import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import db.Endpoint
import db.EndpointUsage
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
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import util.Base128.encodeBase128
import util.BrotliUtils
import util.BrotliUtils.compressBrotli
import util.buildJsonObject
import util.decodeBase64
import util.decodeJsonObject
import util.decodeUTF8
import util.e
import util.encodeUTF8
import util.i
import util.jsonObjectOf
import util.notBlank
import util.notEmpty
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

// 暗号のデコードに必要なヘッダ。小文字化
val encryptionHeaders = arrayOf(
    "Content-Encoding",
    "Crypto-Key",
    "Encryption",
).map { it.lowercase() }.toSet()

private val log = LoggerFactory.getLogger("Main")

val config = Config()
val verbose get() = config.verbose

val tables = arrayOf(Endpoint.Meta, EndpointUsage.Meta)

val client = HttpClient(CIO) {
    install(Logging) {
        logger = Logger.DEFAULT
        level = LogLevel.INFO
    }
    install(HttpTimeout) {
        requestTimeoutMillis = TimeUnit.SECONDS.toMillis(20)
        connectTimeoutMillis = TimeUnit.SECONDS.toMillis(20)
        socketTimeoutMillis = TimeUnit.SECONDS.toMillis(20)
    }
}

val sendFcm = SendFcm()
val sendUnifiedPush = SendUnifiedPush(client)

private fun createStatementsX(vararg tables: Table): List<String> {
    if (tables.isEmpty()) return emptyList()

    val toCreate = SchemaUtils.sortTablesByReferences(tables.toList())
    val alters = arrayListOf<String>()
    return toCreate.flatMap { table ->
        val (create, alter) = table.ddl.partition { it.startsWith("CREATE ") }
        val indicesDDL = table.indices.flatMap { SchemaUtils.createIndex(it) }
        alters += alter
        create + indicesDDL
    } + alters
}

fun main(args: Array<String>) {
    val pid = ProcessHandle.current().pid()
    log.i("main start! pid=$pid, pwd=${File(".").canonicalFile}")

    BrotliUtils.requireInitialized()

    config.parseArgs(args)

    config.pidFile.notBlank()?.let {
        File(it).writeText(pid.toString())
    }

    sendFcm.loadCredential(config.fcmCredentialPath)

    val dataSource = HikariConfig().apply {
        driverClassName = config.dbDriver
        jdbcUrl = config.dbUrl
        username = config.dbUser
        password = config.dbPassword

    }.let { HikariDataSource(it) }

    transaction(Database.connect(dataSource)) {
        for (s in createStatementsX(tables = tables)) {
            if (s.isNotBlank()) log.i("SCHEMA: $s")
        }
        SchemaUtils.create(tables = tables)
    }

    val timeJob = launchTimerJob()

    val server = embeddedServer(
        Netty,
        host = config.listenHost,
        port = config.listenPort,
        module = Application::module
    ).start(wait = false)
    Runtime.getRuntime().addShutdownHook(Thread {
        log.i("stop http server…")
        server.stop(
            gracePeriodMillis = 166L,
            timeoutMillis = TimeUnit.SECONDS.toMillis(10),
        )
        log.i("stop timer job…")
        runBlocking {
            timeJob.cancelAndJoin()
        }
        log.i("shutdown complete.")
    })
    Thread.currentThread().join()
}

@OptIn(DelicateCoroutinesApi::class)
fun launchTimerJob() = GlobalScope.launch(Dispatchers.IO) {
    while (true) {
        try {
            delay(100000)
            deleteOldEndpoints()
        } catch (ex: Throwable) {
            if (ex is CancellationException) break
            log.e(ex, "timerJob error")
        }
    }
}

suspend fun deleteOldEndpoints() {
    val usageAccess = EndpointUsage.AccessImpl()
    val endpointAccess = Endpoint.AccessImpl()
    val oldIds = usageAccess.oldIds()
    endpointAccess.deleteIds(oldIds)
    usageAccess.deleteIds(oldIds)
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

                val acctHashList = reqJson.jsonArray("acctHashList")
                    ?.stringList()
                if (acctHashList.isNullOrEmpty()) {
                    error("acctHashList is null or empty")
                }
                buildJsonObject {
                    val map = Endpoint.AccessImpl().upsert(
                        acctHashList = acctHashList,
                        upUrl = upUrl,
                        fcmToken = fcmToken
                    )
                    EndpointUsage.AccessImpl().updateUsage(map.values.toSet())
                    map.entries.forEach { e -> put(e.key, e.value) }
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
                        if (encryptionHeaders.contains(e.key.lowercase())) {
                            e.value.firstOrNull()?.let { put(e.key, it) }
                        }
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
                        val compressed = newBody.compressBrotli()
                        log.i("send ${compressed.size}/${newBody.size} bytes to $upUrl")
                        sendUnifiedPush.send(compressed, upUrl)
                        EndpointUsage.AccessImpl().updateUsage1(appServerHash)
                        jsonObjectOf("result" to "sent to UnifiedPush server.")
                    }

                    fcmToken != null -> withContext(Dispatchers.IO) {
                        sendFcm.send(fcmToken) {
                            val acctHashEncoded = endpoint.acctHash.decodeBase64().compressBrotli().encodeBase128()
                            val headerJsonEncoded =
                                headerJson.toString().encodeUTF8().compressBrotli().encodeBase128()
                            val bodyEncoded = body.compressBrotli().encodeBase128()
                            putData("a", acctHashEncoded)
                            putData("h", headerJsonEncoded)
                            putData("b", bodyEncoded)
                            log.i(
                                "acct.length=${
                                    acctHashEncoded.length
                                }, header.length=${
                                    headerJsonEncoded.length
                                }, body.length=${
                                    bodyEncoded.length
                                } fcmToken=$fcmToken"
                            )
                        }
                        EndpointUsage.AccessImpl().updateUsage1(appServerHash)
                        jsonObjectOf("result" to "sent to FCM. messageId=$it")
                    }

                    else -> "missing redirect destination.".gone()
                }
            }
        }
    }
}
