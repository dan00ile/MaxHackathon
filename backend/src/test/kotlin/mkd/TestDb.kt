package mkd

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

// Один Postgres (та же версия, что в compose.yaml) на все тесты, которые работают с настоящей схемой:
// внешние ключи, каскады и типы ведут себя как на стенде. Поднимается лениво и переиспользуется,
// поэтому новый тестовый класс не стоит ещё одного контейнера.
private val postgres by lazy {
    PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine")).apply { start() }
}

val testDb: Database by lazy {
    Database.connect(postgres.jdbcUrl, user = postgres.username, password = postgres.password)
        .also { db -> transaction(db) { SchemaUtils.create(*allTables) } }
}
