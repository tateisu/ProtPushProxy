package jp.juggler.fcm

/**
 * noFcmバージョン何もしない
 */
@Suppress("RedundantSuspendModifier")
class FcmTokenLoader {
    suspend fun deleteToken() = Unit
    suspend fun getToken(): String? = null
}
