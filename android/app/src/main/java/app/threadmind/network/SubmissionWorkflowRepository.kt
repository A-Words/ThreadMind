package app.threadmind.network

import android.content.Context
import android.net.Uri
import app.threadmind.auth.AuthRepository
import app.threadmind.data.local.PendingReceiptEntity
import app.threadmind.data.local.PendingSubmissionEntity
import app.threadmind.data.local.WorkflowDao
import app.threadmind.domain.ActionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import app.threadmind.work.WorkflowSyncEngine
import app.threadmind.work.WorkflowSyncResult
import app.threadmind.work.WorkflowWorkScheduler

data class SubmissionProgress(
    val submissionId: String,
    val status: String,
    val cards: List<ActionCard>,
    val failureCode: String? = null,
    val pendingReceipts: Map<String, ActionReceiptRequest> = emptyMap(),
    val providerReviewedVersions: Set<String> = emptySet(),
)

data class AccountExportPayload(
    val requestId: String,
    val fileName: String,
    val json: String,
)

interface SubmissionWorkflowRepository {
    suspend fun submit(uri: Uri, submissionId: String, source: String, supplementalText: String): SubmissionProgress
    suspend fun refresh(submissionId: String): SubmissionProgress
    suspend fun restoreLatest(): SubmissionProgress?
    suspend fun edit(
        cardId: String,
        expectedVersion: Int,
        fields: Map<String, String>,
        targetAccountId: String,
        resolvedValidationIssues: List<String>,
        type: app.threadmind.domain.ActionType? = null,
    ): ActionCard
    suspend fun confirm(cardId: String, expectedVersion: Int): ActionCard
    suspend fun cancel(cardId: String)
    suspend fun reportExecution(cardId: String, request: ActionReceiptRequest)
    suspend fun markProviderReviewed(cardId: String, version: Int)
    suspend fun deleteSubmission(submissionId: String)
    suspend fun clearMemories(): Int
    suspend fun prepareAccountExport(): AccountExportPayload
    suspend fun writeAccountExport(uri: Uri, payload: AccountExportPayload)
    suspend fun deleteAccount()
}

class AndroidSubmissionWorkflowRepository(
    private val context: Context,
    private val api: ThreadMindApi,
    private val auth: AuthRepository,
    private val dao: WorkflowDao,
    private val scheduler: WorkflowWorkScheduler,
    private val syncEngine: WorkflowSyncEngine,
) : SubmissionWorkflowRepository {
    override suspend fun submit(
        uri: Uri,
        submissionId: String,
        source: String,
        supplementalText: String,
    ): SubmissionProgress {
        val accountId = requireNotNull(auth.currentUserId()) { "没有可用的登录会话" }
        val upload = readUpload(uri)
        val localFile = persistUpload(submissionId, upload)
        val now = System.currentTimeMillis()
        dao.upsertSubmission(
            PendingSubmissionEntity(
                accountId = accountId,
                id = submissionId,
                localImagePath = localFile.absolutePath,
                imageContentType = upload.contentType,
                source = source,
                supplementalText = supplementalText.trim(),
                status = "pending_upload",
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )
        scheduler.enqueueSubmission(accountId, submissionId)
        if (syncEngine.syncSubmission(accountId, submissionId) == WorkflowSyncResult.FAILURE) {
            throw IllegalStateException("提交无法同步，请重新选择截图")
        }
        return localProgress(accountId, submissionId)
    }

    override suspend fun refresh(submissionId: String): SubmissionProgress {
        val accountId = requireNotNull(auth.currentUserId()) { "没有可用的登录会话" }
        scheduler.enqueueSubmission(accountId, submissionId)
        syncEngine.syncSubmission(accountId, submissionId)
        return localProgress(accountId, submissionId)
    }

    override suspend fun restoreLatest(): SubmissionProgress? {
        val accountId = auth.currentUserId() ?: return null
        val latest = dao.latestSubmission(accountId) ?: return null
        scheduler.enqueueSubmission(accountId, latest.id)
        return localProgress(accountId, latest.id)
    }

    override suspend fun confirm(cardId: String, expectedVersion: Int): ActionCard {
        val response = api.confirmActionCard(cardId, CardVersionRequest(expectedVersion))
        cache(response)
        return response.toDomain()
    }

    override suspend fun edit(
        cardId: String,
        expectedVersion: Int,
        fields: Map<String, String>,
        targetAccountId: String,
        resolvedValidationIssues: List<String>,
        type: app.threadmind.domain.ActionType?,
    ): ActionCard {
        val response = api.editActionCard(
            cardId,
            ActionCardEditRequest(
                expectedVersion = expectedVersion,
                type = type?.name?.lowercase(),
                fields = fields,
                targetAccountId = targetAccountId,
                resolvedValidationIssues = resolvedValidationIssues,
            ),
        )
        cache(response)
        return response.toDomain()
    }

    override suspend fun cancel(cardId: String) {
        val response = api.cancelActionCard(cardId)
        check(response.isSuccessful) { "取消失败（HTTP ${response.code()}）" }
        cacheStatus(cardId, "cancelled")
    }

    override suspend fun reportExecution(cardId: String, request: ActionReceiptRequest) {
        val accountId = requireNotNull(auth.currentUserId()) { "没有可用的登录会话" }
        val now = System.currentTimeMillis()
        val cardPayload = dao.card(accountId, cardId)?.let { cached ->
            WorkflowSyncEngine.json.decodeFromString<ActionCardResponse>(cached.payloadJson)
                .copy(status = request.status)
                .let(WorkflowSyncEngine.json::encodeToString)
        }
        dao.recordPendingReceipt(
            PendingReceiptEntity(
                accountId = accountId,
                receiptId = request.receiptId,
                actionCardId = cardId,
                payloadJson = WorkflowSyncEngine.json.encodeToString(request),
                createdAtEpochMillis = now,
            ),
            cardStatus = request.status,
            cardPayloadJson = cardPayload,
            updatedAt = now,
        )
        scheduler.enqueueReceipt(accountId, request.receiptId)
        when (syncEngine.syncReceipt(accountId, request.receiptId)) {
            WorkflowSyncResult.SUCCESS -> Unit
            WorkflowSyncResult.RETRY -> throw IOException("执行回执已保存在设备上，等待网络恢复")
            WorkflowSyncResult.FAILURE -> throw IllegalStateException("执行回执无法同步")
        }
    }

    override suspend fun markProviderReviewed(cardId: String, version: Int) {
        val accountId = requireNotNull(auth.currentUserId()) { "没有可用的登录会话" }
        check(dao.markProviderReviewed(accountId, cardId, version) == 1) { "卡片版本已变化，请重新检查" }
    }

    override suspend fun deleteSubmission(submissionId: String) {
        val accountId = requireNotNull(auth.currentUserId()) { "没有可用的登录会话" }
        val response = api.deleteSubmission(submissionId)
        check(response.isSuccessful) { "删除提交失败（HTTP ${response.code()}）" }
        scheduler.cancelSubmission(accountId, submissionId)
        val local = dao.deleteSubmissionData(accountId, submissionId)
        local.pendingReceiptIds.forEach { scheduler.cancelReceipt(accountId, it) }
        local.localImagePath?.let(::File)?.delete()
    }

    override suspend fun clearMemories(): Int = api.clearMemories().cleared

    override suspend fun prepareAccountExport(): AccountExportPayload {
        val json = withContext(Dispatchers.IO) { api.exportAccount().use { it.string() } }
        check(json.isNotBlank()) { "导出内容为空" }
        return AccountExportPayload(
            requestId = java.util.UUID.randomUUID().toString(),
            fileName = "threadmind-export-${System.currentTimeMillis()}.json",
            json = json,
        )
    }

    override suspend fun writeAccountExport(uri: Uri, payload: AccountExportPayload) = withContext(Dispatchers.IO) {
        val output = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("无法打开导出文件")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(payload.json) }
    }

    override suspend fun deleteAccount() {
        val accountId = requireNotNull(auth.currentUserId()) { "没有可用的登录会话" }
        val localImagePaths = dao.accountImagePaths(accountId)
        val response = api.deleteAccount()
        check(response.isSuccessful) { "删除账户失败（HTTP ${response.code()}）" }
        scheduler.cancelAccount(accountId)
        dao.clearAccount(accountId)
        localImagePaths.forEach { File(it).delete() }
        auth.signOut()
    }

    private suspend fun cache(response: ActionCardResponse) {
        val accountId = requireNotNull(auth.currentUserId()) { "没有可用的登录会话" }
        syncEngine.cacheCards(accountId, response.submissionId, listOf(response))
    }

    private suspend fun cacheStatus(cardId: String, status: String) {
        val accountId = requireNotNull(auth.currentUserId()) { "没有可用的登录会话" }
        val cached = dao.card(accountId, cardId) ?: return
        val payload = WorkflowSyncEngine.json.decodeFromString<ActionCardResponse>(cached.payloadJson)
            .copy(status = status)
        dao.updateCardOutcome(
            accountId = accountId,
            cardId = cardId,
            status = status,
            payloadJson = WorkflowSyncEngine.json.encodeToString(payload),
            updatedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun localProgress(accountId: String, submissionId: String): SubmissionProgress {
        val submission = requireNotNull(dao.submission(accountId, submissionId)) { "本地提交不存在" }
        val cards = if (submission.status == "ready") {
            dao.cards(accountId, submissionId).map { cache ->
                WorkflowSyncEngine.json.decodeFromString<ActionCardResponse>(cache.payloadJson).toDomain()
            }
        } else emptyList()
        val receipts = dao.pendingReceipts(accountId).associate { pending ->
            pending.actionCardId to WorkflowSyncEngine.json.decodeFromString<ActionReceiptRequest>(pending.payloadJson)
        }
        val reviewed = dao.cards(accountId, submissionId)
            .filter { it.providerReviewedVersion == it.version }
            .mapTo(mutableSetOf()) { "${it.id}:${it.version}" }
        return SubmissionProgress(submissionId, submission.status, cards, submission.failureCode, receipts, reviewed)
    }

    private fun persistUpload(submissionId: String, upload: ImageUpload): File {
        val directory = File(context.noBackupFilesDir, "pending-submissions").apply { mkdirs() }
        return File(directory, "$submissionId.${upload.extension}").apply { writeBytes(upload.bytes) }
    }

    private suspend fun readUpload(uri: Uri): ImageUpload = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("无法读取所选图片")
        val bytes = stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_IMAGE_BYTES) { "截图不能超过 15 MiB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.isNotEmpty()) { "截图不能为空" }
        val contentType = detectImageContentType(bytes)
            ?: throw IllegalArgumentException("只支持 PNG、JPEG 或 WebP 截图")
        val extension = when (contentType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            else -> "webp"
        }
        ImageUpload(bytes, contentType, extension)
    }

    private data class ImageUpload(val bytes: ByteArray, val contentType: String, val extension: String)

    private companion object {
        const val MAX_IMAGE_BYTES = 15 * 1024 * 1024
    }
}

private fun detectImageContentType(bytes: ByteArray): String? = when {
    bytes.size >= 8 && bytes.sliceArray(0 until 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> "image/png"
    bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
    bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" && bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "image/webp"
    else -> null
}
