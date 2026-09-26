package mkd

import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

object Seed {
    fun run() = transaction {
        if (!Houses.selectAll().empty()) return@transaction

        val ukCompanyId = ManagementCompanies.insert {
            it[name] = "ООО «УК Демо-Сервис»"
            it[inn] = "0000000000"
            it[licenseNo] = "№ 000-демо"
            it[representative] = "Генеральный директор Иванов И. И. (демо)"
            it[exchangeMethod] = "email: uk-demo@example.ru"
            it[isDemo] = true
        } get ManagementCompanies.id

        listOf(
            "г. Казань, ул. Демонстрационная, д. 1",
            "г. Казань, ул. Демонстрационная, д. 2",
            "г. Казань, ул. Демонстрационная, д. 3",
        ).forEach { addr ->
            Houses.insert {
                it[address] = addr
                it[ukId] = ukCompanyId
                it[hasCouncil] = true
                it[isDemo] = true
            }
        }

        data class GroundSeed(val workKind: String, val title: String, val legalRef: String, val wording: String)

        val demandTemplate = "Устранить недостатки выполнения работ «{item}» либо исключить невыполненный объём " +
                "из акта и направить новый акт в порядке п. 6 Порядка, утв. приказом Минстроя России № 318/пр."

        listOf(
            GroundSeed(
                "CLEANING", "Уборка мест общего пользования",
                "Договор управления МКД; п. 23 Минимального перечня услуг и работ, утв. постановлением Правительства РФ от 03.04.2013 № 290",
                "Работы по содержанию помещений общего имущества, включая уборку, выполняются с периодичностью, установленной договором управления",
            ),
            GroundSeed(
                "YARD", "Содержание придомовой территории",
                "Договор управления МКД; пп. 24–25 Минимального перечня № 290",
                "Работы по содержанию придомовой территории в холодный и тёплый период года выполняются с установленной периодичностью",
            ),
            GroundSeed(
                "WASTE", "Содержание контейнерных площадок",
                "Договор управления МКД; п. 26(1) Минимального перечня № 290",
                "Работы по содержанию мест накопления твёрдых коммунальных отходов выполняются в соответствии с установленными требованиями",
            ),
            GroundSeed(
                "ROOF", "Содержание и ремонт крыши",
                "Договор управления МКД; п. 7 Минимального перечня № 290",
                "Работы по надлежащему содержанию крыш выполняются в объёме, предусмотренном договором",
            ),
            GroundSeed(
                "ELEVATOR", "Содержание лифтов",
                "Договор управления МКД; п. 22 Минимального перечня № 290",
                "Работы по надлежащему содержанию лифтов выполняются в объёме, предусмотренном договором",
            ),
            GroundSeed(
                "ENGINEERING", "Внутридомовые инженерные системы",
                "Договор управления МКД; пп. 17–19 Минимального перечня № 290",
                "Работы по содержанию систем водоснабжения, отопления и водоотведения выполняются в объёме, предусмотренном договором",
            ),
            GroundSeed(
                "OTHER", "Прочие работы",
                "Договор управления МКД; ч. 2 ст. 162 ЖК РФ; Минимальный перечень № 290",
                "Услуги и работы по содержанию и ремонту общего имущества оказываются в объёме и с качеством, предусмотренными договором управления",
            ),
        ).forEach { g ->
            Grounds.insert {
                it[workKind] = g.workKind
                it[workKindTitle] = g.title
                it[legalRef] = g.legalRef
                it[wording] = g.wording
                it[Grounds.demandTemplate] = demandTemplate
            }
        }
    }
}
