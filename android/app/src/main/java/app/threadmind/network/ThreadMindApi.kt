package app.threadmind.network

import app.threadmind.auth.AccessTokenProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import okhttp3.MediaType.Companion.toMediaType

@Serializable
data class MemoryListResponse(val items: List<MemoryRecordResponse>)

@Serializable
data class MemoryRecordResponse(
    val id: String,
    val assertion: String,
    val epistemicStatus: String,
    val confidence: Double,
    val sensitivity: String,
)

interface ThreadMindApi {
    @GET("v1/memories")
    suspend fun listMemories(): MemoryListResponse
}

class UnavailableThreadMindApi(
    private val reason: String,
) : ThreadMindApi {
    override suspend fun listMemories(): Nothing = error(reason)
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
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://10.0.2.2")) {
            "THREADMIND_API_BASE_URL must use HTTPS (or the Android emulator loopback host)"
        }
        val json = Json { ignoreUnknownKeys = true }
        val client = OkHttpClient.Builder()
            .addInterceptor(BearerTokenInterceptor(tokenProvider))
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ThreadMindApi::class.java)
    }
}
