package jp.juggler.pushreceiverapp.db

import androidx.room.TypeConverter
import jp.juggler.util.AdbLog
import jp.juggler.util.JsonObject
import jp.juggler.util.decodeJsonObject

class RoomTypeConverter {
    @TypeConverter
    fun jsonObjectToString(value: JsonObject?) =
        value?.toString()

    @TypeConverter
    fun stringToJsonObject(value: String?) = try {
        value?.decodeJsonObject()
    } catch (ex: Throwable) {
        AdbLog.w(ex, "stringToJsonObject failed.")
        null
    }
}
