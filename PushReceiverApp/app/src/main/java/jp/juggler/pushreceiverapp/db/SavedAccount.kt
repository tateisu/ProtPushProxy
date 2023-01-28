package jp.juggler.pushreceiverapp.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import jp.juggler.pushreceiverapp.auth.AuthRepo
import jp.juggler.util.*
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = SavedAccount.TABLE,
    indices = [
        Index(
            value = [SavedAccount.COL_USER_NAME, SavedAccount.COL_AP_DOMAIN],
            unique = true,
        )
    ]
)
data class SavedAccount(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = COL_DB_ID)
    var dbId: Long = 0L,
    @ColumnInfo(name = COL_API_HOST)
    var apiHost: String = "",
    @ColumnInfo(name = COL_AP_DOMAIN)
    var apDomain: String = "",
    @ColumnInfo(name = COL_USER_NAME)
    var userName: String = "",
    @ColumnInfo(name = COL_TOKEN_JSON)
    var tokenJson: JsonObject = JsonObject(),
    @ColumnInfo(name = COL_ACCOUNT_JSON)
    var accountJson: JsonObject = JsonObject(),
    @ColumnInfo(name = COL_SERVER_JSON)
    var serverJson: JsonObject = JsonObject(),
    @ColumnInfo(name = COL_PUSH_KEY_PRIVATE)
    var pushKeyPrivate: ByteArray? = null,
    @ColumnInfo(name = COL_PUSH_KEY_PUBLIC)
    var pushKeyPublic: ByteArray? = null,
    @ColumnInfo(name = COL_PUSH_AUTH_SECRET)
    var pushAuthSecret: ByteArray? = null,
    @ColumnInfo(name = COL_PUSH_SERVER_KEY)
    var pushServerKey: ByteArray? = null,
    @ColumnInfo(name = COL_APP_SERVER_HASH)
    var appServerHash: String? = null,
) {
    companion object {
        const val TABLE = "saved_account"
        const val COL_DB_ID = "db_id"
        const val COL_API_HOST = "api_host"
        const val COL_AP_DOMAIN = "ap_domain"
        const val COL_USER_NAME = "user_name"
        const val COL_TOKEN_JSON = "token_json"
        const val COL_ACCOUNT_JSON = "account_json"
        const val COL_SERVER_JSON = "server_json"
        const val COL_PUSH_KEY_PRIVATE = "push_key_private"
        const val COL_PUSH_KEY_PUBLIC = "push_key_public"
        const val COL_PUSH_AUTH_SECRET = "push_auth_secret"
        const val COL_PUSH_SERVER_KEY = "push_server_key"
        const val COL_APP_SERVER_HASH = "app_server_hash"
    }
    @Dao
    abstract class Access {
        @Query("SELECT * FROM $TABLE order by $COL_USER_NAME, $COL_AP_DOMAIN")
        abstract suspend fun load(): List<SavedAccount>

        @Query("SELECT * FROM $TABLE order by $COL_USER_NAME, $COL_AP_DOMAIN")
        abstract fun listFlow(): Flow<List<SavedAccount>>

        @Query("SELECT * FROM $TABLE where $COL_DB_ID=:dbId")
        abstract suspend fun find(dbId: Long): SavedAccount?

        @Query("SELECT * FROM $TABLE where $COL_USER_NAME=:userName and $COL_AP_DOMAIN=:apDomain")
        abstract suspend fun find(userName: String, apDomain: String): SavedAccount?
        @Query("SELECT * FROM $TABLE where $COL_USER_NAME=:userName and $COL_AP_DOMAIN=:apDomain")
        abstract fun findFlow(userName: String, apDomain: String): Flow<SavedAccount?>

        open suspend fun find(acct: String): SavedAccount? {
            val cols = acct.split("@", limit = 2)
            return find(
                cols.elementAtOrNull(0) ?: "",
                cols.elementAtOrNull(1) ?: "",
            )
        }

        fun findFlow(acct: String?): Flow<SavedAccount?> {
            val cols = acct?.split("@", limit = 2)
            return findFlow(
                cols?.elementAtOrNull(0) ?: "",
                cols?.elementAtOrNull(1) ?: "",
            )
        }

        @Insert
        abstract suspend fun insert(a: SavedAccount): Long

        @Update
        abstract suspend fun update(vararg a: SavedAccount): Int

        @Delete
        abstract suspend fun delete(a: SavedAccount): Int

        @Transaction
        open suspend fun save(a: SavedAccount): Long {
            when (a.dbId) {
                0L -> a.dbId = insert(a)
                else -> update(a)
            }
            return a.dbId
        }

    }

    val acct: String
        get() = "$userName@$apDomain"

    val accessToken: String?
        get() = tokenJson.string(AuthRepo.JSON_ACCESS_TOKEN)

    override fun toString() = acct

    override fun equals(other: Any?): Boolean {
        return other is SavedAccount &&
                userName == other.userName &&
                apDomain == other.apDomain
    }

    override fun hashCode(): Int {
        return userName.hashCode() * 31 + apDomain.hashCode()
    }
}
