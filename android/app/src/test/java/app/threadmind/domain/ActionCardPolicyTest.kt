package app.threadmind.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ActionCardPolicyTest {
    private fun readyCard() = ActionCard(
        id = "card-1",
        submissionId = "submission-1",
        type = ActionType.CREATE_CONTACT,
        version = 1,
        fields = mapOf("displayName" to "Chen", "contactMethod" to "chen@example.com", "targetContactAccountId" to "local"),
        evidence = listOf(EvidenceRef("submission-1", "m1", "chen@example.com", 0.99)),
        fieldConfidence = mapOf("displayName" to 0.8, "contactMethod" to 0.99, "targetContactAccountId" to 1.0),
        validationIssues = emptyList(),
        targetAccountId = "local",
        status = ActionStatus.DRAFT,
        blockers = emptyList(),
    )

    @Test fun `blocked cards cannot be confirmed`() {
        val blocked = ActionCardPolicy.evaluate(readyCard().copy(fields = emptyMap()))
        assertEquals(ActionStatus.BLOCKED, blocked.status)
        assertThrows(IllegalArgumentException::class.java) { ActionCardPolicy.confirm(blocked) }
    }

    @Test fun `editing invalidates confirmation`() {
        val confirmed = ActionCardPolicy.confirm(readyCard())
        val edited = ActionCardPolicy.edit(confirmed, confirmed.fields + ("displayName" to "Chen Wei"))
        assertEquals(2, edited.version)
        assertNull(edited.confirmedSnapshot)
    }
}
