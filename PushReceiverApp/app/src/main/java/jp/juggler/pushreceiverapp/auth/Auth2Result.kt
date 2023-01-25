package jp.juggler.pushreceiverapp.auth

import jp.juggler.util.*

data class Auth2Result(
    val apiHost: String,
    val apDomain: String,
    val userName: String,
    val serverJson: JsonObject,
    val tokenJson: JsonObject,
    val accountJson: JsonObject,
)
