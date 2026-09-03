package app.threadmind.domain

import kotlinx.serialization.Serializable

@Serializable
data class ContactContextSnapshot(
    val source: String = "android_contacts_provider",
    val capturedAt: String,
    val permissionStatus: String,
    val queries: List<ContactContextQuery> = emptyList(),
    val records: List<ContactSnapshotRecord> = emptyList(),
)

@Serializable
data class ContactContextQuery(val kind: String, val value: String)

@Serializable
data class ContactSnapshotRecord(
    val providerContactId: String,
    val displayName: String? = null,
    val emailAddresses: List<String> = emptyList(),
    val phoneNumbers: List<String> = emptyList(),
    val organization: String? = null,
    val jobTitle: String? = null,
    val matchBasis: String,
    val identityStatus: String,
)
