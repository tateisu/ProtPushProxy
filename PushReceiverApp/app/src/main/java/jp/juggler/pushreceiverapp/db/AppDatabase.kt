package jp.juggler.pushreceiverapp.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.startup.AppInitializer
import androidx.startup.Initializer

@Database(
    exportSchema = true,
    version = 2,
    entities = [
        Client::class,
        SavedAccount::class,
    ],
    autoMigrations = [
    ],
)

@TypeConverters(RoomTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountAccess(): SavedAccount.Access
    abstract fun clientAccess(): Client.Access
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("alter table ${SavedAccount.TABLE} add column ${SavedAccount.COL_PUSH_KEY_PRIVATE} blob")
        database.execSQL("alter table ${SavedAccount.TABLE} add column ${SavedAccount.COL_PUSH_KEY_PUBLIC} blob")
        database.execSQL("alter table ${SavedAccount.TABLE} add column ${SavedAccount.COL_PUSH_AUTH_SECRET} blob")
        database.execSQL("alter table ${SavedAccount.TABLE} add column ${SavedAccount.COL_PUSH_SERVER_KEY} blob")
    }
}

/**
 * AndroidManifest.xml で androidx.startup.InitializationProvider から参照される
 */
@Suppress("unused")
class AppDatabaseInitializer : Initializer<AppDatabase> {
    override fun dependencies(): List<Class<out Initializer<*>>> =
        emptyList()

    override fun create(context: Context): AppDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "app_db"
        ) .addMigrations(
            MIGRATION_1_2,
        ).build()
    }
}

val Context.appDatabase: AppDatabase
    get() = AppInitializer.getInstance(this)
        .initializeComponent(AppDatabaseInitializer::class.java)
