package app.threadmind.network

import app.threadmind.auth.AccessTokenProvider
import app.threadmind.domain.ActionCard
import app.threadmind.domain.ActionStatus
import app.threadmind.domain.ActionType
import app.threadmind.domain.ConfirmedActionSnapshot
import app.threadmind.domain.EvidenceRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.Response as RetrofitResponse
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType

@Serializable
data class MemoryListResponse(val items: List<MemoryRecordResponse>)

@Serializable
data class MemoryRecordResponse(
    val id: String,
    val subjectRefs: List<String>,
    val type: String,
    val assertion: String,
    val epistemicStatus: String,
    val confidence: Double,
    val sensitivity: String,
    val sourceRefs: List<String>,
    val sourceEvidence: List<EvidenceRefResponse> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
    val version: Int,
    val supersedesId: String? = null,
    val status: String,
)

@Serializable
data class MemoryRevisionRequest(
    val assertion: String,
    val sourceRef: String,
)

@Serializable
data class SubmissionResponse(
    val id: String,
    val imageContentType: String,
    val imageByteSize: Long,
    val supplementalText: String? = null,
    val source: String,
    val status: String,
    val failureCode: String? = null,
    val processingStartedAt: String? = null,
    val completedAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class ActionCardListResponse(val items: List<ActionCardResponse>)

@Serializable
data class EvidenceRefResponse(
    val sourceId: String,
    val messageId: String? = null,
    val excerpt: String,
    val confidence: Double,
)

@Serializable
data class ConfirmedActionSnapshotResponse(
    val actionCardId: String,
    val type: String,
    val version: Int,
    val fields: Map<String, JsonElement>,
    val targetAccountId: String,
    val evidence: List<EvidenceRefResponse>,
    val idempotencyKey: String,
)

@Serializable
data class ActionCardResponse(
    val id: String,
    val submissionId: String,
    val type: String,
    val version: Int,
    val fields: Map<String, JsonElement>,
    val evidence: List<EvidenceRefResponse>,
    val fieldConfidence: Map<String, Double> = emptyMap(),
    val validationIssues: List<String> = emptyList(),
    val targetAccountId: String? = null,
    val status: String,
    val blockers: List<String>,
    val confirmedSnapshot: ConfirmedActionSnapshotResponse? = null,
)

@Serializable data class CardVersionRequest(val expectedVersion: Int)
@Serializable data class ActionCardEditRequest(
    val expectedVersion: Int,
    val fields: Map<String, String>,
    val targetAccountId: String,
    val resolvedValidationIssues: List<String> = emptyList(),
)
@Serializable data class ActionReceiptRequest(
    val receiptId: String,
    val status: String,
    val targetRecordId: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

fun ActionCardResponse.toDomain(): ActionCard = ActionCard(
    id = id,
    submissionId = submissionId,
    type = ActionType.valueOf(type.uppercase()),
    version = version,
    fields = fields.mapValues { (_, value) -> value.asFieldString() },
    evidence = evidence.map(EvidenceRefResponse::toDomain),
    fieldConfidence = fieldConfidence,
    validationIssues = validationIssues,
    targetAccountId = targetAccountId,
    status = ActionStatus.valueOf(status.uppercase()),
    blockers = blockers,
    confirmedSnapshot = confirmedSnapshot?.toDomain(),
)

private fun EvidenceRefResponse.toDomain() = EvidenceRef(sourceId, messageId, excerpt, confidence)

private fun ConfirmedActionSnapshotResponse.toDomain() = ConfirmedActionSnapshot(
    actionCardId = actionCardId,
    type = ActionType.valueOf(type.uppercase()),
    version = version,
    fields = fields.mapValues { (_, value) -> value.asFieldString() },
    evidence = evidence.map(EvidenceRefResponse::toDomain),
    targetAccountId = targetAccountId,
    idempotencyKey = idempotencyKey,
)

private fun JsonElement.asFieldString(): String = (this as? JsonPrimitive)?.contentOrNull ?: toString()

interface ThreadMindApi {
    @Multipart
    @POST("v1/submissions")
    suspend fun createSubmission(
        @Part image: MultipartBody.Part,
        @Part("submissionId") submissionId: RequestBody,
        @Part("source") source: RequestBody,
        @Part("supplementalText") supplementalText: RequestBody? = null,
    ): SubmissionResponse

    @GET("v1/submissions/{id}")
    suspend fun getSubmission(@Path("id") id: String): SubmissionResponse

    @GET("v1/submissions/{id}/action-cards")
    suspend fun listActionCards(@Path("id") id: String): ActionCardListResponse

    @POST("v1/action-cards/{id}/confirm")
    suspend fun confirmActionCard(@Path("id") id: String, @Body request: CardVersionRequest): ActionCardResponse

    @PATCH("v1/action-cards/{id}")
    suspend fun editActionCard(@Path("id") id: String, @Body request: ActionCardEditRequest): ActionCardResponse

    @DELETE("v1/action-cards/{id}")
    suspend fun cancelActionCard(@Path("id") id: String): RetrofitResponse<Unit>

    @POST("v1/action-cards/{id}/receipts")
    suspend fun createActionReceipt(@Path("id") id: String, @Body request: ActionReceiptRequest)

    @GET("v1/memories")
    suspend fun listMemories(
        @Query("q") search: String? = null,
        @Query("subjectRef") subjectRef: String? = null,
        @Query("type") type: String? = null,
        @Query("from") createdFrom: String? = null,
        @Query("to") createdTo: String? = null,
    ): MemoryListResponse

    @PATCH("v1/memories/{id}")
    suspend fun reviseMemory(
        @Path("id") id: String,
        @Body request: MemoryRevisionRequest,
    ): MemoryRecordResponse

    @DELETE("v1/memories/{id}")
    suspend fun deleteMemory(@Path("id") id: String): RetrofitResponse<Unit>
}

class UnavailableThreadMindApi(
    private val reason: String,
) : ThreadMindApi {
    override suspend fun createSubmission(image: MultipartBody.Part, submissionId: RequestBody, source: RequestBody, supplementalText: RequestBody?): Nothing = error(reason)
    override suspend fun getSubmission(id: String): Nothing = error(reason)
    override suspend fun listActionCards(id: String): Nothing = error(reason)
    override suspend fun confirmActionCard(id: String, request: CardVersionRequest): Nothing = error(reason)
    override suspend fun editActionCard(id: String, request: ActionCardEditRequest): Nothing = error(reason)
    override suspend fun cancelActionCard(id: String): Nothing = error(reason)
    override suspend fun createActionReceipt(id: String, request: ActionReceiptRequest): Nothing = error(reason)
    override suspend fun listMemories(search: String?, subjectRef: String?, type: String?, createdFrom: String?, createdTo: String?): Nothing = error(reason)
    override suspend fun reviseMemory(id: String, request: MemoryRevisionRequest): Nothing = error(reason)
    override suspend fun deleteMemory(id: String): Nothing = error(reason)
}

class BearerTokenInterceptor(
    private val tokenProvider: AccessTokenProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = requireNotNull(tokenProvider.currentAccessToken()) { "No authenticated Supabase session" }
        val request = chain.request().newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        return chain.proceed(request)
    }
}

object ThreadMindApiFactory {
    fun create(baseUrl: String, tokenProvider: AccessTokenProvider): ThreadMindApi {
        require(
            baseUrl.startsWith("https://") ||
                baseUrl.startsWith("http://10.0.2.2") ||
                baseUrl.startsWith("http://127.0.0.1")
        ) {
            "THREADMIND_API_BASE_URL must use HTTPS (or an Android debug loopback host)"
        }
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(BearerTokenInterceptor(tokenProvider))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ThreadMindApi::class.java)
    }
}
