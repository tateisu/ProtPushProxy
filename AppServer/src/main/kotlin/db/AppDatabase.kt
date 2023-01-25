package db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

object AppDatabase {

    var dbUrl =  "jdbc:h2:file:/database"
    var dbDriver = "org.h2.Driver"
    var dbUser = ""
    var dbPassword = ""

    fun initializeSchema() {
        val database = Database.connect(
            url= dbUrl,
            driver = dbDriver,
            user = dbUser,
            password = dbPassword,
        )
        transaction(database) {
            SchemaUtils.create(Subscription.Meta)
        }
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
