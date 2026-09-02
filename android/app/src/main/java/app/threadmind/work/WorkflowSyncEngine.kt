package app.threadmind.work

import app.threadmind.auth.AuthRepository
import app.threadmind.data.local.ActionCardCacheEntity
import app.threadmind.data.local.WorkflowDao
import app.threadmind.network.ActionCardResponse
import app.threadmind.network.ActionReceiptRequest
import app.threadmind.network.ThreadMindApi
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkflowSyncEngine @Inject constructor(
    private val auth: AuthRepository,
    private val dao: WorkflowDao,
    private val api: ThreadMindApi,
) {
    suspend fun syncSubmission(accountId: String, submissionId: String, surfaceErrors: Boolean = false): WorkflowSyncResult {
        if (auth.currentUserId() != accountId) return WorkflowSyncResult.FAILURE
        val local = dao.submission(accountId, submissionId) ?: return WorkflowSyncResult.SUCCESS
        return try {
            val uploadedFromDevice = local.status == "pending_upload"
            val remote = if (local.status == "pending_upload") {
                val image = local.localImagePath?.let(::File)
                if (image == null || !image.isFile) {
                    dao.updateSubmissionStatus(
                        accountId,
                        submissionId,
                        "failed",
                        "local_image_missing",
                        System.currentTimeMillis(),
                    )
                    return WorkflowSyncResult.FAILURE
                }
                api.createSubmission(
                    image = MultipartBody.Part.createFormData(
                        "image",
                        image.name,
                        image.readBytes().toRequestBody(local.imageContentType.toMediaType()),
                    ),
                    submissionId = submissionId.textBody(),
                    source = local.source.textBody(),
                    supplementalText = local.supplementalText.takeIf(String::isNotBlank)?.textBody(),
                )
            } else {
                api.getSubmission(submissionId)
            }
            dao.updateSubmissionStatus(accountId, submissionId, remote.status, remote.failureCode, System.currentTimeMillis())
            if (uploadedFromDevice) local.localImagePath?.let(::File)?.delete()
            when (remote.status) {
                "ready" -> {
                    val extraction = api.getExtraction(submissionId)
                    dao.updateSubmissionExtraction(
                        accountId,
                        submissionId,
                        json.encodeToString(extraction),
                        System.currentTimeMillis(),
                    )
                    cacheCards(accountId, submissionId, api.listActionCards(submissionId).items)
                    WorkflowSyncResult.SUCCESS
                }
                "failed", "deleted" -> {
                    local.localImagePath?.let(::File)?.delete()
                    WorkflowSyncResult.SUCCESS
                }
                else -> WorkflowSyncResult.RETRY
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (error is HttpException && error.code() == 404 && local.status != "pending_upload") {
                dao.updateSubmissionStatus(accountId, submissionId, "deleted", null, System.currentTimeMillis())
                if (surfaceErrors) throw error
                return WorkflowSyncResult.SUCCESS
            }
            if (surfaceErrors) throw error
            if (error.isRetryable()) {
                WorkflowSyncResult.RETRY
            } else {
                dao.updateSubmissionStatus(accountId, submissionId, "failed", "client_sync_rejected", System.currentTimeMillis())
                local.localImagePath?.let(::File)?.delete()
                WorkflowSyncResult.FAILURE
            }
        }
    }

    suspend fun syncReceipt(accountId: String, receiptId: String): WorkflowSyncResult {
        if (auth.currentUserId() != accountId) return WorkflowSyncResult.FAILURE
        val pending = dao.pendingReceipts(accountId).firstOrNull { it.receiptId == receiptId }
            ?: return WorkflowSyncResult.SUCCESS
        return try {
            val request = json.decodeFromString<ActionReceiptRequest>(pending.payloadJson)
            api.createActionReceipt(pending.actionCardId, request)
            dao.deletePendingReceipt(accountId, receiptId)
            WorkflowSyncResult.SUCCESS
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (error.isRetryable()) WorkflowSyncResult.RETRY else WorkflowSyncResult.FAILURE
        }
    }

    suspend fun cacheCards(accountId: String, submissionId: String, cards: List<ActionCardResponse>) {
        val now = System.currentTimeMillis()
        dao.upsertCards(cards.map { card ->
            val reviewedVersion = dao.card(accountId, card.id)?.providerReviewedVersion
                ?.takeIf { it == card.version }
            ActionCardCacheEntity(
                accountId = accountId,
                id = card.id,
                submissionId = submissionId,
                version = card.version,
                status = card.status,
                payloadJson = json.encodeToString(card),
                updatedAtEpochMillis = now,
                providerReviewedVersion = reviewedVersion,
            )
        })
    }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}

private fun String.textBody() = toRequestBody("text/plain".toMediaType())

private fun Throwable.isRetryable(): Boolean = when (this) {
    is IOException -> true
    is HttpException -> code() == 401 || code() == 408 || code() == 425 || code() == 429 || code() >= 500
    else -> false
}
