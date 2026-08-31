package app.threadmind.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionFieldSpecTest {
    @Test
    fun `meeting review exposes every required and optional PRD field`() {
        val fields = actionFieldSpecs(ActionType.CREATE_MEETING).associateBy { it.key }
        assertEquals(setOf("title", "startsAt", "endsAt", "timezone", "targetCalendarId"), fields.filterValues { it.required }.keys)
        assertTrue(setOf("location", "attendees", "notes").all(fields::containsKey))
    }

    @Test
    fun `contact review exposes optional values that the Provider persists`() {
        val fields = actionFieldSpecs(ActionType.CREATE_CONTACT).associateBy { it.key }
        assertEquals(setOf("displayName", "contactMethod"), fields.filterValues { it.required }.keys)
        assertTrue(setOf("email", "phone", "company", "jobTitle", "address", "notes").all(fields::containsKey))
    }

    @Test
    fun `meeting review visibly defaults a missing timezone to the device timezone`() {
        assertEquals(
            "Asia/Taipei",
            withDeviceDefaults(ActionType.CREATE_MEETING, mapOf("title" to "Coffee"), "Asia/Taipei")["timezone"],
        )
        assertEquals(
            "Europe/Paris",
            withDeviceDefaults(ActionType.CREATE_MEETING, mapOf("timezone" to "Europe/Paris"), "Asia/Taipei")["timezone"],
        )
    }
}
