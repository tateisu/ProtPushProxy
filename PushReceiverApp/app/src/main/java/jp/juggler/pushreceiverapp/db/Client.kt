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

@Entity(tableName = Client.TABLE)
data class Client(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = COL_DB_ID)
    var dbId: Long = 0L,
    @ColumnInfo(name = COL_API_HOST)
    var apiHost: String = "",
    @ColumnInfo(name = COL_CLIENT_NAME)
    var clientName: String = "",
    @ColumnInfo(name = COL_CLIENT_JSON)
    var clientJson: JsonObject = JsonObject(),
    @ColumnInfo(name = COL_AUTH_VERSION)
    var authVersion: Int = 0,

    ) {
    companion object {
        const val TABLE = "clients"
        const val COL_DB_ID = "db_id"
        const val COL_API_HOST = "api_host"
        const val COL_CLIENT_NAME = "client_name"
        const val COL_CLIENT_JSON = "client_json"
        const val COL_AUTH_VERSION = "auth_version"

        const val JSON_CLIENT_CREDENTIAL = "clientCredential"
        const val JSON_CLIENT_ID = "clientId"
        const val JSON_CLIENT_SECRET = "clientSecret"
        const val JSON_SCOPE = "scope"
    }
    @Dao
    abstract class Access {
        @Query("SELECT * FROM $TABLE where $COL_CLIENT_NAME=:clientName and $COL_API_HOST=:apiHost")
        abstract suspend fun find(clientName: String, apiHost: String): Client?

        @Insert
        abstract suspend fun insert(a: Client): Long

        @Update
        abstract suspend fun update(vararg a: Client): Int

        @Delete
        abstract suspend fun delete(a: Client): Int

        @Transaction
        open suspend fun save(a: Client): Long {
            when (a.dbId) {
                0L -> a.dbId = insert(a)
                else -> update(a)
            }
            return a.dbId
        }
    }

    var clientId: String?
        get() = clientJson.string(JSON_CLIENT_ID)
        set(value) {
            clientJson[JSON_CLIENT_ID] = value
        }

    var clientSecret: String?
        get() = clientJson.string(JSON_CLIENT_SECRET)
        set(value) {
            clientJson[JSON_CLIENT_SECRET] = value
        }

    var clientCredential: String?
        get() = clientJson.string(JSON_CLIENT_CREDENTIAL)
        set(value) {
            clientJson[JSON_CLIENT_CREDENTIAL] = value
        }

    var scope: String?
        get() = clientJson.string(JSON_SCOPE)
        set(value) {
            clientJson[JSON_SCOPE] = value
        }
}
