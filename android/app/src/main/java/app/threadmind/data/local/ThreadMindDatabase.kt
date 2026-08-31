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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCards(entities: List<ActionCardCacheEntity>)

    @Query("select * from action_card_cache where accountId = :accountId and submissionId = :submissionId order by id")
    suspend fun cards(accountId: String, submissionId: String): List<ActionCardCacheEntity>

    @Query("select * from action_card_cache where accountId = :accountId and id = :cardId")
    suspend fun card(accountId: String, cardId: String): ActionCardCacheEntity?

    @Query("update action_card_cache set status = :status, payloadJson = :payloadJson, updatedAtEpochMillis = :updatedAt where accountId = :accountId and id = :cardId")
    suspend fun updateCardOutcome(accountId: String, cardId: String, status: String, payloadJson: String, updatedAt: Long)

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
    version = 1,
    exportSchema = true,
)
abstract class ThreadMindDatabase : RoomDatabase() {
    abstract fun workflowDao(): WorkflowDao
}
