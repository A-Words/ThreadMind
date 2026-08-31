package app.threadmind.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import app.threadmind.domain.ActionType
import app.threadmind.domain.ConfirmedActionSnapshot

class ContactTargetPolicyTest {
    @Test
    fun `local contacts remain available without a sync adapter`() {
        assertTrue(isWritableContactAccount(null, emptySet()))
    }

    @Test
    fun `only uploading sync adapters are offered as account targets`() {
        val writable = setOf("com.example.writable")
        assertTrue(isWritableContactAccount("com.example.writable", writable))
        assertFalse(isWritableContactAccount("com.example.readonly", writable))
    }

    @Test
    fun `provider action marker is stable for a confirmed snapshot`() {
        val snapshot = ConfirmedActionSnapshot(
            actionCardId = "card-1",
            type = ActionType.UPDATE_CONTACT,
            version = 2,
            fields = emptyMap(),
            evidence = emptyList(),
            targetAccountId = "local",
            idempotencyKey = "stable-key",
        )
        assertTrue(providerActionMarker(snapshot) == "ThreadMind:stable-key")
    }
}
