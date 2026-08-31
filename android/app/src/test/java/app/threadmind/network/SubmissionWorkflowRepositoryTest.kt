package app.threadmind.network

import org.junit.Assert.assertEquals
import org.junit.Test

class SubmissionWorkflowRepositoryTest {
    @Test
    fun `cancelled receipt keeps card recoverable after restart`() {
        assertEquals("failed", recoverableCardStatus("cancelled"))
    }

    @Test
    fun `other receipt states remain unchanged`() {
        assertEquals("failed", recoverableCardStatus("failed"))
        assertEquals("succeeded", recoverableCardStatus("succeeded"))
    }
}
