package app.threadmind.network

import android.content.Context
import android.net.Uri
import app.threadmind.domain.ActionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

data class SubmissionProgress(
    val submissionId: String,
    val status: String,
    val cards: List<ActionCard>,
    val failureCode: String? = null,
)

interface SubmissionWorkflowRepository {
    suspend fun submit(uri: Uri, submissionId: String, source: String, supplementalText: String): SubmissionProgress
    suspend fun refresh(submissionId: String): SubmissionProgress
    suspend fun edit(cardId: String, expectedVersion: Int, fields: Map<String, String>, targetAccountId: String, resolvedValidationIssues: List<String>): ActionCard
    suspend fun confirm(cardId: String, expectedVersion: Int): ActionCard
    suspend fun cancel(cardId: String)
    suspend fun reportExecution(cardId: String, request: ActionReceiptRequest)
}

class AndroidSubmissionWorkflowRepository(
    private val context: Context,
    private val api: ThreadMindApi,
) : SubmissionWorkflowRepository {
    override suspend fun submit(
        uri: Uri,
        submissionId: String,
        source: String,
        supplementalText: String,
    ): SubmissionProgress {
        val upload = readUpload(uri)
        val response = api.createSubmission(
            image = MultipartBody.Part.createFormData(
                "image",
                "screenshot.${upload.extension}",
                upload.bytes.toRequestBody(upload.contentType.toMediaType()),
            ),
            submissionId = submissionId.textBody(),
            source = source.textBody(),
            supplementalText = supplementalText.trim().takeIf(String::isNotEmpty)?.textBody(),
        )
        return response.toProgress()
    }

    override suspend fun refresh(submissionId: String): SubmissionProgress {
        val submission = api.getSubmission(submissionId)
        val cards = if (submission.status == "ready") {
            api.listActionCards(submissionId).items.map(ActionCardResponse::toDomain)
        } else {
            emptyList()
        }
        return submission.toProgress(cards)
    }

    override suspend fun confirm(cardId: String, expectedVersion: Int): ActionCard =
        api.confirmActionCard(cardId, CardVersionRequest(expectedVersion)).toDomain()

    override suspend fun edit(
        cardId: String,
        expectedVersion: Int,
        fields: Map<String, String>,
        targetAccountId: String,
        resolvedValidationIssues: List<String>,
    ): ActionCard = api.editActionCard(
        cardId,
        ActionCardEditRequest(expectedVersion, fields, targetAccountId, resolvedValidationIssues),
    ).toDomain()

    override suspend fun cancel(cardId: String) {
        val response = api.cancelActionCard(cardId)
        check(response.isSuccessful) { "取消失败（HTTP ${response.code()}）" }
    }

    override suspend fun reportExecution(cardId: String, request: ActionReceiptRequest) {
        api.createActionReceipt(cardId, request)
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

private fun String.textBody() = toRequestBody("text/plain".toMediaType())

private fun SubmissionResponse.toProgress(cards: List<ActionCard> = emptyList()) = SubmissionProgress(
    submissionId = id,
    status = status,
    cards = cards,
    failureCode = failureCode,
)
