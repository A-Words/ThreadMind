package app.threadmind.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderPreflightTest {
    @Test fun `contact insight snapshot includes only bounded useful fields`() {
        val fields = listOf(
            ContactFieldSnapshot("1", "email", "a@example.com"), ContactFieldSnapshot("2", "email", "b@example.com"),
            ContactFieldSnapshot("3", "email", "c@example.com"), ContactFieldSnapshot("4", "email", "ignored@example.com"),
            ContactFieldSnapshot("5", "phone", "+886900000001"), ContactFieldSnapshot("6", "company", "Acme"),
            ContactFieldSnapshot("7", "jobTitle", "Director"), ContactFieldSnapshot("8", "note", "private note"),
            ContactFieldSnapshot("9", "address", "private address"),
        )
        val result = buildContactSnapshotRecord("42", "Chen", fields, "provider_record_id", "confirmed_target")
        assertEquals(listOf("a@example.com", "b@example.com", "c@example.com"), result.emailAddresses)
        assertEquals(listOf("+886900000001"), result.phoneNumbers)
        assertEquals("Acme", result.organization)
        assertEquals("Director", result.jobTitle)
        assertEquals(false, result.toString().contains("private"))
    }

    @Test
    fun `builds only visible contact differences and preserves target rows`() {
        val current = listOf(
            ContactFieldSnapshot("email-row", "email", "old@example.com"),
            ContactFieldSnapshot("company-row", "company", "Acme"),
        )

        val changes = buildProposedContactChanges(
            rawContactId = "raw-1",
            current = current,
            proposedFields = mapOf(
                "contactMethod" to "new@example.com",
                "company" to "Acme",
                "jobTitle" to "Director",
            ),
        )

        assertEquals(
            listOf(
                ContactFieldChange("email", "raw-1", "email-row", "old@example.com", "new@example.com"),
                ContactFieldChange("jobTitle", "raw-1", null, null, "Director"),
            ),
            changes,
        )
    }

    @Test
    fun `maps phone and note aliases without inventing empty values`() {
        val changes = buildProposedContactChanges(
            rawContactId = "raw-2",
            current = emptyList(),
            proposedFields = mapOf("contactMethod" to " +886 912 345 678 ", "notes" to "  met at event  ", "address" to ""),
        )

        assertEquals(listOf("phone", "note"), changes.map(ContactFieldChange::field))
        assertEquals(listOf("+886 912 345 678", "met at event"), changes.map(ContactFieldChange::newValue))
    }
}
