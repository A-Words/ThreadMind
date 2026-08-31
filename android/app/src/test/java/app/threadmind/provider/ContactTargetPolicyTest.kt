package app.threadmind.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
