package mkd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RefusalDraftTest {
    private val grounds = mapOf(
        "CLEANING" to GroundRow("ссылка CLEANING", "основание CLEANING", "Устранить {item}"),
        "OTHER" to GroundRow("ссылка OTHER", "основание OTHER", "Устранить {item}"),
    )

    @Test
    fun `dispute with photo becomes objection with merged fact`() {
        val items = listOf(
            DraftItem(
                itemId = 1, lineNo = 1, name = "Уборка лестниц", workKind = "CLEANING", decision = Decision.DISPUTE,
                remarks = listOf(
                    DraftRemark(Verdict.ISSUE, "Не мыли неделю", 1),
                    DraftRemark(Verdict.ISSUE, "Грязно на 3 этаже", 1),
                    DraftRemark(Verdict.OK, "", 0),
                ),
            ),
        )
        val draft = buildDraft(items, grounds)
        assertEquals(1, draft.objections.size)
        val o = draft.objections.single()
        assertEquals("1) Не мыли неделю\n2) Грязно на 3 этаже", o.fact)
        assertEquals(1, o.okCount)
        assertEquals(2, o.issueCount)
        assertEquals(2, o.photoCount)
        assertEquals("ссылка CLEANING", o.groundRef)
        assertEquals("Устранить Уборка лестниц", o.demand)
        assertTrue(draft.noObjectionLineNos.isEmpty())
    }

    @Test
    fun `dispute without photo has no objection`() {
        val items = listOf(DraftItem(2, 2, "Двор", "YARD", Decision.DISPUTE, listOf(DraftRemark(Verdict.ISSUE, "Мусор", 0))))
        val draft = buildDraft(items, grounds)
        assertTrue(draft.objections.isEmpty())
        assertEquals(listOf(2), draft.noObjectionLineNos)
    }

    @Test
    fun `accepted item has no objection`() {
        val items = listOf(DraftItem(3, 3, "Лифт", "ELEVATOR", Decision.ACCEPT, listOf(DraftRemark(Verdict.OK, "", 0))))
        val draft = buildDraft(items, grounds)
        assertTrue(draft.objections.isEmpty())
        assertEquals(listOf(3), draft.noObjectionLineNos)
    }

    @Test
    fun `unknown work kind falls back to OTHER ground`() {
        val items = listOf(DraftItem(4, 4, "Прочее", "NOPE", Decision.DISPUTE, listOf(DraftRemark(Verdict.ISSUE, "Проблема", 1))))
        val draft = buildDraft(items, grounds)
        assertEquals(1, draft.objections.size)
        assertEquals("ссылка OTHER", draft.objections.single().groundRef)
    }
}
