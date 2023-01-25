package db

import db.AppDatabase.dbQuery
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

/**
 * 中継先( endpointUrl または fcmToken )の情報
 */
data class Subscription(
    val id: Long,
    val installIdHash: String,
    val accessTokenHash: String,
    val acctHash: String,
    val endpointUrl: String?,
    val fcmToken: String?,
) {
    object Meta : Table() {
        val id = long("id").autoIncrement()
        val accessTokenHash = text("access_token_hash")
            .index("idx_subscription_access_token_hash")
        val installIdHash = text("title")
            .index("idx_subscription_install_id_hash")
        val acctHash = text("body")
            .index("idx_subscription_acct_hash")
        val endpointUrl = text("endpoint_url").nullable()
        val fcmToken = text("fcm_token").nullable()

        override val primaryKey = PrimaryKey(id)
    }

    interface Access {
        suspend fun list(): List<Subscription>
        suspend fun find(id: Long): Subscription?
        suspend fun add(
            installIdHash: String,
            accessTokenHash: String,
            acctHash: String,
            endpointUrl: String?,
            fcmToken: String?,
        ): Subscription?

        suspend fun edit(
            id: Long,
            installIdHash: String,
            accessTokenHash: String,
            acctHash: String,
            endpointUrl: String?,
            fcmToken: String?,
        ): Int

        suspend fun delete(id: Long): Int
    }

    class AccessImpl : Access {
        private fun resultRowToArticle(row: ResultRow) = Subscription(
            id = row[Meta.id],
            accessTokenHash = row[Meta.accessTokenHash],
            installIdHash = row[Meta.installIdHash],
            acctHash = row[Meta.acctHash],
            endpointUrl = row[Meta.endpointUrl],
            fcmToken = row[Meta.fcmToken],
        )

        override suspend fun list(): List<Subscription> = dbQuery {
            Meta.selectAll().map(::resultRowToArticle)
        }

        override suspend fun find(id: Long): Subscription? = dbQuery {
            Meta.select { Meta.id eq id }
                .map(::resultRowToArticle)
                .singleOrNull()
        }

        override suspend fun add(
            installIdHash: String,
            accessTokenHash: String,
            acctHash: String,
            endpointUrl: String?,
            fcmToken: String?
        ): Subscription? = dbQuery {
            Meta.insert {
                it[Meta.installIdHash] = installIdHash
                it[Meta.accessTokenHash] = accessTokenHash
                it[Meta.acctHash] = acctHash
                it[Meta.endpointUrl] = endpointUrl
                it[Meta.fcmToken] = fcmToken
            }.resultedValues
                ?.singleOrNull()
                ?.let(::resultRowToArticle)
        }

        override suspend fun edit(
            id: Long,
            installIdHash: String,
            accessTokenHash: String,
            acctHash: String,
            endpointUrl: String?,
            fcmToken: String?
        ): Int = dbQuery {
            Meta.update({ Meta.id eq id }) {
                it[Meta.installIdHash] = installIdHash
                it[Meta.accessTokenHash] = accessTokenHash
                it[Meta.acctHash] = acctHash
                it[Meta.endpointUrl] = endpointUrl
                it[Meta.fcmToken] = fcmToken
            }
        }

        override suspend fun delete(id: Long): Int = dbQuery {
            Meta.deleteWhere { Meta.id eq id }
        }
    }
}