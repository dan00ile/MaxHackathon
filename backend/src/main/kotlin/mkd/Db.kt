package mkd

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

object Db {
    fun init(cfg: Config) {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = cfg.dbUrl
            username = cfg.dbUser
            password = cfg.dbPassword
            maximumPoolSize = 5
        }
        val ds = HikariDataSource(hikariConfig)
        Database.connect(ds)
        // createMissingTablesAndColumns, а не create: на уже поднятом стенде нужно доливать новые колонки
        transaction { SchemaUtils.createMissingTablesAndColumns(*allTables) }
    }
}

suspend fun <T> tx(block: suspend Transaction.() -> T): T =
    newSuspendedTransaction(Dispatchers.IO) { block() }

// вызывать только внутри tx { }
fun logEvent(actId: Long?, type: String, actorId: Long?, details: String = "") {
    Events.insert {
        it[Events.actId] = actId
        it[Events.type] = type
        it[Events.actorId] = actorId
        it[Events.at] = Instant.now()
        it[Events.details] = details
    }
}
