package app.threadmind.domain

enum class ActionType { CREATE_MEETING, CREATE_CONTACT, UPDATE_CONTACT }
enum class ActionStatus { DRAFT, BLOCKED, READY, CONFIRMED, EXECUTING, SUCCEEDED, FAILED, CANCELLED }

data class EvidenceRef(
    val sourceId: String,
    val messageId: String?,
    val excerpt: String,
    val confidence: Double,
)

data class ActionCard(
    val id: String,
    val submissionId: String,
    val type: ActionType,
    val version: Int,
    val fields: Map<String, String>,
    val evidence: List<EvidenceRef>,
    val targetAccountId: String?,
    val status: ActionStatus,
    val blockers: List<String>,
    val confirmedSnapshot: ConfirmedActionSnapshot? = null,
)

data class ConfirmedActionSnapshot(
    val actionCardId: String,
    val type: ActionType,
    val version: Int,
    val fields: Map<String, String>,
    val evidence: List<EvidenceRef>,
    val targetAccountId: String,
    val idempotencyKey: String,
)

object ActionCardPolicy {
    private val required = mapOf(
        ActionType.CREATE_MEETING to setOf("title", "startsAt", "endsAt", "timezone", "targetCalendarId"),
        ActionType.CREATE_CONTACT to setOf("displayName", "contactMethod", "targetContactAccountId"),
        ActionType.UPDATE_CONTACT to setOf("targetContactId", "changes"),
    )

    fun evaluate(card: ActionCard): ActionCard {
        val blockers = required.getValue(card.type)
            .filter { card.fields[it].isNullOrBlank() }
            .map { "missing:$it" }
            .toMutableList()
        if (card.evidence.isEmpty()) blockers += "missing:evidence"
        if (card.targetAccountId.isNullOrBlank()) blockers += "missing:targetAccountId"
        return card.copy(status = if (blockers.isEmpty()) ActionStatus.READY else ActionStatus.BLOCKED, blockers = blockers)
    }

    fun confirm(card: ActionCard): ActionCard {
        val ready = evaluate(card)
        require(ready.status == ActionStatus.READY) { "Only a ready card can be confirmed" }
        val target = requireNotNull(ready.targetAccountId)
        val snapshot = ConfirmedActionSnapshot(
            actionCardId = ready.id,
            type = ready.type,
            version = ready.version,
            fields = ready.fields.toMap(),
            evidence = ready.evidence.toList(),
            targetAccountId = target,
            idempotencyKey = "${ready.id}:${ready.version}:$target",
        )
        return ready.copy(status = ActionStatus.CONFIRMED, confirmedSnapshot = snapshot)
    }

    fun edit(card: ActionCard, fields: Map<String, String>): ActionCard = evaluate(
        card.copy(
            version = card.version + 1,
            fields = fields.toMap(),
            status = ActionStatus.DRAFT,
            confirmedSnapshot = null,
        ),
    )
}
