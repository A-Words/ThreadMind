package app.threadmind.provider

import android.provider.ContactsContract
import androidx.test.platform.app.InstrumentationRegistry
import app.threadmind.domain.ActionType
import app.threadmind.domain.ConfirmedActionSnapshot
import app.threadmind.domain.EvidenceRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AndroidProviderContactSnapshotTest {
    @Test fun createsRealContactAndCapturesOnlyBoundedProviderContext() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val executor = AndroidProviderExecutor(context)
        val id = UUID.randomUUID().toString()
        val email = "threadmind-$id@example.com"
        val snapshot = ConfirmedActionSnapshot(
            actionCardId = id, type = ActionType.CREATE_CONTACT, version = 1,
            fields = mapOf("displayName" to "ThreadMind QA $id", "contactMethod" to email, "company" to "Synthetic Acme",
                "jobTitle" to "Synthetic Buyer", "note" to "must-not-upload", "address" to "must-not-upload"),
            evidence = listOf(EvidenceRef("synthetic", null, email, 1.0)), targetAccountId = "local", idempotencyKey = id,
        )
        var contactId: String? = null
        try {
            val result = executor.execute(snapshot) as ProviderResult.Succeeded
            contactId = result.targetRecordId
            val contact = requireNotNull(result.contactContext)
            assertEquals("granted", contact.permissionStatus)
            assertEquals(listOf("target_record_id"), contact.queries.map { it.kind })
            assertEquals(1, contact.records.size)
            assertEquals("confirmed_target", contact.records.single().identityStatus)
            assertEquals(listOf(email), contact.records.single().emailAddresses)
            assertEquals("Synthetic Acme", contact.records.single().organization)
            assertFalse(contact.toString().contains("must-not-upload"))
            val replay = executor.execute(snapshot) as ProviderResult.Succeeded
            assertEquals(contactId, replay.targetRecordId)
            assertTrue(replay.contactContext?.records?.single()?.emailAddresses == listOf(email))
        } finally {
            contactId?.let { target ->
                context.contentResolver.delete(ContactsContract.RawContacts.CONTENT_URI,
                    "${ContactsContract.RawContacts.CONTACT_ID} = ?", arrayOf(target))
            }
        }
    }
}
