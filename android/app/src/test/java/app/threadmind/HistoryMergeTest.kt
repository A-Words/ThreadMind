package app.threadmind

import app.threadmind.network.SubmissionSummaryResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryMergeTest {
    private fun row(id: String, status: String, counts: Map<String, Int> = emptyMap()) =
        SubmissionSummaryResponse(id, "2026-09-03T00:00:00Z", "2026-09-03T00:00:00Z", "in_app", status, counts)

    @Test fun `cloud summaries replace stale cache but not unsent work or device receipts`() {
        val result = mergeHistory(emptyList(), listOf(row("a", "ready"), row("b", "ready"), row("c", "ready")),
            listOf(row("a", "processing"), row("b", "pending_upload"), row("c", "ready", mapOf("executing" to 1))), "all")
        assertEquals(3, result.size)
        assertEquals("ready", result.single { it.id == "a" }.status)
        assertEquals("pending_upload", result.single { it.id == "b" }.status)
        assertEquals(1, result.single { it.id == "c" }.actionCounts["executing"])
    }

    @Test fun `attention filter excludes completed items and page merging has stable ties`() {
        val result = mergeHistory(listOf(row("a", "uploaded")), listOf(row("a", "ready"), row("b", "processing")),
            listOf(row("c", "failed")), "attention")
        assertEquals(listOf("c", "b"), result.map { it.id })
    }
}
