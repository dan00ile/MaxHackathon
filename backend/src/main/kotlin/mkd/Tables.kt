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
    val address = text("address").default("")          // адрес для шапки мотивированного отказа
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

    init {
        uniqueIndex(userId, houseId)
    }
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
    // демо-сброс: акт убран из активного потока, но сам акт, позиции, замечания и документы сохранены
    val archivedAt = timestamp("archived_at").nullable()
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

    init {
        uniqueIndex(itemId, authorId)
    }
}

// §6 «Вложение»
object Attachments : LongIdTable("attachments") {
    val remarkId = reference("remark_id", Remarks, onDelete = ReferenceOption.CASCADE)
    val filePath = text("file_path")
    val mime = text("mime")
    val authorId = long("author_id").references(Users.id)
    val uploadedAt = timestamp("uploaded_at")
    val registryNo =
        integer("registry_no").nullable() // номер в реестре приложений, проставляется при формировании отказа
}

// §6 «Основание» — справочник оператора
object Grounds : LongIdTable("grounds") {
    val workKind = varchar("work_kind", 32).uniqueIndex()
    val workKindTitle = text("work_kind_title")
    val legalRef = text("legal_ref")
    val wording = text("wording")
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

val allTables = arrayOf(
    ManagementCompanies, Houses, Users, Chairmen, Acts, ActItems,
    Remarks, Attachments, Grounds, Refusals, Events
)
