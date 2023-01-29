package main

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.required
import java.io.File
import java.io.FileInputStream

// implementation "com.google.firebase:firebase-admin:8.2.0"
// implementation "org.jetbrains.kotlinx:kotlinx-cli:0.3.4"

fun main(args: Array<String>) {
    val parser = ArgParser("FcmV1Sender")

    val credentialPath by parser.option(
        ArgType.String,
        shortName = "c",
        description = "path to credentials.json"
    ).required()

    val fcmDeviceToken by parser.option(
        ArgType.String,
        shortName = "t",
        description = "FCM device token"
    ).required()

    parser.parse(args)

    val credentialFile = File(credentialPath)
    println("credentialFile=${credentialFile.canonicalPath}")

    val credentials = FileInputStream(credentialFile).use {
        GoogleCredentials.fromStream(it)
    }

    val options = FirebaseOptions.builder()
        .setCredentials(credentials)
        .build()
    FirebaseApp.initializeApp(options)


    fun canSend(part: String, count: Int) = try {
        val message = Message.builder().apply {
            putData("k", part.repeat(count))
//        putData("imageUrl" , "https://m1j.zzz.ac/accounts/avatars/000/000/001/original/0705487e8628acaf.jpg")
//        setNotification(Notification.builder().apply{
//            setTitle("titleNt")
//            setBody("bodyNt")
//            setImage("https://m1j.zzz.ac/accounts/avatars/000/000/001/original/0705487e8628acaf.jpg")
//        }.build())
        }.setToken(fcmDeviceToken).build()

        val response = FirebaseMessaging.getInstance().send(message)
       // println("response: $response")
        true
    } catch (ex: Throwable) {
        when {
            ex.message?.contains("Android message is too big") == true -> false
            else -> throw ex
        }
    }

    arrayOf(
        "a","\u0000","\n",
        "\u00a9",
        "花",
    ).forEach { c ->
        var min = 1000
        var max = 4100
        val list = ArrayList<Int>()
        while (true) {
            val width = max - min
            if (width <= 0) break
            val mid = (min + max) / 2
            // println("search mid=$mid width=$width")
            val canSend = canSend(c, mid)
            if (canSend) {
                list.add(mid)
                min = mid + 1
            } else {
                max = mid - 1
            }
        }
        println("n=${list.maxOrNull()} c=${c.escape()} ${c.toByteArray(Charsets.UTF_8).size}bytes")
    }
}

val reEscapeRequired = """[^\w]""".toRegex()
fun String.escape() =
    reEscapeRequired.replace(this) {
        "\\u%04x".format(it.value.first().code)
    }
