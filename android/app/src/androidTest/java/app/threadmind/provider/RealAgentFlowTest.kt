package app.threadmind.provider

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.ContactsContract
import androidx.test.platform.app.InstrumentationRegistry
import app.threadmind.domain.ActionType
import app.threadmind.domain.ConfirmedActionSnapshot
import app.threadmind.domain.EvidenceRef
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import app.threadmind.network.ActionReceiptRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class RealAgentFlowTest {
    private val accountId = UUID.randomUUID().toString()
    private val submissionId = UUID.randomUUID().toString()

    @Test fun screenshotToRealProviderMemoryAndModelInsight() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val response = multipart("v1/submissions", screenshot(), mapOf("submissionId" to submissionId, "source" to "in_app",
            "supplementalText" to "Lin says: please save my contact lin-$submissionId@example.com. Lin prefers formal proposals by email."))
        assertEquals(202, response.first)
        val submission = pollReady()
        assertEquals("ready", submission.getString("status"))
        val cards = get("v1/submissions/$submissionId/action-cards").getJSONArray("items")
        val original = (0 until cards.length()).map { cards.getJSONObject(it) }.first { it.getString("type") == "create_contact" }
        val fields = original.getJSONObject("fields").apply {
            put("displayName", "Lin Agent E2E")
            put("contactMethod", "lin-$submissionId@example.com")
            put("company", "Synthetic Acme")
            put("jobTitle", "Synthetic Buyer")
        }
        val issues = original.getJSONArray("validationIssues")
        val edited = request("PATCH", "v1/action-cards/${original.getString("id")}", JSONObject().apply {
            put("expectedVersion", original.getInt("version")); put("fields", fields); put("targetAccountId", "local")
            put("resolvedValidationIssues", issues)
        }).second
        val confirmed = request("POST", "v1/action-cards/${original.getString("id")}/confirm",
            JSONObject().put("expectedVersion", edited.getInt("version"))).second
        val confirmedFields = confirmed.getJSONObject("confirmedSnapshot").getJSONObject("fields")
        val provider = AndroidProviderExecutor(context)
        val providerResult = provider.execute(ConfirmedActionSnapshot(
            actionCardId = confirmed.getString("id"), type = ActionType.CREATE_CONTACT, version = confirmed.getInt("version"),
            fields = confirmedFields.keys().asSequence().associateWith { confirmedFields.get(it).toString() },
            evidence = listOf(EvidenceRef(submissionId, null, "lin-$submissionId@example.com", 1.0)),
            targetAccountId = "local", idempotencyKey = confirmed.getJSONObject("confirmedSnapshot").getString("idempotencyKey"),
        )) as ProviderResult.Succeeded
        try {
            val receipt = ActionReceiptRequest(UUID.randomUUID().toString(), "succeeded", providerResult.targetRecordId,
                contactContext = providerResult.contactContext)
            val recorded = requestText("POST", "v1/action-cards/${confirmed.getString("id")}/receipts", json.encodeToString(receipt))
            assertEquals(201, recorded.first)
            val memories = get("v1/memories").getJSONArray("items")
            assertTrue(memories.length() >= 2)
            val insights = get("v1/insights?submissionId=$submissionId").getJSONArray("items")
            assertEquals(1, insights.length())
            val serialized = insights.toString()
            assertTrue(serialized.contains("contact:"))
            assertTrue(serialized.contains("lin-$submissionId@example.com"))
            assertTrue(insights.getJSONObject(0).getJSONArray("items").toString().contains("suggestedAction"))
        } finally {
            context.contentResolver.delete(ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts.CONTACT_ID} = ?", arrayOf(providerResult.targetRecordId))
            request("DELETE", "v1/submissions/$submissionId", JSONObject())
        }
    }

    private fun pollReady(): JSONObject {
        repeat(90) {
            val current = get("v1/submissions/$submissionId")
            if (current.getString("status") in setOf("ready", "failed")) return current
            Thread.sleep(1000)
        }
        error("analysis timeout")
    }

    private fun screenshot(): ByteArray {
        val bitmap = Bitmap.createBitmap(1080, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { color = Color.BLACK; textSize = 36f }
        canvas.drawText("Lin: Save my contact: lin@example.com", 50f, 180f, paint)
        canvas.drawText("Lin: I prefer formal proposals by email.", 50f, 260f, paint)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun multipart(path: String, image: ByteArray, fields: Map<String, String>): Pair<Int, String> {
        val boundary = "ThreadMind${UUID.randomUUID()}"; val connection = open(path, "POST")
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary"); connection.doOutput = true
        connection.outputStream.buffered().use { out ->
            fields.forEach { (key, value) -> out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$key\"\r\n\r\n$value\r\n".toByteArray()) }
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"chat.png\"\r\nContent-Type: image/png\r\n\r\n".toByteArray())
            out.write(image); out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        return connection.response()
    }

    private fun get(path: String) = request("GET", path, null).second
    private fun request(method: String, path: String, body: JSONObject?): Pair<Int, JSONObject> {
        val response = requestText(method, path, body?.toString())
        return response.first to if (response.second.isBlank()) JSONObject() else JSONObject(response.second)
    }
    private fun requestText(method: String, path: String, body: String?): Pair<Int, String> {
        val connection = open(path, method)
        if (body != null) { connection.setRequestProperty("Content-Type", "application/json"); connection.doOutput = true; connection.outputStream.use { it.write(body.toByteArray()) } }
        return connection.response()
    }
    private fun open(path: String, method: String) = (URL("http://127.0.0.1:3000/$path").openConnection() as HttpURLConnection).apply {
        requestMethod = method; connectTimeout = 10_000; readTimeout = 120_000; setRequestProperty("x-account-id", accountId)
    }
    private fun HttpURLConnection.response(): Pair<Int, String> {
        val code = responseCode; val text = (if (code >= 400) errorStream else inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code >= 400) error("HTTP $code: $text")
        return code to text
    }

    companion object { private val json = Json { explicitNulls = false } }
}
