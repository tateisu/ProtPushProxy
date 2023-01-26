package jp.juggler.pushreceiverapp.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import jp.juggler.util.*

@Entity(
    tableName = PushMessage.TABLE,
)
data class PushMessage(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = COL_MESSAGE_DB_ID)
    var messageDbId: Long = 0L,
    @ColumnInfo(name = COL_LOGIN_ACCT)
    var loginAcct: String = "",
    @ColumnInfo(name = COL_MESSAGE_JSON)
    var messageJson: JsonObject = JsonObject(),
    @ColumnInfo(name = COL_TIMESTAMP)
    var timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(name = COL_TIME_SAVE)
    var timeSave: Long = System.currentTimeMillis(),
    @ColumnInfo(name = COL_TIME_DISMISS)
    var timeDismiss: Long = 0L,
    @ColumnInfo(name = COL_MESSAGE_LONG)
    var messageLong: String? = null,
    @ColumnInfo(name = COL_MESSAGE_SHORT)
    var messageShort: String? = null,
    @ColumnInfo(name = COL_ICON_SMALL)
    var iconSmall: String? = null,
    @ColumnInfo(name = COL_ICON_LARGE)
    var iconLarge: String? = null,

    @ColumnInfo(name = COL_HEADER_JSON)
    var headerJson: JsonObject = JsonObject(),
    @ColumnInfo(name = COL_RAW_BODY)
    var rawBody: ByteArray? = null,
) {
    companion object {
        const val TABLE = "push_message"
        const val COL_MESSAGE_DB_ID = "message_db_id"
        const val COL_LOGIN_ACCT = "login_acct"
        const val COL_MESSAGE_JSON = "message_json"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_TIME_SAVE = "time_save"
        const val COL_TIME_DISMISS = "time_dismiss"
        const val COL_MESSAGE_LONG = "message_long"
        const val COL_MESSAGE_SHORT = "message_short"
        const val COL_ICON_SMALL = "icon_small"
        const val COL_ICON_LARGE = "icon_large"
        const val COL_HEADER_JSON = "header_json"
        const val COL_RAW_BODY = "raw_body"

//        const val JSON_CLIENT_CREDENTIAL = "clientCredential"
//        const val JSON_CLIENT_ID = "clientId"
//        const val JSON_CLIENT_SECRET = "clientSecret"
//        const val JSON_SCOPE = "scope"
    }
    @Dao
    abstract class Access {
        @Insert
        abstract suspend fun insert(a: PushMessage): Long

        @Update
        abstract suspend fun update(vararg a: PushMessage): Int

        @Delete
        abstract suspend fun delete(a: PushMessage): Int

        @Transaction
        open suspend fun save(a: PushMessage): Long {
            when (a.messageDbId) {
                0L -> a.messageDbId = insert(a)
                else -> update(a)
            }
            return a.messageDbId
        }

        @Query("select * from $TABLE where $COL_MESSAGE_DB_ID=:messageId")
        abstract suspend fun find(messageId: Long): PushMessage?

        @Query("select * from $TABLE order by $COL_MESSAGE_DB_ID desc")
        abstract suspend fun load(): List<PushMessage>
    }

    override fun hashCode(): Int = messageDbId.hashCode()

    override fun equals(other: Any?) = when {
        this === other -> true
        other !is PushMessage -> false
        messageDbId == other.messageDbId -> true
        else -> false
    }
}
