package mkd

import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Старые сообщения с выбором дома и роли остаются кликабельными — повторное нажатие не должно
// перепривязывать пользователя к другому дому
class HouseBindingTest {
    private val userId = 300L
    private var firstHouse = 0L
    private var secondHouse = 0L

    @BeforeTest
    fun setUp() = transaction(testDb) {
        allTables.reversed().forEach { it.deleteAll() }
        val ukRef = ManagementCompanies.insert {
            it[ManagementCompanies.name] = "ООО «УК Тест»"
            it[ManagementCompanies.inn] = "0000000000"
            it[ManagementCompanies.licenseNo] = "№ 1"
            it[ManagementCompanies.representative] = "Директор Петров П. П."
            it[ManagementCompanies.exchangeMethod] = "email: uk@test.ru"
        } get ManagementCompanies.id
        val (first, second) = listOf("д. 1", "д. 2").map { suffix ->
            (Houses.insert {
                it[Houses.address] = "г. Казань, ул. Тестовая, $suffix"
                it[Houses.ukId] = ukRef
            } get Houses.id).value
        }
        firstHouse = first
        secondHouse = second
        Users.insert {
            it[Users.id] = userId
            it[Users.name] = "Житель"
            it[Users.pdConsentAt] = Instant.now()
            it[Users.createdAt] = Instant.now()
        }
        Unit
    }

    @Test
    fun `second house choice does not rebind the user`() = transaction(testDb) {
        assertTrue(bindHouseTx(userId, firstHouse), "первая привязка проходит")
        assertFalse(bindHouseTx(userId, secondHouse), "повторная привязка отклоняется")
        val houseId = Users.selectAll().where { Users.id eq userId }.single()[Users.houseId]?.value
        assertEquals(firstHouse, houseId)
    }
}
