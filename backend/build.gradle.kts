plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    application
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

val ktor = "3.1.3"
val exposed = "0.61.0"

dependencies {
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-server-cors:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("io.ktor:ktor-server-call-logging:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-client-java:$ktor")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor")
    implementation("org.jetbrains.exposed:exposed-core:$exposed")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposed")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("com.github.librepdf:openpdf:1.3.43")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(kotlin("test"))
    testImplementation("org.testcontainers:postgresql:1.21.3")
}

kotlin { jvmToolchain(21) }
// ponytail: замечания в коде до внедрения detekt заморожены в baseline, новые — валят сборку;
// чистить: убрать строку из detekt-baseline.xml и поправить код
detekt { baseline = file("detekt-baseline.xml") }
application { mainClass.set("mkd.ApplicationKt") }
tasks.test {
    dependsOn(tasks.detekt) // detekt по умолчанию проверяет и src/main, и src/test
    useJUnitPlatform()
    // Docker Engine 29 отвечает 400 на версии Docker API ниже 1.44, а docker-java внутри
    // Testcontainers по умолчанию просит более старую — фиксируем минимально поддерживаемую
    systemProperty("api.version", "1.44")
}
dependencyLocking { lockAllConfigurations() }
