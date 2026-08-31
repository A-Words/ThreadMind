package app.threadmind.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction

@Entity(
    tableName = "pending_submissions",
    primaryKeys = ["accountId", "id"],
    indices = [Index(value = ["accountId", "status"])],
)
data class PendingSubmissionEntity(
    val accountId: String,
    val id: String,
    val localImagePath: String,
    val imageContentType: String,
    val source: String,
    val supplementalText: String,
    val status: String,
    val failureCode: String? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "action_card_cache",
    primaryKeys = ["accountId", "id"],
    indices = [Index(value = ["accountId", "submissionId"])],
)
data class ActionCardCacheEntity(
    val accountId: String,
    val id: String,
    val submissionId: String,
    val version: Int,
    val status: String,
    val payloadJson: String,
    val updatedAtEpochMillis: Long,
    val providerReviewedVersion: Int? = null,
)

@Entity(
    tableName = "pending_receipts",
    primaryKeys = ["accountId", "receiptId"],
    indices = [Index(value = ["accountId", "actionCardId"])],
)
data class PendingReceiptEntity(
    val accountId: String,
    val receiptId: String,
    val actionCardId: String,
    val payloadJson: String,
    val createdAtEpochMillis: Long,
    val lastAttemptAtEpochMillis: Long? = null,
)

data class LocalSubmissionDeletion(
    val localImagePath: String?,
    val pendingReceiptIds: List<String>,
)

@Dao
interface WorkflowDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSubmission(entity: PendingSubmissionEntity)

    @Query("select * from pending_submissions where accountId = :accountId and id = :submissionId")
    suspend fun submission(accountId: String, submissionId: String): PendingSubmissionEntity?

    @Query("select * from pending_submissions where accountId = :accountId order by updatedAtEpochMillis desc limit 1")
    suspend fun latestSubmission(accountId: String): PendingSubmissionEntity?

    @Query("select * from pending_submissions where accountId = :accountId and status not in ('ready', 'failed', 'deleted') order by createdAtEpochMillis")
    suspend fun recoverableSubmissions(accountId: String): List<PendingSubmissionEntity>

    @Query("update pending_submissions set status = :status, failureCode = :failureCode, updatedAtEpochMillis = :updatedAt where accountId = :accountId and id = :submissionId")
    suspend fun updateSubmissionStatus(accountId: String, submissionId: String, status: String, failureCode: String?, updatedAt: Long)

    @Query("delete from pending_submissions where accountId = :accountId and id = :submissionId")
    suspend fun deleteSubmission(accountId: String, submissionId: String)

    @Query("select localImagePath from pending_submissions where accountId = :accountId and id = :submissionId")
    suspend fun submissionImagePath(accountId: String, submissionId: String): String?

    @Query("select localImagePath from pending_submissions where accountId = :accountId")
    suspend fun accountImagePaths(accountId: String): List<String>

    @Query("select receiptId from pending_receipts where accountId = :accountId and actionCardId in (select id from action_card_cache where accountId = :accountId and submissionId = :submissionId)")
    suspend fun submissionReceiptIds(accountId: String, submissionId: String): List<String>

    @Query("delete from pending_receipts where accountId = :accountId and actionCardId in (select id from action_card_cache where accountId = :accountId and submissionId = :submissionId)")
    suspend fun deleteSubmissionReceipts(accountId: String, submissionId: String)

    @Query("delete from action_card_cache where accountId = :accountId and submissionId = :submissionId")
    suspend fun deleteSubmissionCards(accountId: String, submissionId: String)

    @Transaction
    suspend fun deleteSubmissionData(accountId: String, submissionId: String): LocalSubmissionDeletion {
        val deletion = LocalSubmissionDeletion(
            localImagePath = submissionImagePath(accountId, submissionId),
            pendingReceiptIds = submissionReceiptIds(accountId, submissionId),
        )
        deleteSubmissionReceipts(accountId, submissionId)
        deleteSubmissionCards(accountId, submissionId)
        deleteSubmission(accountId, submissionId)
        return deletion
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCards(entities: List<ActionCardCacheEntity>)

    @Query("select * from action_card_cache where accountId = :accountId and submissionId = :submissionId order by id")
    suspend fun cards(accountId: String, submissionId: String): List<ActionCardCacheEntity>

    @Query("select * from action_card_cache where accountId = :accountId and id = :cardId")
    suspend fun card(accountId: String, cardId: String): ActionCardCacheEntity?

    @Query("update action_card_cache set status = :status, payloadJson = :payloadJson, updatedAtEpochMillis = :updatedAt where accountId = :accountId and id = :cardId")
    suspend fun updateCardOutcome(accountId: String, cardId: String, status: String, payloadJson: String, updatedAt: Long)

    @Query("update action_card_cache set providerReviewedVersion = :version where accountId = :accountId and id = :cardId and version = :version")
    suspend fun markProviderReviewed(accountId: String, cardId: String, version: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingReceipt(entity: PendingReceiptEntity)

    @Transaction
    suspend fun recordPendingReceipt(
        entity: PendingReceiptEntity,
        cardStatus: String,
        cardPayloadJson: String?,
        updatedAt: Long,
    ) {
        upsertPendingReceipt(entity)
        if (cardPayloadJson != null) {
            updateCardOutcome(entity.accountId, entity.actionCardId, cardStatus, cardPayloadJson, updatedAt)
        }
    }

    @Query("select * from pending_receipts where accountId = :accountId order by createdAtEpochMillis")
    suspend fun pendingReceipts(accountId: String): List<PendingReceiptEntity>

    @Query("delete from pending_receipts where accountId = :accountId and receiptId = :receiptId")
    suspend fun deletePendingReceipt(accountId: String, receiptId: String)

    @Query("delete from pending_submissions where accountId = :accountId")
    suspend fun clearSubmissions(accountId: String)

    @Query("delete from action_card_cache where accountId = :accountId")
    suspend fun clearCards(accountId: String)

    @Query("delete from pending_receipts where accountId = :accountId")
    suspend fun clearReceipts(accountId: String)

    @Transaction
    suspend fun clearAccount(accountId: String) {
        clearReceipts(accountId)
        clearCards(accountId)
        clearSubmissions(accountId)
    }
}

@Database(
    entities = [PendingSubmissionEntity::class, ActionCardCacheEntity::class, PendingReceiptEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class ThreadMindDatabase : RoomDatabase() {
    abstract fun workflowDao(): WorkflowDao
}
