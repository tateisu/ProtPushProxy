package jp.juggler.fcm

/**
 * noFcmバージョンは常にnullを返す
 */
class FcmTokenLoader {
    @Suppress("RedundantSuspendModifier")
    suspend fun getToken(): String? = null
}
