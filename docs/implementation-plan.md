# План реализации MVP (Must-путь)

> Этот документ — пошаговая инструкция для **исполнителя** (модели или
> человека, который пишет код). Архитектурные решения здесь уже приняты: не
> пересматривай их и не добавляй слоёв «на будущее». Если шаг нельзя
> выполнить так, как описано, — остановись и задай вопрос человеку, не
> импровизируй с архитектурой.
>
> Источники: [build-brief.md](build-brief.md) (стек),
> [requirements.md](requirements.md) (FR/NFR, §5 машина состояний, §6 модель
> данных), [tasks.md](tasks.md) (T-06…T-30),
> [hackathon-brief.md](hackathon-brief.md) (формат сдачи). Если план
> расходится с requirements.md по смыслу требования — прав requirements.md,
> но сначала спроси человека.

**Дедлайн: 30 сентября 2026.** План рассчитан на 24–30 сентября (см.
«График» в конце раздела 1).

---

## 0. Правила для исполнителя

1. **Git** (из [CLAUDE.md](../CLAUDE.md)):
   - Одна ветка на блок шагов (блоки — в таблице §1): `feat/skeleton`,
     `feat/bot-roles`, `feat/act-card`, `feat/timers`, `feat/remarks`,
     `feat/sign-refusal`, `chore/delivery`. Внутри блока — **один коммит на
     шаг**. Мерж в `master` делает человек (или подтверждает) — ты только
     пушишь ветку и открываешь PR.
   - Автор коммитов — `dan00ile <nikelodeon53@gmail.com>`. Проверь
     `git config user.email`; если там другое — коммить с
     `git -c user.name=dan00ile -c user.email=nikelodeon53@gmail.com commit …`
     (конфиг не переписывай).
   - Сообщение — Conventional Commits, описание по-русски, коротко:
     `feat(bot): загрузка акта председателем`. **Без** строки
     `Co-Authored-By`.
2. **Ponytail:** никаких интерфейсов «ради тестов», DI-фреймворков,
   репозиториев поверх Exposed, Flyway, Spring, React, сборщиков фронта.
   Только зависимости из шага S1. Сознательный шорткат помечай комментарием
   `// ponytail: <что упрощено и когда доделать>`.
3. **Секреты** только через переменные окружения (`.env`, не в git). До
   получения токенов (см. §4 «Вопросы к человеку») шаги, требующие MAX/
   GigaChat, делай с `DEV_AUTH=true` и проверяй тем, что доступно без сети;
   финальную проверку — когда токены появятся.
4. Все тексты для пользователя — простым русским языком, без юридического
   жаргона в подсказках (NFR-7). Тексты сообщений бота — **plain text**, без
   markdown (чтобы не экранировать).
5. Каждый шаг заканчивается проверкой из «Критерия готовности». Не переходи к
   следующему, пока критерий не выполнен.

---

## 1. Решённые развилки

| Вопрос из build-brief «на усмотрение» | Решение | Почему |
|---|---|---|
| HTTP-клиент MAX Bot API: генерить из OpenAPI или руками | **Руками**: один файл `MaxBotClient.kt` на Ktor Client + kotlinx.serialization | Нужно 6 методов (`/updates`, `/messages`, `/answers`, `/uploads`, `/me`, скачивание файла). Генератор даст сотни файлов, которые никто не читает, и отдельный шаг сборки. Поля сверены с официальным Go SDK `max-messenger/max-bot-api-client-go` |
| React + MAX UI vs чистый HTML/JS | **Чистый HTML/CSS/JS** без сборщика: 4 файла в `webapp/` | 4 экрана, нет сборки → деплой = пуш, нет `package-lock.json`, меньше мест для ошибки у исполнителя. MAX Bridge подключается одним `<script>` |
| PDF-библиотека | **OpenPDF** `com.github.librepdf:openpdf:1.3.43` (LGPL/MPL) + шрифт DejaVu Sans в ресурсах | Прямой API (`Document`/`Paragraph`/`PdfPTable`/`Image`) — детерминированная вёрстка без HTML/XHTML-шаблонов и их строгого парсинга; умеет встраивать изображения (FR-G2.1) и извлекать текст из PDF (`PdfTextExtractor`) для распознавания акта — одна библиотека на обе задачи. Лицензия свободная. Кириллица — через встроенный TTF |
| Webhook vs long polling | **Long polling** (`GET /updates`) везде | Не нужен публичный адрес под бота, одинаково работает локально и в Docker. Webhook — после Must |
| Госключ | **Заглушка** (FR-F2 = Should): «Подписать» формирует PDF-экземпляр с пометкой «демо, КЭП не применялась» | requirements §3/§7 |
| Отправка документа в УК | **Председатель пересылает PDF сам** (email/мессенджер) и жмёт в боте «Отправил исполнителю» → фиксируется время | FR-F1 разрешает «согласованный способ»; SMTP = ещё один секрет и интеграция. Автоотправка — после Must |
| Распознавание акта (FR-B1.1) | GigaChat `POST /chat/completions` с просьбой вернуть JSON. PDF → текст через OpenPDF и в промпт; фото → загрузка файла в GigaChat и `attachments`. При ошибке — акт без позиций, председатель вводит их вручную в мини-аппе (FR-B1.2) | Один провайдер, один формат ответа; ручной ввод и так нужен по FR-B1.2, поэтому он и есть fallback (NFR-9). Отдельную функцию `table` не используем — её контракт не проверен |
| Миграции БД | `SchemaUtils.create(...)` на старте | Схема создаётся с нуля; миграции — после Must |
| Авторизация мини-аппа | Заголовок `X-Max-Init-Data: <initData>` в **каждом** запросе, проверка HMAC на бэкенде, без сессий/JWT | Stateless, нет хранилища сессий |
| Хостинг мини-аппа | GitHub Pages через GitHub Actions из папки `webapp/` | `docs/` уже занята документацией |
| Роль «председатель» | Самозаявление: ФИО + основание полномочий текстом. Если задан `ADMIN_USER_IDS` — админ подтверждает кнопкой; если пусто — подтверждается автоматически (демо) | FR-A4; жюри должно пройти сценарий без ожидания оператора |
| Председатель = житель | Регистрация председателем даёт и роль жителя того же дома | FR-A3; один проверяющий может пройти весь сценарий сам |
| Окно замечаний на T0+10 | **Не** закрывается автоматически, только напоминание | FR-D3 (текстовое требование) приоритетнее стрелки в диаграмме §5 |
| Как показать жюри 30-й день | Команда бота `/shift N` (только при `DEMO_MODE=true`) сдвигает дату получения активного акта на N дней назад | Иначе молчаливое согласие (T-17) не проверить за время проверки |
| Структура Kotlin-кода | Плоский пакет `mkd`, ~16 файлов, ручная сборка зависимостей в `Application.kt` | Ponytail: build-brief допускает «рекомендация, не догма» |

### Блоки, шаги и задачи

| Блок / ветка | Шаг | Задачи tasks.md |
|---|---|---|
| `feat/skeleton` | S1 Каркас Gradle/Ktor | T-06 |
| | S2 Схема БД (все таблицы) | T-06 |
| | S3 Docker, compose, .env.example | T-08 |
| | S4 Клиент MAX Bot API + long polling | T-07 |
| | S5 Проверка initData + `/api/me` | T-09 |
| | S6 Каркас мини-аппа + GitHub Pages + смоук | T-06, T-07 |
| `feat/bot-roles` | S7 Демо-справочник (сид) | T-10 |
| | S8 Регистрация и роли в боте | T-09 |
| `feat/act-card` | S9 Загрузка акта, дата получения, сроки | T-11, T-12 |
| | S10 GigaChat + распознавание позиций | T-11 (FR-B1.1) |
| | S11 Редактор карточки в мини-аппе + старт сбора | T-11 (FR-B1.2) |
| `feat/timers` | S12 Движок сроков | T-14 |
| | S13 Проактивные уведомления | T-16 |
| | S14 Молчаливое согласие + `/shift` | T-17 |
| | S15 Обратный отсчёт в боте и мини-аппе | T-15 |
| `feat/remarks` | S16 Чек-лист жителя + фото | T-18, T-19 |
| | S17 Формализация замечаний LLM | T-20 |
| | S18 Агрегированная картина и решения по позициям | T-21 |
| `feat/sign-refusal` | S19 PDF-рендер + подписание (заглушка) + передача | T-23, T-24 |
| | S20 Сборка черновика отказа | T-25 |
| | S21 Редактирование, подтверждение, PDF отказа с фото | T-25, T-26 |
| | S22 Отправка отказа с фиксацией времени | T-27 |
| `chore/delivery` | S23 Сквозной сценарий | T-29 |
| | S24 README и чек-лист сдачи | T-30, T-08 |

T-08 в tasks.md помечен Should, но Docker+README — **обязательное условие
сдачи** (hackathon-brief §«Формат сдачи»), поэтому он в Must-пути.

### График

| Дата | Шаги |
|---|---|
| 24–25 сен | S1–S7 |
| 26 сен | S8–S11 |
| 27 сен | S12–S16 |
| 28 сен | S17–S21 |
| 29 сен | S22–S24, прогон в MAX (мобильная + веб-версия) |
| 30 сен | Буфер, фикс багов, фиксация commit hash для сдачи |

---

## 2. Итоговая структура репозитория

```
backend/
  settings.gradle.kts
  build.gradle.kts
  gradle.lockfile
  gradlew, gradlew.bat, gradle/wrapper/*
  Dockerfile
  certs/russian_trusted_root_ca.cer        # корневой сертификат Минцифры (для GigaChat)
  src/main/kotlin/mkd/
    Application.kt      # main: конфиг, БД, сид, клиенты, корутины, Ktor
    Config.kt           # чтение env
    Tables.kt           # все Exposed-таблицы и enum'ы (S2)
    Db.kt               # подключение, create schema, suspend-транзакция, logEvent
    Seed.kt             # демо-справочник (S7)
    MaxBotClient.kt     # HTTP-клиент MAX + DTO (S4)
    InitData.kt         # HMAC-проверка initData (S5)
    GigaChatClient.kt   # OAuth + chat + files (S10)
    Bot.kt              # обработка апдейтов: регистрация, загрузка, колбэки
    Api.kt              # REST для мини-аппа + DTO
    Acts.kt             # ActService + Deadlines (чистые функции)
    Timers.kt           # TimerService
    Remarks.kt          # RemarkService (замечания, фото, формализация)
    Refusal.kt          # RefusalService + buildDraft (чистая функция)
    Pdf.kt              # PdfRenderer
  src/main/resources/
    logback.xml
    fonts/DejaVuSans.ttf, fonts/DejaVuSans-Bold.ttf
  src/test/kotlin/mkd/
    DeadlinesTest.kt, InitDataTest.kt, TimersTest.kt, RefusalDraftTest.kt
    DemoActPdfTest.kt   # генератор demo/act-demo.pdf, работает только при GEN_DEMO=1
webapp/
  index.html, app.js, style.css, config.js
demo/
  act-demo.pdf          # демо-акт по форме 761/пр для проверки (S10)
.github/workflows/pages.yml
compose.yaml
.dockerignore
.env.example
.gitignore
README.md
```

---

## 3. Шаги

Формат каждого шага: **Файлы** → **Что сделать / интерфейсы** →
**Критерий готовности** → **Коммит**.

---

### S1. Каркас Gradle + Ktor (T-06)

**Файлы:** `backend/settings.gradle.kts`, `backend/build.gradle.kts`,
`backend/gradle/wrapper/*`, `backend/gradlew*`,
`backend/src/main/kotlin/mkd/Application.kt`, `backend/src/main/kotlin/mkd/Config.kt`,
`backend/src/main/resources/logback.xml`, `.gitignore`.

`settings.gradle.kts` — Google-зеркало Maven Central стоит первым: прямой
`repo1.maven.org` отвечает 429 на общих IP облачных сессий и CI. Зеркало
отдаёт те же артефакты; если в нём чего-то нет, Gradle возьмёт из
следующего репозитория:
```kotlin
val centralMirror = "https://maven-central.storage-download.googleapis.com/maven2/"

pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven(centralMirror)
        mavenCentral()
    }
}

rootProject.name = "backend"
```

`build.gradle.kts` (версии — ровно эти; если какая-то не резолвится из
Maven Central, возьми ближайшую патч-версию той же минорной ветки и запиши
в коммит почему):
```kotlin
plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    application
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
    implementation("io.ktor:ktor-client-java:$ktor")   // не CIO: CIO ловит 503 на platform-api2.max.ru через прокси, JDK HttpClient — 200
    implementation("io.ktor:ktor-client-content-negotiation:$ktor")
    implementation("org.jetbrains.exposed:exposed-core:$exposed")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposed")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("com.github.librepdf:openpdf:1.3.43")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }
application { mainClass.set("mkd.ApplicationKt") }
tasks.test { useJUnitPlatform() }
dependencyLocking { lockAllConfigurations() }
```

Блока `repositories` в `build.gradle.kts` нет: репозитории задаются только
в `settings.gradle.kts`.

Exposed — именно ветка `0.61.x` (пакеты `org.jetbrains.exposed.sql.*`).
Не бери Exposed 1.x — там другие пакеты.

Wrapper: `gradle wrapper --gradle-version 8.14.3` (если локального Gradle
нет: `docker run --rm -v "$PWD/backend":/p -w /p gradle:8.14.3-jdk21 gradle wrapper --gradle-version 8.14.3`).
Lock-файл: `./gradlew dependencies --write-locks`.

`Config.kt`:
```kotlin
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
    val zone: java.time.ZoneId,
) {
    companion object {
        fun fromEnv(): Config  // System.getenv(...) с дефолтами из таблицы ниже
    }
}
```

| env | дефолт |
|---|---|
| `PORT` | `8080` |
| `DB_URL` | `jdbc:postgresql://localhost:5432/mkd` |
| `DB_USER` / `DB_PASSWORD` | `mkd` / `mkd` |
| `MAX_BOT_TOKEN` | `""` |
| `MAX_API_BASE` | `https://platform-api2.max.ru` |
| `GIGACHAT_AUTH_KEY` | `""` |
| `GIGACHAT_SCOPE` | `GIGACHAT_API_PERS` |
| `GIGACHAT_MODEL` | `GigaChat-2-Max` |
| `CORS_ORIGIN` | `http://localhost:5500` |
| `ADMIN_USER_IDS` | `""` (через запятую) |
| `DEMO_MODE` | `true` |
| `DEV_AUTH` | `false` |
| `FILES_DIR` | `./data/files` |
| `TZ_ZONE` | `Europe/Moscow` |

`Application.kt` на этом шаге: `fun main()` → `Config.fromEnv()` →
`embeddedServer(Netty, port = cfg.port) { install(ContentNegotiation) { json(AppJson) }; install(CallLogging); routing { get("/health") { call.respondText("ok") } } }.start(wait = true)`.

Там же объяви общий JSON:
```kotlin
val AppJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
```

`.gitignore` (корень): `.env`, `backend/build/`, `backend/.gradle/`,
`data/`, `.idea/workspace.xml`.

**Критерий готовности:** `cd backend && ./gradlew build` зелёный;
`./gradlew run` → `curl localhost:8080/health` возвращает `ok`.

**Коммит:** `chore(backend): каркас Ktor-приложения`

---

### S2. Схема БД — все таблицы сразу (T-06, requirements §6)

**Файлы:** `backend/src/main/kotlin/mkd/Tables.kt`, `backend/src/main/kotlin/mkd/Db.kt`,
правка `Application.kt`.

Все дальнейшие шаги ссылаются на эти таблицы. Не добавляй колонок сверх
перечисленных без необходимости, не удаляй перечисленные.

`Tables.kt`:
```kotlin
package mkd

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

enum class ActStatus { RECEIVED, COLLECTING, REVIEW, SIGNED, REJECTED, SILENT }
enum class Recognition { PENDING, DONE, FAILED }
enum class Decision { ACCEPT, DISPUTE }
enum class Verdict { OK, ISSUE }
enum class LlmStatus { NONE, PENDING, DONE, FAILED }

// §6 «УК (исполнитель)»
object ManagementCompanies : LongIdTable("management_companies") {
    val name = text("name")
    val inn = varchar("inn", 12)
    val licenseNo = text("license_no")
    val representative = text("representative")        // ФИО/должность представителя (FR-G2)
    val exchangeMethod = text("exchange_method")       // согласованный способ обмена, напр. "email: uk@example.ru"
    val isDemo = bool("is_demo").default(true)
}

// §6 «Дом (МКД)»
object Houses : LongIdTable("houses") {
    val address = text("address")
    val registryId = text("registry_id").nullable()    // id в ГИС ЖКХ (Could), в MVP null
    val ukId = reference("uk_id", ManagementCompanies)
    val hasCouncil = bool("has_council").default(true)
    val isDemo = bool("is_demo").default(true)
}

// §6 «Пользователь». id = user_id в MAX
object Users : Table("users") {
    val id = long("id")
    val name = text("name")                            // имя из профиля MAX
    val contact = text("contact").nullable()
    val houseId = reference("house_id", Houses).nullable()   // привязка к дому = роль жителя
    val pdConsentAt = timestamp("pd_consent_at").nullable() // согласие на обработку ПДн (NFR-2)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

// §6 «Председатель»
object Chairmen : LongIdTable("chairmen") {
    val userId = long("user_id").references(Users.id)
    val houseId = reference("house_id", Houses)
    val fullName = text("full_name")                   // ФИО для документов
    val authorityBasis = text("authority_basis")       // протокол ОСС / доверенность, без проверки (FR-A4)
    val termUntil = date("term_until").nullable()
    val confirmedAt = timestamp("confirmed_at").nullable()  // null = ждёт подтверждения оператора
    init { uniqueIndex(userId, houseId) }
}

// §6 «Акт»
object Acts : LongIdTable("acts") {
    val houseId = reference("house_id", Houses)
    val actType = text("act_type").default("Содержание и текущий ремонт общего имущества")
    val number = text("number").nullable()
    val period = text("period").nullable()             // "сентябрь 2026"
    val formedDate = date("formed_date").nullable()    // дата оформления исполнителем
    val receivedAt = timestamp("received_at")          // T0
    val deadline10 = date("deadline10")                // T0+10 (последний день срока по п. 4)
    val deadline30 = date("deadline30")                // T0+30 (последний день до молчаливого согласия, п. 5)
    val status = enumerationByName("status", 16, ActStatus::class)
    val filePath = text("file_path")
    val fileName = text("file_name")
    val uploadedBy = long("uploaded_by").references(Users.id)
    val recognition = enumerationByName("recognition", 16, Recognition::class)
    val previousActId = long("previous_act_id").nullable()  // цепочка кругов (FR-G5, Should) — в Must всегда null
    val round = integer("round").default(1)
    val signedPdfPath = text("signed_pdf_path").nullable()
    val createdAt = timestamp("created_at")
}

// §6 «Позиция акта»
object ActItems : LongIdTable("act_items") {
    val actId = reference("act_id", Acts, onDelete = ReferenceOption.CASCADE)
    val lineNo = integer("line_no")
    val name = text("name")
    val periodicity = text("periodicity").default("")
    val volume = text("volume").default("")
    val cost = text("cost").default("")                // строкой: как в акте, без парсинга
    val workKind = varchar("work_kind", 32).default("OTHER")  // код из Grounds.workKind
    val decision = enumerationByName("decision", 16, Decision::class).nullable()  // решение председателя
}

// §6 «Замечание». Одна строка = ответ одного жителя по одной позиции (и «ок», и «претензия»)
object Remarks : LongIdTable("remarks") {
    val itemId = reference("item_id", ActItems, onDelete = ReferenceOption.CASCADE)
    val authorId = long("author_id").references(Users.id)
    val verdict = enumerationByName("verdict", 8, Verdict::class)
    val originalText = text("original_text").nullable()
    val formalizedText = text("formalized_text").nullable()
    val relevance = text("relevance").nullable()       // метка релевантности (FR-E4, Should) — в Must не заполняется
    val llmStatus = enumerationByName("llm_status", 16, LlmStatus::class).default(LlmStatus.NONE)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    init { uniqueIndex(itemId, authorId) }
}

// §6 «Вложение»
object Attachments : LongIdTable("attachments") {
    val remarkId = reference("remark_id", Remarks, onDelete = ReferenceOption.CASCADE)
    val filePath = text("file_path")
    val mime = text("mime")
    val authorId = long("author_id").references(Users.id)
    val uploadedAt = timestamp("uploaded_at")
    val registryNo = integer("registry_no").nullable() // номер в реестре приложений, проставляется при формировании отказа
}

// §6 «Основание» — справочник оператора
object Grounds : LongIdTable("grounds") {
    val workKind = varchar("work_kind", 32).uniqueIndex()
    val workKindTitle = text("work_kind_title")
    val legalRef = text("legal_ref")
    val wording = text("wording")
    val demandTemplate = text("demand_template")       // шаблон требования, плейсхолдер {item}
}

// §6 «Мотивированный отказ». Одна запись на акт (новый круг = новый акт)
object Refusals : LongIdTable("refusals") {
    val actId = reference("act_id", Acts).uniqueIndex()
    val draftJson = text("draft_json")                 // RefusalDraft (S20) в JSON: возражения + позиции без возражений
    val place = text("place")                          // место составления
    val revision = integer("revision").default(1)      // номер редакции
    val confirmedAt = timestamp("confirmed_at").nullable()
    val confirmedBy = long("confirmed_by").nullable()  // подпись (в MVP — факт подтверждения председателем)
    val pdfPath = text("pdf_path").nullable()
    val sentAt = timestamp("sent_at").nullable()
    val createdAt = timestamp("created_at")
}

// §6 «Событие (аудит)». Только INSERT — никаких UPDATE/DELETE (NFR-4)
object Events : LongIdTable("events") {
    val actId = long("act_id").nullable().index()
    val type = varchar("type", 48)
    val actorId = long("actor_id").nullable()          // null = система
    val at = timestamp("at")
    val details = text("details").default("")
}

val allTables = arrayOf(ManagementCompanies, Houses, Users, Chairmen, Acts, ActItems,
    Remarks, Attachments, Grounds, Refusals, Events)
```

Типы событий (`Events.type`) — строковые константы, полный список:
`USER_REGISTERED, CHAIRMAN_REGISTERED, CHAIRMAN_CONFIRMED, ACT_RECEIVED,
RECEIPT_DATE_SET, RECOGNIZED, RECOGNITION_FAILED, CARD_EDITED,
COLLECTION_OPENED, REMARK_SAVED, PHOTO_ADDED, COLLECTION_CLOSED,
DECISION_SET, NOTIFY_D0, NOTIFY_D7, NOTIFY_D10, NOTIFY_D25, NOTIFY_D28,
NOTIFY_D29, NOTIFY_SILENT, SILENT_CONSENT, SIGNED, REFUSAL_DRAFTED,
REFUSAL_EDITED, REFUSAL_CONFIRMED, REFUSAL_SENT, DEMO_SHIFT`.

`Db.kt`:
```kotlin
object Db {
    fun init(cfg: Config)  // HikariDataSource(url,user,pass, maximumPoolSize=5) → Database.connect(ds);
                           // transaction { SchemaUtils.create(*allTables) }
}

suspend fun <T> tx(block: suspend Transaction.() -> T): T =
    newSuspendedTransaction(Dispatchers.IO) { block() }

// вызывать только внутри tx { }
fun logEvent(actId: Long?, type: String, actorId: Long?, details: String = "") {
    Events.insert { it[Events.actId] = actId; it[Events.type] = type; it[Events.actorId] = actorId
                    it[Events.at] = Instant.now(); it[Events.details] = details }
}
```
В `Application.kt` вызвать `Db.init(cfg)` до старта сервера.

**Критерий готовности:** с локальным Postgres
(`docker run --rm -e POSTGRES_USER=mkd -e POSTGRES_PASSWORD=mkd -e POSTGRES_DB=mkd -p 5432:5432 postgres:16-alpine`)
`./gradlew run` стартует без ошибок; `psql` → `\dt` показывает 11 таблиц;
повторный старт не падает (create идемпотентен).

**Коммит:** `feat(db): схема таблиц по модели данных`

---

### S3. Docker, compose, .env.example (T-08)

**Файлы:** `backend/Dockerfile`, `compose.yaml`, `.dockerignore`,
`.env.example`, `backend/certs/russian_trusted_root_ca.cer`.

Сертификат: скачать `https://gu-st.ru/content/Other/doc/russian_trusted_root_ca.cer`
(публичный корневой сертификат Минцифры, секретом не является). Нужен,
потому что API GigaChat подписан этим УЦ, а JVM ему по умолчанию не доверяет.
Если скачать не удаётся — вопрос к человеку (§4).

`backend/Dockerfile`:
```dockerfile
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts gradle.lockfile ./
RUN gradle dependencies --no-daemon > /dev/null
COPY src ./src
RUN gradle installDist --no-daemon -x test

FROM eclipse-temurin:21-jre
COPY certs/russian_trusted_root_ca.cer /tmp/ru_root.cer
RUN keytool -importcert -noprompt -cacerts -storepass changeit -alias ru_root -file /tmp/ru_root.cer
COPY --from=build /src/build/install/backend /app
ENV FILES_DIR=/data/files
EXPOSE 8080
CMD ["/app/bin/backend"]
```

`compose.yaml`:
```yaml
services:
  db:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: mkd
      POSTGRES_USER: mkd
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    volumes: [pgdata:/var/lib/postgresql/data]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U mkd"]
      interval: 3s
      retries: 20
  backend:
    build: ./backend
    env_file: .env
    environment:
      DB_URL: jdbc:postgresql://db:5432/mkd
      DB_USER: mkd
      DB_PASSWORD: ${POSTGRES_PASSWORD}
    ports: ["8080:8080"]
    volumes: [files:/data/files]
    depends_on:
      db: { condition: service_healthy }
volumes:
  pgdata:
  files:
```

`.env.example` (без значений секретов, с комментарием к каждой строке):
```
# Токен бота MAX (выдают организаторы / @MasterBot). ОБЯЗАТЕЛЬНО
MAX_BOT_TOKEN=
MAX_API_BASE=https://platform-api2.max.ru
# Ключ авторизации GigaChat (Authorization key из личного кабинета developers.sber.ru). ОБЯЗАТЕЛЬНО для LLM
GIGACHAT_AUTH_KEY=
GIGACHAT_SCOPE=GIGACHAT_API_PERS
GIGACHAT_MODEL=GigaChat-2-Max
# Пароль БД для docker compose
POSTGRES_PASSWORD=change-me
# Origin мини-приложения на GitHub Pages, например https://dan00ile.github.io
CORS_ORIGIN=
# user_id администраторов в MAX через запятую; пусто = председатели подтверждаются автоматически (демо)
ADMIN_USER_IDS=
# Демо-режим: команда /shift и пометки «демо» в документах
DEMO_MODE=true
# Только для локальной отладки без MAX: принимать заголовок X-Dev-User-Id. НИКОГДА не true на проверке
DEV_AUTH=false
TZ_ZONE=Europe/Moscow
```

`.dockerignore`: `**/build`, `**/.gradle`, `.git`, `.idea`, `docs`,
`webapp`, `demo`, `data`, `.env`.

**Критерий готовности:** `cp .env.example .env && docker compose up --build`
— сборка бэкенда ≤5 минут (без учёта скачивания базовых образов),
`curl localhost:8080/health` → `ok`; `docker compose down && docker compose up`
— стартует снова, данные в Postgres сохраняются.

**Коммит:** `chore(docker): Dockerfile, compose и .env.example`

---

### S4. Клиент MAX Bot API + long polling (T-07)

**Файлы:** `backend/src/main/kotlin/mkd/MaxBotClient.kt`,
`backend/src/main/kotlin/mkd/Bot.kt` (пока — эхо), правка `Application.kt`.

Контракт MAX (сверен с официальным Go SDK; при расхождении с живым API —
верь живому API и поправь DTO):
- База `MAX_API_BASE`, заголовок `Authorization: <token>` (сам токен, без
  `Bearer`), токен **никогда** не в query.
- `GET /updates?timeout=30&marker=<m>&types=message_created,message_callback,bot_started`
  → `{"updates":[...],"marker":123}`.
- `POST /messages?user_id=<id>` тело `{"text": "...", "attachments": [...]}`.
- Inline-клавиатура — элемент `attachments`:
  `{"type":"inline_keyboard","payload":{"buttons":[[{"type":"callback","text":"…","payload":"…"}],[{"type":"link","text":"…","url":"https://…"}]]}}`.
- `POST /answers?callback_id=<id>` тело `{"notification":"…"}`.
- Загрузка файла: `POST /uploads?type=file` → `{"url":"…"}`; затем
  multipart `POST <url>` с полем `data` → `{"token":"…"}`; затем
  сообщение с `attachments: [{"type":"file","payload":{"token":"…"}}]`.
  Если `POST /messages` вернул ошибку с `attachment.not.ready` — повторить
  до 5 раз с паузой 1 с.
- Входящий файл/фото от пользователя: `message.body.attachments[i]` с
  `type` = `"file"` или `"image"`, ссылка — `payload.url`, имя файла —
  `filename` (для `file`). Скачивать обычным `GET url` без заголовка
  `Authorization`.
- HTTP 429 → пауза 1 с и один повтор.

```kotlin
@Serializable data class MaxUser(
    @SerialName("user_id") val userId: Long,
    val name: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
) { fun displayName() = name ?: listOfNotNull(firstName, lastName).joinToString(" ").ifBlank { "Житель" } }

@Serializable data class Recipient(@SerialName("chat_id") val chatId: Long? = null, @SerialName("user_id") val userId: Long? = null)
@Serializable data class MessageBody(val mid: String, val text: String? = null, val attachments: List<JsonObject> = emptyList())
@Serializable data class Message(val sender: MaxUser? = null, val recipient: Recipient, val timestamp: Long, val body: MessageBody)
@Serializable data class Callback(@SerialName("callback_id") val callbackId: String, val payload: String? = null, val user: MaxUser)
@Serializable data class Update(
    @SerialName("update_type") val type: String,
    val timestamp: Long,                        // unix ms
    val message: Message? = null,               // message_created, message_callback
    val callback: Callback? = null,             // message_callback
    val user: MaxUser? = null,                  // bot_started
    @SerialName("chat_id") val chatId: Long? = null,
    val payload: String? = null,                // bot_started: payload диплинка
)
@Serializable data class UpdateList(val updates: List<Update>, val marker: Long? = null)
@Serializable data class Button(val type: String, val text: String, val payload: String? = null, val url: String? = null)

fun cb(text: String, payload: String) = Button("callback", text, payload = payload)
fun link(text: String, url: String) = Button("link", text, url = url)

class MaxBotClient(private val token: String, private val base: String) {
    // HttpClient(Java) {   (движок Java — для всех HTTP-клиентов проекта, включая GigaChat)
    // install(ContentNegotiation){ json(AppJson) }; install(HttpTimeout){ requestTimeoutMillis = 45_000 } }
    suspend fun me(): JsonObject
    suspend fun getUpdates(marker: Long?): UpdateList
    suspend fun sendText(userId: Long, text: String, buttons: List<List<Button>> = emptyList())
    suspend fun sendFile(userId: Long, bytes: ByteArray, fileName: String, text: String, buttons: List<List<Button>> = emptyList())
    suspend fun answerCallback(callbackId: String, notification: String)
    suspend fun download(url: String): ByteArray
}
```
Если `buttons` пуст — не добавлять `inline_keyboard` в `attachments`.

`Bot.kt`:
```kotlin
class Bot(private val max: MaxBotClient /* + сервисы в следующих шагах */) {
    suspend fun pollLoop()   // var marker: Long? = null; while (true) { try { val l = max.getUpdates(marker);
                             //   l.updates.forEach { runCatching { handle(it) }.onFailure { log.error("update", it) } };
                             //   marker = l.marker ?: marker } catch (e: Exception) { log.warn(...); delay(3000) } }
    suspend fun handle(u: Update)  // на этом шаге: message_created с текстом → sendText(sender.userId, "Эхо: $text")
}
```

`Application.kt`: `val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`;
если `cfg.maxToken` не пуст — вызвать `max.me()`, взять из ответа
`username` (ник бота для диплинков, отдельной переменной окружения нет) и
сохранить в `lateinit var botUsername: String` верхнего уровня в
`MaxBotClient.kt`; затем `scope.launch { bot.pollLoop() }`. Если `me()`
упал — приложение падает с понятной ошибкой «неверный MAX_BOT_TOKEN».
Если токен пуст — лог-предупреждение «MAX_BOT_TOKEN не задан, бот
выключен».

**Критерий готовности:** с реальным токеном — лог при старте печатает
результат `me()` и `botUsername`; сообщение боту в MAX возвращается эхом. Без
токена приложение стартует и пишет предупреждение.

**Коммит:** `feat(bot): клиент MAX Bot API и long polling`

---

### S5. Проверка initData + `/api/me` (T-09, NFR-1)

**Файлы:** `backend/src/main/kotlin/mkd/InitData.kt`,
`backend/src/main/kotlin/mkd/Api.kt`, `backend/src/test/kotlin/mkd/InitDataTest.kt`,
правка `Application.kt`.

```kotlin
object InitData {
    data class Result(val userId: Long, val name: String, val startParam: String?)

    fun validate(raw: String, botToken: String, now: Instant = Instant.now(), maxAgeSec: Long = 86_400): Result?
    fun hmac(key: ByteArray, data: ByteArray): ByteArray   // Mac "HmacSHA256"
}
```
Алгоритм `validate` (строго так):
1. Разбить `raw` по `&`; каждую пару — по **первому** `=`; значение
   URL-декодировать **один раз** (`URLDecoder.decode(v, UTF_8)`).
2. `hash` = значение ключа `hash`; нет → `null`.
3. `dataCheckString` = все пары, кроме `hash`, отсортированные по ключу,
   в виде `key=value`, соединённые `\n`.
4. `secret = hmac(key = "WebAppData".toByteArray(), data = botToken.toByteArray())`.
5. `expected = hex(hmac(key = secret, data = dataCheckString.toByteArray()))`
   (нижний регистр); сравнить с `hash.lowercase()` через
   `MessageDigest.isEqual`. Не совпало → `null`.
6. `auth_date` (секунды) старше `maxAgeSec` → `null`.
7. `user` — JSON; `userId = (user["id"] ?: user["user_id"]).long`;
   `name = first_name + " " + last_name` (trim) или `"Житель"`;
   `startParam = params["start_param"]`.

При несовпадении hash логируй на WARN только список ключей initData (не
значения, не hash).

В `Api.kt`:
```kotlin
class ApiError(val status: HttpStatusCode, val code: String, override val message: String) : Exception(message)
@Serializable data class ErrorDto(val error: String, val message: String)

// кидает ApiError(401, "unauthorized", ...) при провале
fun ApplicationCall.authUser(cfg: Config): InitData.Result
//  1) заголовок X-Max-Init-Data → InitData.validate(..., cfg.maxToken)
//  2) иначе если cfg.devAuth && есть X-Dev-User-Id → Result(id, "Dev", null)
//  3) иначе 401

fun Route.api(cfg: Config /* + сервисы */)   // все роуты под "/api"
```
`Application.kt`: `install(StatusPages) { exception<ApiError> { call, e -> call.respond(e.status, ErrorDto(e.code, e.message)) } }`;
`install(CORS) { allowHost(cfg.corsOrigin без схемы, schemes = listOf("https","http")); allowHeader("X-Max-Init-Data"); allowHeader("X-Dev-User-Id"); allowHeader(HttpHeaders.ContentType); allowMethod(HttpMethod.Put) }`.

Эндпоинт на этом шаге:
```
GET /api/me
200 → MeDto
```
```kotlin
@Serializable data class MeDto(
    val userId: Long, val name: String,
    val registered: Boolean,          // есть строка в Users
    val houseId: Long?, val houseAddress: String?,
    val roles: List<String>,          // подмножество ["RESIDENT","CHAIRMAN"]
    val chairmanPending: Boolean,     // заявка председателя ждёт подтверждения
    val activeActId: Long?,           // последний акт дома в статусе RECEIVED/COLLECTING/REVIEW
    val startParam: String?,
)
```
Роли: `RESIDENT` — `Users.houseId != null`; `CHAIRMAN` — есть `Chairmen`
с этим `userId`, тем же `houseId` и `confirmedAt != null`. Тот же расчёт
вынеси в функцию (в `Acts.kt` или `Api.kt`), она нужна боту:
```kotlin
suspend fun rolesOf(userId: Long): Roles
data class Roles(val houseId: Long?, val resident: Boolean, val chairman: Boolean, val chairmanPending: Boolean)
```

`InitDataTest`: собери initData в тесте тем же алгоритмом с токеном
`"test-token"` → `validate` возвращает userId; изменённый `user` → `null`;
`auth_date` двухдневной давности → `null`; initData без `hash` → `null`.

**Критерий готовности:** `./gradlew test` зелёный; с `DEV_AUTH=true`
`curl -H 'X-Dev-User-Id: 1' localhost:8080/api/me` → 200 с
`registered=false`; без заголовков → 401 с JSON `{"error":"unauthorized",…}`.
Проверка на **реальных** initData — в S6.

**Коммит:** `feat(api): проверка initData и /api/me`

---

### S6. Каркас мини-аппа + GitHub Pages + смоук в MAX (T-06, T-07)

**Файлы:** `webapp/index.html`, `webapp/app.js`, `webapp/style.css`,
`webapp/config.js`, `.github/workflows/pages.yml`.

`index.html`: `<meta name="viewport" content="width=device-width, initial-scale=1">`;
скрипты в порядке: `https://st.max.ru/js/max-web-app.js`, `config.js`,
`app.js`; в body — `<header id="header"></header><main id="app">Загрузка…</main>`.

`config.js`:
```js
window.API_BASE = "https://REPLACE-ME";   // публичный HTTPS-адрес бэкенда, см. «Вопросы к человеку»
```

`app.js` — каркас, который дальше расширяют шаги S11/S15/S16/S18/S21:
```js
const WA = window.WebApp;
const params = new URLSearchParams(location.search);
const DEV_USER = params.get("devUser");          // локальная отладка вне MAX (нужен DEV_AUTH=true на бэкенде)

async function api(path, opts = {}) {
  const headers = { ...(opts.headers || {}) };
  if (WA && WA.initData) headers["X-Max-Init-Data"] = WA.initData;
  else if (DEV_USER) headers["X-Dev-User-Id"] = DEV_USER;
  if (opts.json !== undefined) { headers["Content-Type"] = "application/json"; opts.body = JSON.stringify(opts.json); }
  const r = await fetch(window.API_BASE + path, { ...opts, headers });
  if (!r.ok) { const e = await r.json().catch(() => ({ message: r.statusText })); throw new Error(e.message); }
  return r.status === 204 ? null : r.json();
}

let state = { me: null, act: null, tab: "items" };

async function start() {
  try { state.me = await api("/api/me"); render(); }
  catch (e) { document.getElementById("app").textContent = "Ошибка: " + e.message; }
  if (WA) { WA.ready(); WA.expand && WA.expand(); }
}
function render() { /* S6: вывести me.name, me.userId, me.roles и <input type=file accept="image/*"> для смоука */ }
start();
```
Правило для всех экранов: ошибки API показывать текстом над формой, не
через `alert`.

`.github/workflows/pages.yml`: триггер `push` в `master` по путям
`webapp/**` + `workflow_dispatch`; `permissions: pages: write, id-token: write, contents: read`;
шаги `actions/checkout@v4` → `actions/configure-pages@v5` →
`actions/upload-pages-artifact@v3` (`path: webapp`) → `actions/deploy-pages@v4`
(environment `github-pages`).

Локальная отладка без MAX: `cd webapp && python3 -m http.server 5500`,
в `config.js` временно `http://localhost:8080` (не коммить), бэкенд с
`DEV_AUTH=true CORS_ORIGIN=http://localhost:5500`, открыть
`http://localhost:5500/?devUser=1`.

**Смоук в MAX (обязательно до S8, нужны ответы человека из §4):**
1. Человек включил Pages и указал URL мини-аппа в настройках бота.
2. Открыть мини-апп из MAX (мобильный и веб) → экран показывает `userId`
   и имя → значит `initData` прошла HMAC-проверку на бэкенде.
3. Сравнить `userId` из мини-аппа с `sender.user_id` из лога эхо-бота (S4)
   — должны совпадать. Если не совпадают — остановиться и спросить
   человека.
4. Проверить, что `<input type=file>` открывает выбор фото/камеру в
   мобильном MAX. Если нет — дальше в S16 делать вариант «фото через чат»
   (описан там).
5. Если HMAC не сходится на реальных данных: проверить, что значения
   декодируются ровно один раз и `hash` исключён; если всё равно не
   сходится — **остановиться и спросить человека**, не ослаблять проверку.

**Критерий готовности:** пункты 2–4 смоука выполнены; результат (совпал ли
id, работает ли file input) записан в описание PR.

**Коммит:** `feat(webapp): каркас мини-приложения и деплой на Pages`

---

### S7. Демо-справочник: дома, УК, основания (T-10, FR-E2)

**Файлы:** `backend/src/main/kotlin/mkd/Seed.kt`, правка `Application.kt`.

```kotlin
object Seed { fun run() }   // внутри transaction; ничего не делает, если Houses не пуст
```

Данные (все с `isDemo = true`; в интерфейсе и PDF выводить пометку «демо»):

- УК: `ООО «УК Демо-Сервис»`, ИНН `0000000000`, лицензия `№ 000-демо`,
  представитель `Генеральный директор Иванов И. И. (демо)`, способ обмена
  `email: uk-demo@example.ru`.
- Дома (все с этой УК): `г. Казань, ул. Демонстрационная, д. 1`,
  `… д. 2`, `… д. 3`. Три дома — чтобы несколько проверяющих не мешали друг
  другу.
- Основания (`Grounds`). Справочник демонстрационный, в README и в
  `legalRef` не выдавать его за проверенную юридическую позицию;
  `demandTemplate` у всех одинаковый:
  `Устранить недостатки выполнения работ «{item}» либо исключить невыполненный объём из акта и направить новый акт в порядке п. 6 Порядка, утв. приказом Минстроя России № 318/пр.`

| workKind | workKindTitle | legalRef | wording |
|---|---|---|---|
| `CLEANING` | Уборка мест общего пользования | Договор управления МКД; п. 23 Минимального перечня услуг и работ, утв. постановлением Правительства РФ от 03.04.2013 № 290 | Работы по содержанию помещений общего имущества, включая уборку, выполняются с периодичностью, установленной договором управления |
| `YARD` | Содержание придомовой территории | Договор управления МКД; пп. 24–25 Минимального перечня № 290 | Работы по содержанию придомовой территории в холодный и тёплый период года выполняются с установленной периодичностью |
| `WASTE` | Содержание контейнерных площадок | Договор управления МКД; п. 26(1) Минимального перечня № 290 | Работы по содержанию мест накопления твёрдых коммунальных отходов выполняются в соответствии с установленными требованиями |
| `ROOF` | Содержание и ремонт крыши | Договор управления МКД; п. 7 Минимального перечня № 290 | Работы по надлежащему содержанию крыш выполняются в объёме, предусмотренном договором |
| `ELEVATOR` | Содержание лифтов | Договор управления МКД; п. 22 Минимального перечня № 290 | Работы по надлежащему содержанию лифтов выполняются в объёме, предусмотренном договором |
| `ENGINEERING` | Внутридомовые инженерные системы | Договор управления МКД; пп. 17–19 Минимального перечня № 290 | Работы по содержанию систем водоснабжения, отопления и водоотведения выполняются в объёме, предусмотренном договором |
| `OTHER` | Прочие работы | Договор управления МКД; ч. 2 ст. 162 ЖК РФ; Минимальный перечень № 290 | Услуги и работы по содержанию и ремонту общего имущества оказываются в объёме и с качеством, предусмотренными договором управления |

`Application.kt`: `Seed.run()` сразу после `Db.init`.

**Критерий готовности:** после старта в `houses` 3 строки, в `grounds` 7;
повторный старт не дублирует.

**Коммит:** `feat(db): демо-справочник домов, УК и оснований`

---

### S8. Регистрация и роли в боте (T-09, FR-A1–A4, NFR-2)

**Файлы:** `Bot.kt` (основная работа), `Acts.kt` (создать файл, пока с
`rolesOf` если не положил её в `Api.kt`).

Состояние диалога — в памяти (теряется при рестарте, это допустимо):
```kotlin
sealed interface Pending {
    data class ChairFio(val houseId: Long) : Pending
    data class ChairBasis(val houseId: Long, val fio: String) : Pending
    data class ReceiptDate(val actId: Long) : Pending     // S9
}
private val pending = ConcurrentHashMap<Long, Pending>()
```

Callback-payload'ы (полный список на весь проект, формат `команда:аргумент`):
`consent`, `house:{houseId}`, `role_res:{houseId}`, `role_chair:{houseId}`,
`approve:{chairmanRowId}`, `rcv_today:{actId}`, `rcv_other:{actId}`,
`status`, `close:{actId}`, `sign:{actId}`, `sign_ok:{actId}`,
`refuse:{actId}`, `sent:{actId}`. На **каждый** колбэк — `answerCallback`
(короткий текст или `"ок"`).

Диалог:
1. `bot_started` или текст `/start`:
   - если пользователя нет в `Users` или `pdConsentAt == null` → текст
     «Бот помогает совету дома принять или обоснованно отклонить акт работ
     УК. Мы храним ваше имя в MAX, привязку к дому и ваши отметки по акту.
     Нажимая кнопку, вы соглашаетесь на обработку этих данных.» + кнопка
     `[Согласен]` (`consent`);
   - иначе → меню (п. 5).
2. `consent` → upsert `Users` (id, name = `displayName()`, `pdConsentAt = now`,
   `createdAt`), событие `USER_REGISTERED` → «Выберите ваш дом:» + по кнопке
   на каждый дом (`house:{id}`, текст — адрес + « (демо)»).
3. `house:{id}` → «Кто вы?» + `[Житель]` (`role_res:{id}`) `[Председатель совета дома]` (`role_chair:{id}`).
4. `role_res:{id}` → `Users.houseId = id` → меню.
   `role_chair:{id}` → `Users.houseId = id`, `pending = ChairFio(id)` →
   «Напишите ваши ФИО полностью — они будут в документах».
   Текст при `ChairFio` → `pending = ChairBasis` → «Чем подтверждены ваши
   полномочия? Например: протокол общего собрания № 5 от 01.03.2026».
   Текст при `ChairBasis` → вставить `Chairmen` (fullName, authorityBasis,
   `confirmedAt = if (cfg.adminUserIds.isEmpty()) now else null`), событие
   `CHAIRMAN_REGISTERED`:
   - если подтверждено сразу → «Готово, вы председатель дома …» + меню;
   - иначе → «Заявка отправлена оператору» + каждому админу сообщение
     «Заявка председателя: {ФИО}, {адрес}, основание: {…}» + `[Подтвердить]`
     (`approve:{chairmanRowId}`). `approve` (только от id из
     `adminUserIds`) → `confirmedAt = now`, `CHAIRMAN_CONFIRMED`, сообщение
     председателю.
5. Меню (текст `/menu` или после регистрации) — по ролям:
   - председатель: «Чтобы начать, пришлите сюда файл акта (PDF или фото).»
     + `[Статус акта]` (`status`) + если есть активный акт —
     `[Открыть акт]` (link-кнопка, см. ниже);
   - житель: `[Статус акта]` + если активный акт в `COLLECTING` —
     `[Отметить работы]` (link).

Link на мини-апп (единая функция, используется во всех шагах):
```kotlin
fun appLink(startParam: String) = "https://max.ru/$botUsername?startapp=$startParam"   // botUsername — из me() (S4)
// startParam: "act_{id}" или "refusal_{id}" (только [A-Za-z0-9_-])
```

**Критерий готовности:** в MAX: новый пользователь проходит согласие →
дом → «Житель» и видит меню жителя; другой (или тот же после удаления
строки) — «Председатель» с ФИО и основанием, видит меню председателя;
`/api/me` из мини-аппа возвращает `roles = ["RESIDENT","CHAIRMAN"]` для
председателя. С непустым `ADMIN_USER_IDS` роль `CHAIRMAN` появляется только
после нажатия `[Подтвердить]` админом.

**Коммит:** `feat(bot): регистрация пользователя и выбор роли`

---

### S9. Загрузка акта, дата получения, контрольные даты (T-11, T-12, FR-B1, FR-B2, FR-C1)

**Файлы:** `Acts.kt`, `Bot.kt`, `backend/src/test/kotlin/mkd/DeadlinesTest.kt`.

Чистые функции сроков в `Acts.kt` (без БД — их тестируем):
```kotlin
object Deadlines {
    val MILESTONES = listOf(0, 7, 10, 25, 28, 29)                    // FR-C3
    fun day10(receivedAt: Instant, zone: ZoneId): LocalDate = receivedAt.atZone(zone).toLocalDate().plusDays(10)
    fun day30(receivedAt: Instant, zone: ZoneId): LocalDate = receivedAt.atZone(zone).toLocalDate().plusDays(30)
    // молчаливое согласие наступает в начале дня, следующего за T0+30
    fun silentAt(deadline30: LocalDate, zone: ZoneId): Instant = deadline30.plusDays(1).atStartOfDay(zone).toInstant()
    // момент уведомления: d=0 — сразу; иначе 10:00 местного времени в день T0+d
    fun milestoneAt(receivedAt: Instant, d: Int, zone: ZoneId): Instant =
        if (d == 0) receivedAt else receivedAt.atZone(zone).toLocalDate().plusDays(d.toLong()).atTime(10, 0).atZone(zone).toInstant()
    // сколько календарных дней осталось до конца дня `until` (0 = сегодня последний день, <0 = прошёл)
    fun daysLeft(until: LocalDate, now: Instant, zone: ZoneId): Long = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), until)
}
```

`ActService` (в `Acts.kt`):
```kotlin
class ActService(private val cfg: Config, private val max: MaxBotClient) {
    suspend fun activeAct(houseId: Long): ResultRow?        // последний акт дома в RECEIVED/COLLECTING/REVIEW
    suspend fun createFromUpload(userId: Long, houseId: Long, bytes: ByteArray, fileName: String, mime: String,
                                 receivedAt: Instant, mid: String): Long
    suspend fun setReceiptDate(actId: Long, userId: Long, date: LocalDate)
    suspend fun requireChairmanOf(actId: Long, userId: Long): ResultRow  // иначе ApiError(403,"forbidden",...)
    suspend fun requireMemberOf(actId: Long, userId: Long): ResultRow    // житель или председатель дома акта, иначе 403
}
```
`createFromUpload`:
- сохранить файл в `{FILES_DIR}/acts/{UUID}.{pdf|jpg|png}` (каталоги
  создавать `Files.createDirectories`);
- вставить `Acts`: `status = RECEIVED`, `recognition = PENDING`,
  `receivedAt`, `deadline10/30` по `Deadlines`, `round = 1`;
- события `ACT_RECEIVED` (details: `mid=…; uploadedAt=…; file=…`) — это и
  есть подтверждение получения (FR-B2, NFR-4).

`setReceiptDate`: дата не в будущем и не раньше чем 30 дней назад (иначе
`ApiError(400, …)`/ответ в боте); `receivedAt = date.atTime(12,0).atZone(zone)`,
пересчитать `deadline10/30`, событие `RECEIPT_DATE_SET`.

`Bot.kt` — `message_created` с вложением `file` или `image`:
1. Не председатель (подтверждённый) → «Загружать акт может только
   председатель совета дома.»
2. Есть активный акт по дому → «Сначала завершите текущий акт № … (подпишите
   или направьте отказ).»
3. Скачать `payload.url`, `createFromUpload(receivedAt = Instant.ofEpochMilli(message.timestamp))`.
4. Ответ: «Акт получен {dd.MM.yyyy HH:mm}. Когда вы получили этот акт от
   УК?» + `[Сегодня]` (`rcv_today:{id}`) `[Другая дата]` (`rcv_other:{id}`).
   `rcv_today` → ничего не менять, ответить статусом (S15; до S15 —
   просто датами дедлайнов). `rcv_other` → `pending = ReceiptDate(id)` →
   «Напишите дату в формате ДД.ММ.ГГГГ»; текст парсить
   `DateTimeFormatter.ofPattern("dd.MM.yyyy")`, при ошибке — переспросить.
5. После ответа на п. 4 записать событие `NOTIFY_D0` (стартовое
   уведомление = этот ответ; таймер его не дублирует).
6. Запустить распознавание (S10) в `scope.launch` — на этом шаге заглушка
   `recognition = FAILED`.

`DeadlinesTest`: `receivedAt = 2026-09-24T09:00+03:00`, зона Москва →
`day10 = 2026-10-04`, `day30 = 2026-10-24`,
`silentAt = 2026-10-25T00:00+03:00`, `milestoneAt(…, 7) = 2026-10-01T10:00+03:00`,
`daysLeft(2026-10-04, now=2026-10-04T15:00+03:00) = 0`.

**Критерий готовности:** тесты зелёные; в MAX председатель отправляет PDF →
в `acts` строка с правильными датами, файл лежит в `FILES_DIR/acts`, в
`events` — `ACT_RECEIVED`; «Другая дата» `20.09.2026` пересчитывает
`deadline10 = 2026-09-30`; житель при отправке файла получает отказ.

**Коммит:** `feat(bot): загрузка акта и фиксация даты получения`

---

### S10. GigaChat + распознавание позиций (T-11, FR-B1.1)

**Файлы:** `GigaChatClient.kt`, `Acts.kt` (метод распознавания),
`backend/src/main/resources/fonts/DejaVuSans.ttf`, `…/DejaVuSans-Bold.ttf`,
`backend/src/test/kotlin/mkd/DemoActPdfTest.kt`, `demo/act-demo.pdf`.

Шрифты (нужны здесь для демо-акта и дальше в S19/S21): из архива
`https://github.com/dejavu-fonts/dejavu-fonts/releases/download/version_2_37/dejavu-fonts-ttf-2.37.zip`
(свободная лицензия) положить `DejaVuSans.ttf` и `DejaVuSans-Bold.ttf` в
`backend/src/main/resources/fonts/`. Загрузка в OpenPDF — только так
(байты из classpath):
```kotlin
fun font(name: String, size: Float): Font {
    val bytes = Pdf::class.java.getResourceAsStream("/fonts/$name")!!.readBytes()
    return Font(BaseFont.createFont(name, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, bytes, null), size)
}
```
Положи эту функцию в новый `Pdf.kt` (`object Pdf { … }`), S19 его расширит.

```kotlin
class GigaChatClient(private val authKey: String, private val scope: String, private val model: String) {
    // OAuth: POST https://ngw.devices.sberbank.ru:9443/api/v2/oauth
    //   headers: Authorization: Basic {authKey}, RqUID: {UUID}, Content-Type: application/x-www-form-urlencoded
    //   body: scope={scope} → {"access_token": "...", "expires_at": <unix ms>}
    //   кэшировать токен до expires_at - 60 с (Mutex, чтобы не запрашивать параллельно)
    // База API: https://gigachat.devices.sberbank.ru/api/v1, заголовок Authorization: Bearer {access_token}
    val enabled: Boolean get() = authKey.isNotBlank()
    suspend fun chat(system: String, user: String, attachments: List<String> = emptyList()): String
        // POST /chat/completions {"model":model,"temperature":0.1,
        //   "messages":[{"role":"system","content":system},{"role":"user","content":user,"attachments":[...]}]}
        // → choices[0].message.content. Поле attachments не слать, если список пуст.
    suspend fun uploadFile(bytes: ByteArray, fileName: String, mime: String): String
        // POST /files multipart: file=<bytes>, purpose=general → {"id": "..."}
}
```
HttpTimeout 60 с. Если `!enabled` — все вызовы сразу кидают
`IllegalStateException("GigaChat не настроен")` (обрабатывается как
недоступность LLM).

Распознавание (`ActService.recognize(actId)`, запускается из бота в фоне):
- PDF → текст: `PdfReader(bytes)` + `PdfTextExtractor(reader).getTextFromPage(i)`
  по всем страницам; `chat(SYSTEM_RECOGNIZE, "Текст акта:\n$text")`.
  Если текста < 50 символов (скан) — как с фото.
- Фото → `uploadFile` → `chat(SYSTEM_RECOGNIZE, "Распознай акт на изображении.", listOf(fileId))`.
- `SYSTEM_RECOGNIZE`:
  ```
  Ты извлекаешь данные из акта приёмки оказанных услуг и выполненных работ по содержанию и
  текущему ремонту общего имущества МКД (форма приказа Минстроя № 761/пр).
  Верни ТОЛЬКО JSON без пояснений в формате:
  {"number":"...","formedDate":"YYYY-MM-DD","period":"...","items":[{"lineNo":1,"name":"...",
  "periodicity":"...","volume":"...","cost":"...","workKind":"..."}]}
  workKind — один код из списка: CLEANING, YARD, WASTE, ROOF, ELEVATOR, ENGINEERING, OTHER.
  Если поля нет в акте — пустая строка. Ничего не придумывай.
  ```
  (список кодов собирать из таблицы `Grounds`, а не хардкодить).
- Разбор ответа: вырезать от первой `{` до последней `}`, `AppJson.decodeFromString<RecognizedAct>(…)`;
  `workKind` не из справочника → `OTHER`; `formedDate` не парсится → `null`.
- Успех: записать `number/period/formedDate`, вставить `ActItems`,
  `recognition = DONE`, событие `RECOGNIZED` (details: `items=N`). Ошибка
  (любая) → `recognition = FAILED`, событие `RECOGNITION_FAILED`.
- В обоих случаях сообщение председателю: «Распознано позиций: N. Проверьте
  их и откройте сбор замечаний жителей.» / «Не удалось распознать акт
  автоматически — введите позиции вручную, это займёт пару минут.» + link
  `[Открыть акт]` (`appLink("act_$id")`).

Демо-акт `demo/act-demo.pdf` генерируется один раз тестом-генератором
`DemoActPdfTest` (тело теста выполняется только при `GEN_DEMO=1`, иначе
сразу `return` — чтобы обычный `./gradlew test` не писал файлов):
`GEN_DEMO=1 ./gradlew test --tests mkd.DemoActPdfTest` → пишет
`../demo/act-demo.pdf`. Содержимое (OpenPDF, шрифт через `Pdf.font`):
заголовок «Акт приёмки оказанных услуг и (или) выполненных работ по
содержанию и текущему ремонту общего имущества в многоквартирном доме»,
«№ 12 от 30.09.2026, период: сентябрь 2026», «Адрес: г. Казань, ул.
Демонстрационная, д. 1», «Исполнитель: ООО «УК Демо-Сервис»», таблица
(№, наименование, периодичность, ед. изм./объём, стоимость, руб.) из 6
строк: влажная уборка лестничных клеток — 1 раз в неделю — 1 200 м² —
18 000; уборка придомовой территории — ежедневно — 2 500 м² — 25 000;
содержание контейнерной площадки — ежедневно — 1 шт. — 4 000; осмотр
кровли — 2 раза в год — 900 м² — 3 500; техническое обслуживание лифта —
ежемесячно — 3 шт. — 21 000; осмотр системы отопления — 1 раз в месяц —
1 система — 6 000; внизу пометка «ДЕМОНСТРАЦИОННЫЙ ДОКУМЕНТ». Сам PDF
закоммитить.

**Критерий готовности:** с ключом GigaChat загрузка `demo/act-demo.pdf` в
бота → через ≤30 с приходит «Распознано позиций: 6», в `act_items` 6 строк
с осмысленными `workKind`. С пустым `GIGACHAT_AUTH_KEY` → приходит
сообщение про ручной ввод, приложение не падает.

**Коммит:** `feat(llm): распознавание позиций акта через GigaChat`

---

### S11. Карточка акта в мини-аппе: правка позиций и старт сбора (T-11, FR-B1.2, FR-B4)

**Файлы:** `Api.kt`, `Acts.kt`, `webapp/app.js`, `webapp/style.css`.

DTO (единые для всех последующих шагов; поля, которые появляются позже,
заполняй `null`/нулями до своего шага):
```kotlin
@Serializable data class WorkKindDto(val code: String, val title: String)
@Serializable data class PhotoDto(val id: Long, val url: String)                 // url = "/api/photos/{id}"
@Serializable data class StatsDto(val ok: Int, val issue: Int, val issueWithPhoto: Int)
@Serializable data class MyRemarkDto(val verdict: String, val text: String?, val photos: List<PhotoDto>)
@Serializable data class RemarkDto(val id: Long, val verdict: String, val text: String?, val formalized: String?,
                                   val llmStatus: String, val photos: List<PhotoDto>)   // без автора (минимизация ПДн)
@Serializable data class ItemDto(
    val id: Long, val lineNo: Int, val name: String, val periodicity: String, val volume: String, val cost: String,
    val workKind: String, val decision: String?,
    val stats: StatsDto,
    val my: MyRemarkDto?,              // только если пользователь житель дома
    val remarks: List<RemarkDto>?,     // только председателю; жителю — null (FR-D5)
)
@Serializable data class ActDto(
    val id: Long, val houseAddress: String, val number: String?, val formedDate: String?, val period: String?,
    val actType: String, val status: String, val recognition: String,
    val receivedAt: String, val deadline10: String, val deadline30: String,
    val daysLeft10: Long, val daysLeft30: Long,             // считает сервер (S15)
    val isChairman: Boolean, val isResident: Boolean,
    val items: List<ItemDto>, val workKinds: List<WorkKindDto>,
    val hasRefusal: Boolean,
)
@Serializable data class ItemInput(val id: Long? = null, val lineNo: Int, val name: String, val periodicity: String = "",
                                   val volume: String = "", val cost: String = "", val workKind: String = "OTHER")
@Serializable data class CardInput(val number: String?, val formedDate: String?, val period: String?, val items: List<ItemInput>)
```

Эндпоинты:
```
GET  /api/acts/{id}                    член дома        → ActDto
PUT  /api/acts/{id}/card               председатель     CardInput → ActDto
     только status=RECEIVED, иначе 409 "card_locked";
     semantics replace-all: позиции без id вставить, с id — обновить, отсутствующие в списке — удалить;
     name не пустой, lineNo уникальны, иначе 400; событие CARD_EDITED
POST /api/acts/{id}/open-collection    председатель     → ActDto
     RECEIVED → COLLECTING; нужна ≥1 позиция, иначе 409 "no_items"; событие COLLECTION_OPENED;
     рассылка всем Users с houseId дома акта (кроме самого председателя):
     «Председатель открыл проверку акта работ УК за {period}. Отметьте, что сделано, а что нет —
      это займёт 2 минуты. Последний день приёма замечаний — {deadline30}.» + link [Отметить работы] (act_{id})
     рассылка последовательно, между сообщениями delay(50); ошибка отправки одному не прерывает рассылку
```

Мини-апп:
- Какой акт открыть: `start_param` вида `act_{id}` или `refusal_{id}`
  (`WA.initDataUnsafe.start_param`, при отладке — `?startapp=act_1`);
  если нет — `me.activeActId`; если и его нет — текст «Активного акта нет».
  Незарегистрированному (`me.registered=false`) — «Сначала напишите боту
  /start».
- Шапка (`#header`): адрес дома, «Акт № … за …», статус по-русски
  (`RECEIVED` «Получен», `COLLECTING` «Идёт сбор замечаний», `REVIEW`
  «Решение председателя», `SIGNED` «Подписан», `REJECTED` «Отказ направлен»,
  `SILENT` «Принят молчаливым согласием»), счётчики — S15.
- Экран «Карточка» (председатель, `RECEIVED`): поля номер/дата/период;
  список позиций с полями и `<select>` вида работ из `workKinds`; кнопки
  «+ позиция», «удалить» у строки, «Сохранить» (PUT card),
  «Открыть сбор замечаний» (сначала сохранить, потом POST open-collection,
  с `confirm()`-подтверждением «После открытия позиции нельзя будет
  менять»). Если `recognition = PENDING` — «Акт распознаётся…» и кнопка
  «Обновить».
- Житель при `RECEIVED` — «Председатель ещё готовит акт к проверке».

**Критерий готовности:** из бота кнопкой открыть акт → видны 6 позиций →
изменить название, добавить 7-ю, удалить одну, сохранить → перезагрузка
показывает изменения; «Открыть сбор» → статус «Идёт сбор замечаний», у
жителя дома в MAX пришло сообщение со ссылкой; повторный PUT card → 409.
Житель чужого дома получает 403 на `GET /api/acts/{id}`.

**Коммит:** `feat(webapp): редактирование позиций акта и старт сбора замечаний`

---

### S12. Движок сроков (T-14, FR-C1, FR-C5, NFR-5)

**Файлы:** `Timers.kt`, `backend/src/test/kotlin/mkd/TimersTest.kt`,
правка `Application.kt`.

Чистая функция планирования (тестируется без БД):
```kotlin
data class NotifyPlan(val skip: List<String>, val send: String?)   // значения — типы событий "NOTIFY_D7" и т.п.

// sent — множество уже записанных NOTIFY_* событий акта
fun planNotifications(receivedAt: Instant, sent: Set<String>, now: Instant, zone: ZoneId): NotifyPlan {
    val due = Deadlines.MILESTONES.filter { now >= Deadlines.milestoneAt(receivedAt, it, zone) }
        .map { "NOTIFY_D$it" }.filter { it !in sent }
    if (due.isEmpty()) return NotifyPlan(emptyList(), null)
    return NotifyPlan(due.dropLast(1), due.last())   // пропущенные старые не шлём, шлём только самое свежее
}
```

```kotlin
class TimerService(private val cfg: Config, private val max: MaxBotClient, private val clock: Clock = Clock.systemUTC()) {
    suspend fun loop()                 // while (true) { runCatching { tick() }.onFailure { log.error } ; delay(60_000) }
    suspend fun tick(now: Instant = clock.instant())
}
```
`tick` (порядок важен — переход статуса не зависит от доставки
уведомлений, FR-C5):
1. Для каждого акта в `RECEIVED/COLLECTING/REVIEW`: если
   `now >= Deadlines.silentAt(deadline30)` → переход в `SILENT`. Сам
   переход реализуется в S14; на этом шаге пункт 1 не пиши.
2. Для каждого акта в `RECEIVED/COLLECTING/REVIEW`: `sent` = типы событий
   `NOTIFY_%` этого акта; `plan = planNotifications(...)`; для каждого из
   `plan.skip` — `logEvent(actId, it, null, "skipped")`; если
   `plan.send != null` — отправить (S13) и **только при успехе**
   `logEvent(actId, plan.send, null, "sent")`. При ошибке отправки событие
   не пишется → повтор на следующем тике.

`Application.kt`: `scope.launch { timers.loop() }`.

`TimersTest`: T0 = 2026-09-24T09:00 MSK;
- now = T0+1ч, sent = {} → send = `NOTIFY_D0`;
- now = 2026-10-01T10:05, sent = {D0} → send = `NOTIFY_D7`, skip = [];
- now = 2026-10-20T10:05 (сервер «спал»; D25 = 2026-10-19 10:00 уже наступил, D28 = 2026-10-22 ещё нет), sent = {D0} → skip = [`NOTIFY_D7`, `NOTIFY_D10`], send = `NOTIFY_D25`;
- now = 2026-10-01T09:59, sent = {D0} → send = null.

**Критерий готовности:** тесты зелёные; приложение стартует, в логе раз в
минуту нет ошибок тика.

**Коммит:** `feat(timers): движок контрольных сроков`

---

### S13. Проактивные уведомления 0–7–10–25–28–29 (T-16, FR-C3)

**Файлы:** `Timers.kt`, `Acts.kt` (функция текста статуса — появится в S15,
здесь используй даты напрямую).

Получатели: все подтверждённые председатели дома акта. Кнопки в каждом
уведомлении: `[Открыть акт]` (link `act_{id}`) + `[Статус]` (`status`).

Тексты (`{d10}`, `{d30}` — `dd.MM.yyyy`; `{n30}` — `Deadlines.daysLeft(deadline30, now)`):

| Событие | Текст |
|---|---|
| `NOTIFY_D0` | пишется ботом в S9 (ответ на загрузку), таймер его только «видит» |
| `NOTIFY_D7` | «Через 3 дня, {d10}, заканчивается срок по приказу на подписание или отказ. Посмотрите замечания жителей и примите решение.» |
| `NOTIFY_D10` | «Сегодня, {d10}, последний день срока по приказу. Если не успеваете — подписать или отказать ещё можно до {d30}; после этого акт будет считаться подписанным без ваших возражений.» |
| `NOTIFY_D25` | «Осталось дней: {n30}. После {d30} акт будет считаться подписанным без ваших возражений.» |
| `NOTIFY_D28` | «Внимание: осталось дней: {n30}. После {d30} акт будет считаться подписанным.» |
| `NOTIFY_D29` | «Внимание: завтра, {d30}, последний день. Если ничего не сделать, акт будет считаться подписанным.» |

Отправка — `max.sendText` каждому председателю; успех = хотя бы одному
отправилось.

**Критерий готовности:** загрузить акт → в БД `NOTIFY_D0`; в `psql`
сдвинуть `received_at` на 8 дней назад (`update acts set received_at = received_at - interval '8 days'`)
→ в течение минуты приходит D7; повторно не приходит. (Удобнее после S14
через `/shift`.)

**Коммит:** `feat(timers): проактивные уведомления председателю`

---

### S14. Молчаливое согласие на 30-й день + `/shift` для демо (T-17, FR-C4)

**Файлы:** `Timers.kt`, `Acts.kt`, `Bot.kt`.

В `tick`, п. 1: `status = SILENT`, `logEvent(actId, "SILENT_CONSENT", null)`
— **в одной транзакции**, до любых отправок. Затем уведомление (с тем же
механизмом «событие только при успехе» — тип `NOTIFY_SILENT`): всем
председателям и жителям дома — «Срок 30 дней истёк {d30}. Акт № … за …
считается подписанным (молчаливое согласие, п. 5 Порядка, приказ Минстроя
№ 318/пр).». Для этого в п. 2 `tick` дополнительно обрабатывай акты в
`SILENT` без события `NOTIFY_SILENT`.

`ActService.demoShift(actId: Long, userId: Long, days: Int)`: только если
`cfg.demoMode`, только председатель, `days in 1..40`;
`receivedAt -= days`, пересчитать `deadline10/30`, событие
`DEMO_SHIFT` (details `days=N`), затем сразу `timers.tick()`.

Бот: текст `/shift N` от председателя с активным актом → `demoShift` →
ответ статусом (S15). При `DEMO_MODE=false` — «Команда доступна только в
демо-режиме».

Замечание: `/shift 31` на акте в `COLLECTING` → акт `SILENT`, уведомления
D7…D29 помечаются `skipped`, приходит только уведомление о молчаливом
согласии.

**Критерий готовности:** `/shift 8` → приходит D7-уведомление; `/shift 23`
(итого 31) → статус «Принят молчаливым согласием», пришло финальное
уведомление, в `events` есть `SILENT_CONSENT`; из `SILENT` нельзя подписать/
отказать (кнопки не показываются, API → 409).

**Коммит:** `feat(timers): молчаливое согласие и демо-сдвиг срока`

---

### S15. Обратный отсчёт в боте и мини-аппе (T-15, FR-C2, FR-B4)

**Файлы:** `Acts.kt`, `Bot.kt`, `Api.kt`, `webapp/app.js`.

```kotlin
// Acts.kt
fun statusText(act: ResultRow, address: String, now: Instant, zone: ZoneId): String
fun statusButtons(cfg: Config, act: ResultRow, isChairman: Boolean, isResident: Boolean): List<List<Button>>
```
`statusText` (для `RECEIVED/COLLECTING/REVIEW`):
```
Акт № {number ?: "без номера"} за {period ?: "—"}, {address}
Статус: {статус по-русски}
Срок по приказу (10 дней): до {d10} — осталось дней: {n10}
Защитный срок (30 дней): до {d30} — осталось дней: {n30}
```
Если `n10 < 0` — вторая строка срока заменяется на: «Срок по приказу истёк
{d10}, но акт ещё не считается принятым — решение можно принять до {d30}.»
Для `SIGNED/REJECTED/SILENT` — только первые две строки + дата события.

`statusButtons`:
- все: `[Открыть акт]` (link `act_{id}`), если статус не терминальный;
- председатель, `COLLECTING`: `[Завершить сбор замечаний]` (`close:{id}`);
- председатель, `COLLECTING/REVIEW`: `[Подписать]` (`sign:{id}`), `[Сформировать отказ]` (`refuse:{id}`).

Бот: колбэк `status` и текст `/status` → активный (или последний) акт дома →
`statusText` + `statusButtons`. `rcv_today`/`rcv_other`/`/shift` отвечают
этим же статусом. Колбэк `close:{id}` → `ActService.closeCollection` (S18).

`ActDto.daysLeft10/30` — через `Deadlines.daysLeft` с серверным `now`.
Мини-апп: в шапке две строки-счётчика теми же формулировками (текст
собирать на клиенте из `deadline10/30`, `daysLeft10/30`).

**Критерий готовности:** `/status` у председателя показывает оба счётчика
и кнопки по статусу; у жителя — счётчики без кнопок подписи/отказа
(FR-A2); после `/shift 11` текст про «срок по приказу истёк, но акт ещё не
принят»; в мини-аппе те же числа.

**Коммит:** `feat(timers): обратный отсчёт 10/30 дней в боте и мини-приложении`

---

### S16. Чек-лист жителя и фото (T-18, T-19, FR-D1–D5)

**Файлы:** `Remarks.kt`, `Api.kt`, `webapp/app.js`, `webapp/style.css`.

```kotlin
class RemarkService(private val cfg: Config, private val giga: GigaChatClient, private val scope: CoroutineScope) {
    suspend fun saveMy(itemId: Long, userId: Long, verdict: Verdict, text: String?): MyRemarkDto
    suspend fun addPhoto(itemId: Long, userId: Long, bytes: ByteArray, mime: String): PhotoDto
    suspend fun photoFile(photoId: Long, userId: Long): Pair<File, String>   // файл + mime
    fun formalizeAsync(remarkId: Long)                                        // S17
}
```

Эндпоинты:
```
PUT  /api/items/{itemId}/my-remark      житель дома    {"verdict":"OK"|"ISSUE","text":"..."} → MyRemarkDto
     акт только в COLLECTING, иначе 409 "collection_closed";
     ISSUE требует text длиной 3..1000, иначе 400; OK — text=null и удалить фото этого замечания;
     upsert по (itemId, authorId); событие REMARK_SAVED (details: itemId, verdict)
POST /api/items/{itemId}/my-remark/photos   житель дома    multipart, поле "photo", image/jpeg|image/png, ≤10 МБ → PhotoDto
     требует существующее замечание ISSUE, иначе 409 "no_issue"; не более 5 фото на замечание;
     файл → {FILES_DIR}/photos/{UUID}.jpg|png; Attachments(uploadedAt=now, authorId); событие PHOTO_ADDED
GET  /api/photos/{id}                   автор фото или председатель дома → байты с Content-Type
```
Multipart в Ktor 3: `call.receiveMultipart(formFieldLimit = 10L * 1024 * 1024).forEachPart { p -> if (p is PartData.FileItem && p.name == "photo") bytes = p.provider().readRemaining().readByteArray(); p.dispose() }`.

`GET /api/acts/{id}` теперь заполняет `stats` (по всем замечаниям позиции:
`ok`, `issue`, `issueWithPhoto` = ISSUE с ≥1 фото) и `my`.

Мини-апп, экран «Чек-лист» (житель, `COLLECTING`):
- Карточка на позицию: номер, название, периодичность; строка агрегата
  «{issue} из {ok+issue} ответивших: есть претензия» (только числа, без
  имён — FR-D5); две кнопки `Выполнено` / `Есть претензия` (активная
  подсвечена по `my.verdict`).
- `Есть претензия` → раскрыть `<textarea>` (подсказка: «Что именно не так?
  Например: в подъезде 2 не мыли пол с 10 сентября») + кнопка
  «Добавить фото» (`<input type=file accept="image/*" capture="environment">`,
  скрытый) + миниатюры своих фото + «Сохранить».
- Перед загрузкой фото уменьшать на клиенте: `createImageBitmap(file)` →
  canvas с длинной стороной ≤1600 px → `canvas.toBlob(cb, "image/jpeg", 0.8)`
  → `FormData.append("photo", blob, "photo.jpg")`.
- Картинки грузить через `api`-заголовки: `fetch(API_BASE + url, {headers})`
  → `URL.createObjectURL(await r.blob())` (обычный `<img src>` не
  передаст initData).
- Внизу подсказка: «Претензия с фото — самый сильный аргумент для отказа.
  Без фото председатель не сможет её заявить.» (FR-E5 объяснение).
- При `REVIEW/SIGNED/REJECTED/SILENT` — чек-лист только для чтения с
  надписью «Сбор замечаний закрыт».

**Запасной вариант (только если в S6 `<input type=file>` не работал в
мобильном MAX):** вместо кнопки «Добавить фото» — кнопка «Отправить фото
через чат». По нажатию: вызвать
`POST /api/items/{itemId}/my-remark/await-photo` (сервер кладёт
`pending[userId] = Pending.Photo(itemId)`; для этого `Bot.pending` сделать
`internal` и передать `Bot` в `api(...)`), показать текст «Закройте окно
и отправьте фото боту одним сообщением», затем `WA.close()`. В боте
вложение `image` при `Pending.Photo` → скачать → `RemarkService.addPhoto`
→ ответ «Фото добавлено к замечанию». Добавить `data class Photo(val itemId: Long) : Pending`.

**Критерий готовности:** житель отмечает 2 позиции «Выполнено», 1 —
«Есть претензия» с текстом и фото → после перезагрузки мини-аппа всё на
месте; фото открывается у автора, у другого жителя `GET /api/photos/{id}`
→ 403; агрегат по позиции показывает верные числа у второго жителя; в
`events` есть `REMARK_SAVED`, `PHOTO_ADDED`. Проверено в мобильном и
веб-MAX.

**Коммит:** `feat(remarks): чек-лист позиций и фото от жителей`

---

### S17. Формализация замечаний LLM (T-20, FR-E1, NFR-6, NFR-9)

**Файлы:** `Remarks.kt`, `webapp/app.js`.

- В `saveMy` при `ISSUE` и изменившемся тексте: `formalizedText = null`,
  `llmStatus = PENDING`, после транзакции `formalizeAsync(remarkId)`.
- `formalizeAsync` = `scope.launch { formalize(remarkId) }`;
  `suspend fun formalize(remarkId: Long)`:
  ```
  system: Ты помогаешь оформить замечание жителя к акту работ управляющей компании.
  Перепиши замечание одним-двумя предложениями в официально-деловом стиле, описав только
  фактическое положение: что не выполнено или выполнено с недостатками, где и когда (если указано).
  Не добавляй нормативных ссылок, оценок, требований и фактов, которых нет в тексте.
  Верни только переписанный текст.
  user: Позиция акта: «{item.name}» ({item.periodicity}).
        Замечание жителя: {originalText}
  ```
  Успех → `formalizedText = ответ.trim()`, `llmStatus = DONE`. Ошибка/LLM
  выключен → `llmStatus = FAILED` (исходный текст остаётся — система
  деградирует, а не падает, NFR-9).
- `RemarkDto.formalized` / `llmStatus` отдаются председателю; в мини-аппе у
  председателя: `PENDING` → «обрабатывается…» (индикатор, NFR-6),
  `FAILED` → показывать исходный текст с пометкой «без обработки».
- `suspend fun ensureFormalized(actId: Long)` — для всех ISSUE-замечаний
  акта с `llmStatus != DONE` синхронно `withTimeoutOrNull(20_000) { formalize(id) }`;
  вызывается при сборке черновика отказа (S20).

**Критерий готовности:** житель сохраняет «мусор у 3 подъезда не убирают
неделю» → через несколько секунд у председателя в мини-аппе формализованный
текст вида «Уборка территории у подъезда № 3 не выполнялась в течение
недели.»; с пустым ключом GigaChat — `FAILED` и исходный текст, ошибок в UI
нет.

**Коммит:** `feat(llm): формализация замечаний жителей`

---

### S18. Агрегированная картина и решения по позициям (T-21, FR-D3, FR-E5)

**Файлы:** `Acts.kt`, `Api.kt`, `Bot.kt`, `webapp/app.js`.

```kotlin
// ActService
suspend fun closeCollection(actId: Long, userId: Long)   // COLLECTING → REVIEW, событие COLLECTION_CLOSED;
    // предзаполнить decision у позиций, где он null: DISPUTE если stats.issueWithPhoto > 0, иначе ACCEPT
suspend fun setDecision(itemId: Long, userId: Long, decision: Decision)
    // председатель; акт в COLLECTING или REVIEW; DISPUTE при issueWithPhoto == 0 → 409 "no_evidence"
    // с сообщением "По позиции нет замечаний с фото — оснований для возражения недостаточно" (FR-E5); событие DECISION_SET
```
Эндпоинты:
```
POST /api/acts/{id}/close-collection   председатель → ActDto
PUT  /api/items/{itemId}/decision      председатель {"decision":"ACCEPT"|"DISPUTE"} → ItemDto
```
Бот: колбэк `close:{id}` → `closeCollection` → ответ статусом; жителям дома
— «Сбор замечаний по акту № … завершён. Спасибо!».

Мини-апп, экран «Замечания» (председатель, `COLLECTING/REVIEW`):
- Позиции по убыванию `issue`; у каждой: агрегат «выполнено: X, претензия:
  Y (с фото: Z)»; список `remarks` с претензией (формализованный текст или
  исходный, миниатюры фото); переключатель `Принять` / `Оспорить`;
  `Оспорить` недоступен при `issueWithPhoto = 0` с подписью «Нет замечаний
  с фото — возражать нечем».
- Кнопка «Завершить сбор замечаний» (при `COLLECTING`).
- Председатель, который ещё и житель, переключается вкладками
  «Мой чек-лист» / «Замечания» / «Отказ» (S21) — `state.tab`.

**Критерий готовности:** при двух жителях с претензиями (одна с фото, одна
без) председатель видит обе, может «Оспорить» только позицию с фото, на
второй — 409 с понятным текстом; после «Завершить сбор» статус `REVIEW`,
решения предзаполнены, житель больше не может сохранить отметку (409).

**Коммит:** `feat(remarks): сводка замечаний и решения председателя по позициям`

---

### S19. PDF-рендер, подписание (заглушка Госключа) и передача экземпляра (T-23, T-24, FR-F1, FR-F3)

**Файлы:** `Pdf.kt`, `Acts.kt`, `Bot.kt`.

```kotlin
object Pdf {
    fun font(name: String, size: Float): Font           // из S10; BaseFont кэшировать в lazy-поле
    fun signedAct(d: SignedActData): ByteArray
    fun refusal(d: RefusalPdfData): ByteArray   // S21
}
data class SignedActData(
    val houseAddress: String, val ukName: String, val actNumber: String?, val formedDate: LocalDate?,
    val period: String?, val items: List<ItemRow>, val chairmanFio: String, val signedAt: ZonedDateTime, val demo: Boolean,
)
data class ItemRow(val lineNo: Int, val name: String, val periodicity: String, val volume: String, val cost: String)
```
`signedAct` — A4, `Document` + `PdfWriter.getInstance(doc, ByteArrayOutputStream())`:
1. Заголовок (жирный, 14): «Экземпляр акта приёмки оказанных услуг и
   (или) выполненных работ по содержанию и текущему ремонту общего
   имущества в многоквартирном доме».
2. Строки: «Акт № {…} от {…} за {…}», «Адрес: {…}», «Исполнитель: {…}».
3. `PdfPTable(5)`: №, наименование, периодичность, объём, стоимость.
4. «Акт подписан председателем совета многоквартирного дома без
   возражений.» «Председатель совета МКД: {ФИО} ____________»
   «Дата и время подписания: {dd.MM.yyyy HH:mm}».
5. Если `demo`: рамка/абзац курсивом «Демо-режим: документ сформирован без
   квалифицированной электронной подписи (Госключ не подключён).»

`ActService.sign(actId, userId)`:
- председатель; статус `COLLECTING` или `REVIEW` (иначе 409); если
  `COLLECTING` — сначала `closeCollection`;
- `Pdf.signedAct(...)` → `{FILES_DIR}/pdf/act-{id}-signed.pdf`,
  `signedPdfPath`, `status = SIGNED`, событие `SIGNED` (actor = userId,
  details: `stub=true`) — всё в одной транзакции;
- `max.sendFile(userId, bytes, "akt-{number}-podpisan.pdf", "Акт подписан. Перешлите этот файл в УК ({exchangeMethod}) — это ваш подписанный экземпляр.")`;
- жителям дома: «Председатель подписал акт № … без возражений.».

Бот: `sign:{id}` → «Подписать акт без возражений? Оспариваемых позиций:
{N}.» (если N > 0 — добавить «Замечания жителей в документ не попадут.»)
+ `[Да, подписать]` (`sign_ok:{id}`) `[Нет]` (`status`). `sign_ok` →
`ActService.sign`.

**Критерий готовности:** «Подписать» → «Да» → в чат пришёл PDF: кириллица
читается, таблица позиций верна, есть демо-пометка; статус `SIGNED`,
событие `SIGNED` с временем и id председателя; повторное «Подписать» → 409 /
кнопок нет.

**Коммит:** `feat(sign): формирование подписанного экземпляра акта (заглушка Госключа)`

---

### S20. Сборка черновика мотивированного отказа (T-25, FR-G1, FR-G2, FR-G6 шаг 1)

**Файлы:** `Refusal.kt`, `backend/src/test/kotlin/mkd/RefusalDraftTest.kt`.

Промежуточный объект отказа (хранится в `Refusals.draftJson`):
```kotlin
@Serializable data class Objection(
    val itemId: Long, val lineNo: Int, val itemName: String,
    val fact: String,          // «Фактически» — из формализованных замечаний (LLM)
    val groundRef: String,     // из Grounds.legalRef (не LLM)
    val groundText: String,    // из Grounds.wording (не LLM)
    val demand: String,        // Grounds.demandTemplate с подстановкой {item}
    val okCount: Int, val issueCount: Int, val photoCount: Int,
)
@Serializable data class RefusalDraft(val objections: List<Objection>, val noObjectionLineNos: List<Int>)
```

Чистая функция (тестируется без БД):
```kotlin
data class DraftItem(val itemId: Long, val lineNo: Int, val name: String, val workKind: String, val decision: Decision?,
                     val remarks: List<DraftRemark>)
data class DraftRemark(val verdict: Verdict, val text: String, val photoCount: Int)   // text = formalized ?: original
data class GroundRow(val legalRef: String, val wording: String, val demandTemplate: String)

fun buildDraft(items: List<DraftItem>, grounds: Map<String, GroundRow>): RefusalDraft
```
Правила `buildDraft`:
- Возражение формируется по позиции, только если `decision == DISPUTE`
  **и** есть ISSUE-замечания с `photoCount > 0` (FR-E5). Иначе позиция
  идёт в `noObjectionLineNos` (FR-G1 — частичный отказ).
- `fact`: тексты ISSUE-замечаний **с фото**, без дубликатов строк; одно —
  как есть; несколько — `"1) …\n2) …"`.
- основание: `grounds[workKind] ?: grounds["OTHER"]`.
- `demand = demandTemplate.replace("{item}", name)`.
- `okCount/issueCount` — по всем замечаниям позиции, `photoCount` — сумма
  фото по ISSUE-замечаниям.
- Сортировка возражений и `noObjectionLineNos` — по `lineNo`.

```kotlin
class RefusalService(private val cfg: Config, private val max: MaxBotClient, private val remarks: RemarkService,
                     private val acts: ActService) {
    suspend fun draft(actId: Long, userId: Long, rebuild: Boolean): RefusalDto
}
```
`draft`: председатель; статус `COLLECTING`/`REVIEW` (при `COLLECTING` —
`closeCollection`); если отказ уже подтверждён → 409 `refusal_confirmed`;
если запись есть и `!rebuild` — вернуть её; иначе
`remarks.ensureFormalized(actId)` → собрать `DraftItem` из БД →
`buildDraft` → если `objections` пуст → 409 `no_objections` («Нет
оспариваемых позиций с фото — подпишите акт или отметьте позиции для
возражения»); upsert `Refusals` (`place` по умолчанию = адрес дома,
`revision = 1` при создании, `+1` при rebuild); событие `REFUSAL_DRAFTED`.

`RefusalDraftTest`: 3 позиции — (DISPUTE, ISSUE с фото ×2 разных текста +
OK ×1) → одно возражение с `fact = "1) …\n2) …"`, `okCount=1, issueCount=2`;
(DISPUTE, ISSUE без фото) → в `noObjectionLineNos`; (ACCEPT) → в
`noObjectionLineNos`; неизвестный `workKind` → основание `OTHER`.

**Критерий готовности:** тест зелёный.

**Коммит:** `feat(refusal): сборка черновика мотивированного отказа`

---

### S21. Редактирование, подтверждение и PDF отказа с фото (T-25, T-26, FR-G2, FR-G2.1, FR-G3, FR-G6 шаг 2)

**Файлы:** `Refusal.kt`, `Pdf.kt`, `Api.kt`, `Bot.kt`, `webapp/app.js`.

DTO и эндпоинты:
```kotlin
@Serializable data class RefusalDto(val id: Long, val revision: Int, val place: String, val objections: List<Objection>,
                                    val noObjectionLineNos: List<Int>, val confirmedAt: String?, val sentAt: String?)
@Serializable data class ObjectionEdit(val itemId: Long, val fact: String, val demand: String)
@Serializable data class RefusalEdit(val place: String, val objections: List<ObjectionEdit>)
```
```
POST /api/acts/{id}/refusal/draft?rebuild=true|false   председатель → RefusalDto   (S20)
GET  /api/acts/{id}/refusal                             председатель → RefusalDto | 404 "no_refusal"
PUT  /api/acts/{id}/refusal                             председатель RefusalEdit → RefusalDto
     только до подтверждения (иначе 409); fact/demand не пустые; itemId должен быть в черновике;
     groundRef/groundText НЕ редактируются (FR-E2); revision+1; событие REFUSAL_EDITED
POST /api/acts/{id}/refusal/confirm                     председатель → RefusalDto
     FR-G3: только явным вызовом; рендер PDF, сохранение, отправка в чат (см. ниже)
```

`confirm`:
1. Проставить `Attachments.registryNo` = 1..N по всем фото ISSUE-замечаний
   оспариваемых позиций, порядок: по `lineNo` позиции, затем по
   `uploadedAt`.
2. Собрать `RefusalPdfData` и `Pdf.refusal(...)` →
   `{FILES_DIR}/pdf/act-{id}-refusal-r{revision}.pdf`.
3. `confirmedAt = now`, `confirmedBy = userId`, `pdfPath`, событие
   `REFUSAL_CONFIRMED` — одна транзакция.
4. `max.sendFile(userId, bytes, "otkaz-akt-{number}.pdf", "Мотивированный отказ готов. Перешлите файл в УК ({exchangeMethod}) и нажмите кнопку ниже — мы зафиксируем время отправки.", [[cb("Отправил исполнителю", "sent:{id}")]])`.

```kotlin
data class RefusalPdfData(
    val houseAddress: String, val ukName: String, val ukRepresentative: String, val exchangeMethod: String,
    val actNumber: String?, val formedDate: LocalDate?, val period: String?,
    val objections: List<Objection>, val noObjectionLineNos: List<Int>,
    val photos: List<PhotoPage>,
    val chairmanFio: String, val place: String, val composedAt: ZonedDateTime, val demo: Boolean,
)
data class PhotoPage(val registryNo: Int, val lineNo: Int, val itemName: String, val author: String,
                     val uploadedAt: ZonedDateTime, val file: File)
```
Макет `Pdf.refusal` (FR-G2, в этом порядке):
1. Справа сверху: «Исполнителю: {ukName}», «{ukRepresentative}»; «от
   председателя совета МКД по адресу: {адрес}, {ФИО}».
2. Заголовок: «Мотивированный отказ от подписания акта приёмки оказанных
   услуг и (или) выполненных работ по содержанию и текущему ремонту общего
   имущества в многоквартирном доме».
3. «В соответствии с п. 4 Порядка приёмки…, утв. приказом Минстроя России
   от 22.05.2026 № 318/пр, отказываюсь от подписания акта № {…} от {…} за
   {…} в части следующих позиций:».
4. По каждому возражению — блок (таблица 2 колонки):
   «Позиция акта» — `№{lineNo}. {itemName}`; «Фактически» — `fact`;
   «Основание» — `groundText` + `(groundRef)`; «Требование» — `demand`;
   «Отметки жителей» — `выполнено: {ok}, претензия: {issue}, фото: {photoCount}`;
   «Приложения» — номера `registryNo` фото этой позиции.
5. «По позициям № {noObjectionLineNos через запятую} возражений не
   имеется.» (если список не пуст).
6. «Настоящий отказ направляется исполнителю способом: {exchangeMethod}.»
7. «Место составления: {place}. Дата и время составления: {dd.MM.yyyy HH:mm}.»
   «Председатель совета МКД ____________ /{ФИО}/».
8. Демо-пометка (как в S19) + «Сведения об УК и справочник оснований —
   демонстрационные».
9. Новая страница «Реестр приложений»: таблица № / позиция / автор / дата
   загрузки.
10. По странице на каждое фото: подпись сверху «Приложение № {registryNo}.
    Позиция № {lineNo} «{itemName}». Автор: {author}. Загружено: {dd.MM.yyyy HH:mm}»,
    ниже `Image.getInstance(file.path)` с `scaleToFit(ширина страницы − поля, высота − 120)`.

Мини-апп, вкладка «Отказ» (председатель, `REVIEW`, открывается по
`start_param=refusal_{id}` или вкладкой):
- Нет черновика → кнопка «Собрать черновик» (POST draft).
- По каждому возражению: заголовок позиции; `<textarea>` «Фактически»;
  основание — только текст (не редактируется) с подписью «из справочника
  оснований»; `<textarea>` «Требование»; счётчики отметок. Поле «Место
  составления». Кнопки «Сохранить» (PUT), «Пересобрать из замечаний»
  (POST draft?rebuild=true, с `confirm()` «Ваши правки будут потеряны»),
  «Подтвердить и сформировать документ» (сначала PUT, затем confirm, с
  `confirm()` «После подтверждения текст изменить нельзя»).
- После подтверждения: «Документ отправлен вам в чат MAX» и
  `WA.close()` по кнопке «Вернуться в чат».

Бот: `refuse:{id}` → `RefusalService.draft(rebuild=false)`; при 409
`no_objections` — текст ошибки; иначе «Черновик отказа готов: возражений —
{N}. Проверьте формулировки и подтвердите.» + link `[Открыть черновик]`
(`refusal_{id}`).

**Критерий готовности:** «Сформировать отказ» → черновик с оспоренной
позицией, основание из справочника; правка текста сохраняется (revision
растёт); без нажатия «Подтвердить» PDF не создаётся; после подтверждения в
чат пришёл PDF: частичный отказ, 4 элемента по позиции, строка «по
позициям … возражений не имеется», реестр, страницы с фото с подписями,
кириллица читается; PUT после подтверждения → 409.

**Коммит:** `feat(refusal): редактирование, подтверждение и PDF отказа с фото`

---

### S22. Отправка отказа исполнителю с фиксацией времени (T-27, FR-F3, FR-G4)

**Файлы:** `Refusal.kt`, `Bot.kt`.

`RefusalService.markSent(actId, userId)`: председатель; отказ подтверждён,
`sentAt == null` (иначе ответить «Уже отмечено {время}»); в одной
транзакции `sentAt = now`, `status = REJECTED`, событие `REFUSAL_SENT`
(actor = userId, details: `method={exchangeMethod}`). Затем:
- председателю: «Время отправки зафиксировано: {dd.MM.yyyy HH:mm}. Акт в
  статусе «Отказ направлен, ожидается новый акт». Когда УК пришлёт новый
  акт — просто загрузите его сюда.»;
- жителям дома: «Председатель направил в УК мотивированный отказ по акту
  № … (оспорено позиций: N). Спасибо за ваши отметки!».

Бот: колбэк `sent:{id}` → `markSent`.

Таймер (S12) акты `REJECTED` не трогает; загрузка нового акта после
`REJECTED` разрешена (активным `REJECTED` не считается).

**Критерий готовности:** «Отправил исполнителю» → статус `REJECTED`, в
`events` `REFUSAL_SENT` с временем и id председателя; повторное нажатие не
меняет время; жители получили сообщение; новый акт по дому загружается.

**Коммит:** `feat(refusal): фиксация отправки отказа исполнителю`

---

### S23. Сквозной сценарий (T-29)

**Файлы:** `docs/e2e-checklist.md` (новый; он же войдёт в README как
«пошаговый сценарий проверки»).

Прогнать на задеплоенном стенде **в мобильном и в веб-MAX** (правило
хакатона: функциональность в обеих версиях) два сценария и записать
результат (✅/❌ + заметка) в чек-лист:

**A. Отказ.** 1) Председатель: `/start` → согласие → дом 1 → председатель →
ФИО → основание. 2) Житель (второй аккаунт): `/start` → дом 1 → житель.
3) Председатель отправляет `demo/act-demo.pdf` → «Сегодня» → «Распознано
позиций: 6». 4) Мини-апп: правка позиции, «Открыть сбор». 5) Житель:
уведомление → чек-лист: 4 «выполнено», 1 «претензия» с фото, 1 «претензия»
без фото. 6) Председатель: `/status` → счётчики; мини-апп «Замечания» —
формализованный текст; «Оспорить» доступно только для позиции с фото.
7) `/shift 8` → уведомление D7. 8) «Сформировать отказ» → правка «Требования»
→ «Подтвердить» → PDF в чате (проверить реквизиты FR-G2 и страницу фото).
9) «Отправил исполнителю» → статус «Отказ направлен», житель уведомлён.

**B. Молчаливое согласие и подпись.** 1) Дом 2: загрузить акт, открыть сбор.
2) `/shift 31` → статус «Принят молчаливым согласием», всем пришло
уведомление, кнопок подписи нет. 3) Дом 3: загрузить акт → «Подписать» →
«Да» → PDF подписанного экземпляра.

**C. Отказоустойчивость.** Остановить бэкенд на 2 минуты во время сбора →
запустить → уведомления/статусы продолжают работать, данные на месте
(NFR-5). С пустым `GIGACHAT_AUTH_KEY` сценарий A проходит с ручным вводом
позиций и неформализованными текстами (NFR-9).

Найденные баги чинить отдельными коммитами `fix(...)` в этой же ветке.

**Критерий готовности:** все пункты A–C ✅ в обеих версиях MAX.

**Коммит:** `test: чек-лист сквозного сценария и результаты прогона`

---

### S24. README и чек-лист сдачи (T-30, T-08)

**Файлы:** `README.md` (переписать), `.env.example` (сверить).

README — ровно разделы из hackathon-brief §«Формат сдачи», п. 3:
1. Назначение (2–3 предложения из текущего README).
2. Основной пользовательский сценарий (сценарий A из S23 кратко).
3. Состав и архитектура: схема «бот + мини-апп (GitHub Pages) → Ktor →
   Postgres / GigaChat / файлы», таблица файлов `backend/src/main/kotlin/mkd/*`.
4. Одна команда запуска: `cp .env.example .env` (заполнить) →
   `docker compose up --build`.
5. Переменные окружения (таблица из S3), порты (`8080` бэкенд, `5432`
   внутри сети compose), зависимости (`backend/build.gradle.kts` +
   `gradle.lockfile`).
6. Внешние сервисы: MAX Bot API, GigaChat, GitHub Pages (мини-апп — вне
   Docker, это статический хостинг; указать URL и что для работы
   мини-аппа бэкенд должен быть доступен по HTTPS, адрес — в
   `webapp/config.js`).
7. Работа с данными: какие ПДн храним (имя MAX, ФИО председателя,
   отметки, фото), где (Postgres + volume `files`), согласие при
   регистрации, удаление — `docker compose down -v`.
8. Тестовые данные: демо-дома/УК/основания помечены «демо»; `demo/act-demo.pdf`;
   Госключ — заглушка; команда `/shift N` (только `DEMO_MODE=true`).
9. Пошаговый сценарий проверки — из `docs/e2e-checklist.md`.
10. Примеры ожидаемого поведения (ответы бота на ключевых шагах).
11. Известные ограничения: подпись без КЭП; отправка в УК вручную;
    одновременный запуск двух экземпляров с одним токеном бота делит
    апдейты (при локальной проверке остановить стенд или взять другой
    токен); состояние диалога регистрации теряется при рестарте;
    справочник оснований демонстрационный.
12. Остановка и перезапуск: `docker compose down` / `docker compose up`;
    полный сброс — `docker compose down -v`.

Финальный чек-лист (отметить в PR):
- [ ] `docker compose up --build` с чистого клона ≤5 минут сборки.
- [ ] В репозитории нет токенов/паролей (`git grep -i -E "token|key|password"` — только плейсхолдеры).
- [ ] `.env.example`, `.dockerignore`, `Dockerfile`, `compose.yaml`, `gradle.lockfile` на месте.
- [ ] `./gradlew test` зелёный.
- [ ] Мини-апп на Pages открывается из бота в мобильном и веб-MAX.
- [ ] `webapp/config.js` указывает на живой бэкенд.
- [ ] Сценарии A–C из S23 пройдены на финальном коммите; commit hash
      передан человеку для первого слайда презентации.

**Коммит:** `docs: README для сдачи и проверки`

---

## 4. Вопросы к человеку

Исполнитель не может получить это сам. Всё, что является секретом, —
только в `.env` (не в git); в `.env.example` — пустое значение с
комментарием; в README — где взять.

1. **Токен бота MAX (`MAX_BOT_TOKEN`).** Выдают организаторы или
   `@MasterBot` → `/create`. Ник бота бэкенд берёт сам из `GET /me`. Ник
   нельзя сменить после создания — выбрать осознанно. Нужен к S4.
2. **URL мини-аппа в настройках бота.** После первого деплоя Pages (S6)
   указать в `@MasterBot` адрес вида `https://<user>.github.io/MaxHackathon/`.
3. **GitHub Pages.** Репозиторий должен быть публичным (или тариф с Pages
   для приватных); Settings → Pages → Source: **GitHub Actions**. Деплой
   идёт из `master` — значит, для проверки в MAX ветку с мини-аппом нужно
   смержить (или разрешить ветку в правилах environment `github-pages`).
4. **Публичный HTTPS-адрес бэкенда.** Мини-апп на `https://*.github.io` не
   может ходить на `http://` (mixed content), а жюри будет проверять не с
   вашего ноутбука. Нужен VPS/хостинг с Docker и доменом (HTTPS, например,
   через Caddy: `caddy reverse-proxy --from api.<домен> --to localhost:8080`)
   или именованный туннель Cloudflare. Адрес вписать в `webapp/config.js`
   (`API_BASE`) и в `.env` (`CORS_ORIGIN=https://<user>.github.io`). Нужен
   к S6-смоуку и к сдаче.
5. **Ключ GigaChat (`GIGACHAT_AUTH_KEY`, `GIGACHAT_SCOPE`).**
   developers.sber.ru → проект GigaChat API → «Ключ авторизации»
   (Authorization key, base64). Для физлица scope `GIGACHAT_API_PERS`.
   Проверить, что на тарифе доступна модель `GigaChat-2-Max` (для фото
   акта нужна модель с поддержкой изображений); если нет — сообщить,
   какая доступна. Нужен к S10.
6. **Корневой сертификат Минцифры** (`backend/certs/russian_trusted_root_ca.cer`)
   — если исполнитель не может скачать его с `gu-st.ru`, скачать вручную и
   положить в репозиторий (публичный, не секрет).
7. **Ваш MAX `user_id` для `ADMIN_USER_IDS`** — опционально. Если пусто,
   председатели подтверждаются автоматически (удобно для жюри); на
   проверку рекомендуем оставить пустым.
8. **Реальный скан/PDF акта по форме 761/пр** — опционально, чтобы
   проверить распознавание не только на сгенерированном `demo/act-demo.pdf`.
9. **Git-автор.** В текущем клоне `git config user.email` может не
   совпадать с `nikelodeon53@gmail.com` — исполнитель использует `git -c …`
   на каждый коммит (§0); если хотите — поправьте конфиг сами.

---

## 5. После Must (не делать до зелёного S23)

Should (requirements §3 / tasks.md):
- T-13 / FR-B3 — пояснение «что проверить» по каждой позиции.
- T-22 / FR-E3–E4 — дедупликация и метка нерелевантности замечаний
  (колонка `Remarks.relevance` уже есть).
- T-28 / FR-G5 — цепочка кругов: связь нового акта с предыдущим отказом
  (колонки `Acts.previousActId`, `round` уже есть), перенос непринятых
  возражений в подсказки.
- FR-F2 — реальная подпись через Госключ (коннектор Астрал/Диадок).
- FR-B5 — сценарий «акт не поступил» и шаблон запроса в УК.
- История актов по дому и экспорт PDF.
- Уведомление УК о результате приёмки (автоотправка по email/SMTP).

Технический долг Must-пути (собрать `ponytail:`-комментарии через
`/ponytail-debt`):
- Webhook (`POST /subscriptions`) вместо long polling для прод-режима.
- Персистентное состояние диалога бота (сейчас в памяти).
- Миграции схемы (Flyway) вместо `SchemaUtils.create`.
- Удаление ПДн по запросу пользователя (NFR-2) командой бота.

Could: интеграция с ГИС ЖКХ, приём акта напрямую от УК, шаблоны типовых
возражений, мультидомовость, аналитика по срокам.
