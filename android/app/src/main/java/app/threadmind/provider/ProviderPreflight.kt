package app.threadmind.provider

import app.threadmind.domain.ActionCard
import kotlinx.serialization.Serializable
import app.threadmind.domain.ContactSnapshotRecord

@Serializable
data class ContactFieldChange(
    val field: String,
    val rawContactId: String,
    val dataId: String? = null,
    val oldValue: String? = null,
    val newValue: String,
)

data class ContactCandidate(
    val contactId: String,
    val rawContactId: String,
    val displayName: String,
    val accountName: String?,
    val accountType: String?,
    val matchedBy: String,
    val proposedChanges: List<ContactFieldChange>,
)

data class MeetingConflict(
    val eventId: String,
    val title: String,
    val startsAtEpochMillis: Long,
    val endsAtEpochMillis: Long,
)

data class ContactFieldSnapshot(
    val dataId: String,
    val field: String,
    val value: String,
)

data class ProviderTarget(
    val targetAccountId: String,
    val label: String,
    val fieldUpdates: Map<String, String>,
)

fun buildProposedContactChanges(
    rawContactId: String,
    current: List<ContactFieldSnapshot>,
    proposedFields: Map<String, String>,
): List<ContactFieldChange> = desiredContactValues(proposedFields).mapNotNull { (field, newValue) ->
    val existing = current.firstOrNull { it.field == field }
    if (existing?.value == newValue) null else ContactFieldChange(
        field = field,
        rawContactId = rawContactId,
        dataId = existing?.dataId,
        oldValue = existing?.value,
        newValue = newValue,
    )
}

private fun desiredContactValues(fields: Map<String, String>): Map<String, String> = buildMap {
    fields["contactMethod"]?.trim()?.takeIf(String::isNotEmpty)?.let { put(if (it.contains("@")) "email" else "phone", it) }
    listOf("email", "phone", "company", "jobTitle", "address").forEach { field ->
        fields[field]?.trim()?.takeIf(String::isNotEmpty)?.let { put(field, it) }
    }
    (fields["note"] ?: fields["notes"])?.trim()?.takeIf(String::isNotEmpty)?.let { put("note", it) }
}

internal fun buildContactSnapshotRecord(
    contactId: String,
    displayName: String?,
    fields: List<ContactFieldSnapshot>,
    matchBasis: String,
    identityStatus: String,
): ContactSnapshotRecord = ContactSnapshotRecord(
    providerContactId = contactId,
    displayName = displayName,
    emailAddresses = fields.filter { it.field == "email" }.map(ContactFieldSnapshot::value).distinct().take(3),
    phoneNumbers = fields.filter { it.field == "phone" }.map(ContactFieldSnapshot::value).distinct().take(3),
    organization = fields.firstOrNull { it.field == "company" }?.value,
    jobTitle = fields.firstOrNull { it.field == "jobTitle" }?.value,
    matchBasis = matchBasis,
    identityStatus = identityStatus,
)

sealed interface ProviderPreflightResult {
    val cardId: String
    val version: Int

    data class Clear(
        override val cardId: String,
        override val version: Int,
    ) : ProviderPreflightResult

    data class MeetingConflicts(
        override val cardId: String,
        override val version: Int,
        val items: List<MeetingConflict>,
    ) : ProviderPreflightResult

    data class ContactCandidates(
        override val cardId: String,
        override val version: Int,
        val items: List<ContactCandidate>,
        val createContact: Boolean,
    ) : ProviderPreflightResult

    data class ContactOverwrites(
        override val cardId: String,
        override val version: Int,
        val candidate: ContactCandidate,
        val changes: List<ContactFieldChange>,
    ) : ProviderPreflightResult

    data class Blocked(
        override val cardId: String,
        override val version: Int,
        val message: String,
    ) : ProviderPreflightResult
}

interface ProviderInspector {
    suspend fun inspect(card: ActionCard): ProviderPreflightResult
    suspend fun targets(card: ActionCard): List<ProviderTarget> = emptyList()
    fun updateFields(candidate: ContactCandidate): Map<String, String>
}
