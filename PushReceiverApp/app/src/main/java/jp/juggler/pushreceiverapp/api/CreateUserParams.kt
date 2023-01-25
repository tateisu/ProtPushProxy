package jp.juggler.pushreceiverapp.api

class CreateUserParams(
    val username: String,
    val email: String,
    val password: String,
    val agreement: Boolean,
    val reason: String?,
)
