package app.threadmind.workspace

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import app.threadmind.auth.AuthRepository
import app.threadmind.auth.UnavailableAuthRepository
import app.threadmind.data.local.*
import app.threadmind.di.DatabaseModule
import app.threadmind.network.*
import app.threadmind.work.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class WorkflowCacheTest {
    @Test fun migrationPreservesV3DataAndPermitsRemoteRecordsWithoutAnImage() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "migration-qa-${UUID.randomUUID()}.db"
        val schema = JSONObject(instrumentation.context.assets.open("app.threadmind.data.local.ThreadMindDatabase/3.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { sqlite ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) sqlite.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            sqlite.execSQL("INSERT INTO pending_submissions VALUES ('a','old','/synthetic.png','image/png','in_app','synthetic','ready',NULL,NULL,1,2)")
            sqlite.execSQL("INSERT INTO pending_receipts VALUES ('a','receipt','card','{}',1,NULL)")
            sqlite.version = 3
        }
        val db = Room.databaseBuilder(context, ThreadMindDatabase::class.java, name).addMigrations(DatabaseModule.MIGRATION_3_4).build()
        try {
            assertEquals("/synthetic.png", db.workflowDao().submission("a", "old")?.localImagePath)
            assertEquals(1, db.workflowDao().pendingReceipts("a").size)
            db.workflowDao().upsertSubmission(PendingSubmissionEntity("a", "remote", null, "image/png", "in_app", "", "ready", createdAtEpochMillis = 3, updatedAtEpochMillis = 3))
            assertNull(db.workflowDao().submission("a", "remote")?.localImagePath)
            assertTrue(db.workflowDao().history("b").isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun openingCloudHistoryNeverUploadsAndPreservesPendingDeviceReceipts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ThreadMindDatabase::class.java).build()
        var reads = 0
        var syncFailure: Throwable? = null
        val auth = object : AuthRepository by UnavailableAuthRepository("unused") { override fun currentUserId() = "a" }
        val api = object : ThreadMindApi by UnavailableThreadMindApi("unexpected network operation") {
            override suspend fun getSubmission(id: String): SubmissionResponse {
                syncFailure?.let { throw it }
                reads++
                return SubmissionResponse(id, "image/png", 100, source = "in_app", status = "ready", createdAt = "2026-09-01T00:00:00Z", updatedAt = "2026-09-01T00:00:00Z")
            }
            override suspend fun getExtraction(id: String) = ExtractionResponse("extraction", id, emptyList(), emptyList(), "2026-09-01T00:00:00Z")
            override suspend fun listActionCards(id: String) = ActionCardListResponse(listOf(ActionCardResponse(
                "card", id, "create_contact", 1, emptyMap(), emptyList(), status = "confirmed", blockers = emptyList())))
        }
        val scheduler = object : WorkflowWorkScheduler {
            override fun enqueueSubmission(accountId: String, submissionId: String) = Unit
            override fun enqueueReceipt(accountId: String, receiptId: String) = Unit
            override fun cancelSubmission(accountId: String, submissionId: String) = Unit
            override fun cancelReceipt(accountId: String, receiptId: String) = Unit
            override fun cancelAccount(accountId: String) = Unit
        }
        try {
            val engine = WorkflowSyncEngine(auth, db.workflowDao(), api)
            val repository = AndroidSubmissionWorkflowRepository(context, api, auth, db.workflowDao(), scheduler, engine)
            val opened = repository.open("remote")
            assertTrue(opened.remoteOnly)
            assertEquals(2, reads)
            assertNull(db.workflowDao().submission("a", "remote")?.localImagePath)
            assertEquals(emptySet<String>(), opened.providerReviewedVersions)
            db.workflowDao().upsertPendingReceipt(PendingReceiptEntity("a", "receipt", "card", "{\"receiptId\":\"receipt\",\"status\":\"succeeded\",\"targetRecordId\":\"test\"}", 1))
            val refreshed = repository.refresh("remote")
            assertEquals(app.threadmind.domain.ActionStatus.SUCCEEDED, refreshed.cards.single().status)
            assertTrue("card" in refreshed.pendingReceipts)
            syncFailure = java.io.IOException("Synthetic offline state")
            val offline = repository.refresh("remote")
            assertEquals("ready", offline.status)
            assertNotNull(offline.syncMessage)
            syncFailure = kotlinx.coroutines.CancellationException("Synthetic account switch")
            try { repository.refresh("remote"); fail("Cancellation must propagate") }
            catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals("ready", db.workflowDao().submission("a", "remote")?.status)
            assertEquals(1, db.workflowDao().pendingReceipts("a").size)
        } finally { db.close() }
    }
}
