package app.threadmind.network

import app.threadmind.auth.AccessTokenProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.Response as RetrofitResponse
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.Path
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

interface ThreadMindApi {
    @GET("v1/memories")
    suspend fun listMemories(): MemoryListResponse

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
    override suspend fun listMemories(): Nothing = error(reason)
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
        val json = Json { ignoreUnknownKeys = true }
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
