import db.AppDatabase
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory.getLogger
import org.slf4j.event.Level
import util.withCaption
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

fun main(args: Array<String>) {
    val log = getLogger("Main")
    config.parseArgs(args)

    AppDatabase.dbUrl = config.dbUrl
    AppDatabase.dbDriver = config.dbDriver
    AppDatabase.dbUser = config.dbUser
    AppDatabase.dbPassword = config.dbPassword

    AppDatabase.initializeSchema()

    // Try adding program arguments via Run/Debug configuration.
    // Learn more about running applications: https://www.jetbrains.com/help/idea/running-applications.html.
    println("Program arguments: ${args.joinToString()}")

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
                val bodyBytes =
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
        post("/register") {
            call.respondText("Hello from routeRegister!", ContentType.Text.Html)
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
