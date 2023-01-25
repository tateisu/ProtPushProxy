package main

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.required
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.*

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

    @Suppress("SpellCheckingInspection")
    val sdf = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        Locale.getDefault()
    )

    val message = Message.builder().apply {
        putData("title", "test")
        putData("text" , "test ${sdf.format(Date())}")
        putData("imageUrl" , "https://m1j.zzz.ac/accounts/avatars/000/000/001/original/0705487e8628acaf.jpg")
//        setNotification(Notification.builder().apply{
//            setTitle("titleNt")
//            setBody("bodyNt")
//            setImage("https://m1j.zzz.ac/accounts/avatars/000/000/001/original/0705487e8628acaf.jpg")
//        }.build())
    }.setToken(fcmDeviceToken).build()

    val response = FirebaseMessaging.getInstance().send(message)
    println("response: $response")
}
