package db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import javax.sql.DataSource

object AppDatabase {

    var dbUrl = "jdbc:h2:file:/database"
    var dbDriver = "org.h2.Driver"
    var dbUser = ""
    var dbPassword = ""

    private val dataSource: DataSource by lazy {
        val config = HikariConfig()
        config.driverClassName = dbDriver
        config.jdbcUrl = dbUrl
        config.username = dbUser
        config.password = dbPassword
        HikariDataSource(config)
    }

    fun initializeSchema() {
        transaction(Database.connect(dataSource)) {
            SchemaUtils.create(Endpoint.Meta)
        }
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
