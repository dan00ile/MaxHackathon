package mkd

import java.time.ZoneId

data class Config(
    val port: Int,
    val dbUrl: String, val dbUser: String, val dbPassword: String,
    val maxToken: String, val maxApiBase: String,
    val gigaAuthKey: String, val gigaScope: String, val gigaModel: String,
    val corsOrigin: String,          // https://<user>.github.io
    val adminUserIds: Set<Long>,
    val demoMode: Boolean,
    val devAuth: Boolean,
    val filesDir: String,
    val zone: ZoneId,
) {
    companion object {
        fun fromEnv(): Config {
            fun env(name: String, default: String) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
            return Config(
                port = env("PORT", "8080").toInt(),
                dbUrl = env("DB_URL", "jdbc:postgresql://localhost:5432/mkd"),
                dbUser = env("DB_USER", "mkd"),
                dbPassword = env("DB_PASSWORD", "mkd"),
                maxToken = env("MAX_BOT_TOKEN", ""),
                maxApiBase = env("MAX_API_BASE", "https://platform-api2.max.ru"),
                gigaAuthKey = env("GIGACHAT_AUTH_KEY", ""),
                gigaScope = env("GIGACHAT_SCOPE", "GIGACHAT_API_PERS"),
                gigaModel = env("GIGACHAT_MODEL", "GigaChat-2-Max"),
                corsOrigin = env("CORS_ORIGIN", "http://localhost:5500"),
                adminUserIds = env("ADMIN_USER_IDS", "").split(",")
                    .map { it.trim() }.filter { it.isNotEmpty() }.map { it.toLong() }.toSet(),
                demoMode = env("DEMO_MODE", "true").toBoolean(),
                devAuth = env("DEV_AUTH", "false").toBoolean(),
                filesDir = env("FILES_DIR", "./data/files"),
                zone = ZoneId.of(env("TZ_ZONE", "Europe/Moscow")),
            )
        }
    }
}
