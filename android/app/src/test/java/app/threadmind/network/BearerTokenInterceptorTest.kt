package app.threadmind.network

import app.threadmind.auth.AccessTokenProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class BearerTokenInterceptorTest {
    @Test fun `adds the current Supabase access token`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{}"))
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .addInterceptor(BearerTokenInterceptor(AccessTokenProvider { "jwt-123" }))
                .build()
            client.newCall(Request.Builder().url(server.url("/v1/memories")).build()).execute().use { response ->
                assertEquals(200, response.code)
            }
            assertEquals("Bearer jwt-123", server.takeRequest().getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }
}
