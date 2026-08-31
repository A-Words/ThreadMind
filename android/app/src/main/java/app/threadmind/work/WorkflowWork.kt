package app.threadmind.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Inject

interface WorkflowWorkScheduler {
    fun enqueueSubmission(accountId: String, submissionId: String)
    fun enqueueReceipt(accountId: String, receiptId: String)
    fun cancelSubmission(accountId: String, submissionId: String)
    fun cancelReceipt(accountId: String, receiptId: String)
    fun cancelAccount(accountId: String)
}

class AndroidWorkflowWorkScheduler @Inject constructor(
    private val workManager: WorkManager,
) : WorkflowWorkScheduler {
    override fun enqueueSubmission(accountId: String, submissionId: String) {
        workManager.enqueueUniqueWork(
            submissionWorkName(accountId, submissionId),
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SubmissionSyncWorker>()
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .setInputData(workDataOf(ACCOUNT_ID to accountId, SUBMISSION_ID to submissionId))
                .addTag(accountTag(accountId))
                .build(),
        )
    }

    override fun enqueueReceipt(accountId: String, receiptId: String) {
        workManager.enqueueUniqueWork(
            receiptWorkName(accountId, receiptId),
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ReceiptSyncWorker>()
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .setInputData(workDataOf(ACCOUNT_ID to accountId, RECEIPT_ID to receiptId))
                .addTag(accountTag(accountId))
                .build(),
        )
    }

    override fun cancelAccount(accountId: String) {
        workManager.cancelAllWorkByTag(accountTag(accountId))
    }

    override fun cancelSubmission(accountId: String, submissionId: String) {
        workManager.cancelUniqueWork(submissionWorkName(accountId, submissionId))
    }

    override fun cancelReceipt(accountId: String, receiptId: String) {
        workManager.cancelUniqueWork(receiptWorkName(accountId, receiptId))
    }

    private companion object {
        val networkConstraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    }
}

@HiltWorker
class SubmissionSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val engine: WorkflowSyncEngine,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val accountId = inputData.requiredString(ACCOUNT_ID)
        val submissionId = inputData.requiredString(SUBMISSION_ID)
        return engine.syncSubmission(accountId, submissionId).toWorkerResult()
    }
}

@HiltWorker
class ReceiptSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val engine: WorkflowSyncEngine,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val accountId = inputData.requiredString(ACCOUNT_ID)
        val receiptId = inputData.requiredString(RECEIPT_ID)
        return engine.syncReceipt(accountId, receiptId).toWorkerResult()
    }
}

enum class WorkflowSyncResult { SUCCESS, RETRY, FAILURE }

private fun WorkflowSyncResult.toWorkerResult() = when (this) {
    WorkflowSyncResult.SUCCESS -> ListenableWorker.Result.success()
    WorkflowSyncResult.RETRY -> ListenableWorker.Result.retry()
    WorkflowSyncResult.FAILURE -> ListenableWorker.Result.failure()
}

private fun Data.requiredString(key: String) = requireNotNull(getString(key)) { "Missing $key" }
private fun accountTag(accountId: String) = "threadmind:account:$accountId"
private fun submissionWorkName(accountId: String, submissionId: String) = "threadmind:submission:$accountId:$submissionId"
private fun receiptWorkName(accountId: String, receiptId: String) = "threadmind:receipt:$accountId:$receiptId"

private const val ACCOUNT_ID = "account_id"
private const val SUBMISSION_ID = "submission_id"
private const val RECEIPT_ID = "receipt_id"
