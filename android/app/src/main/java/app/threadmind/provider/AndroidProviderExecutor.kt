package app.threadmind.provider

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import android.provider.ContactsContract
import app.threadmind.domain.ActionType
import app.threadmind.domain.ConfirmedActionSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import javax.inject.Inject

class AndroidProviderExecutor @Inject constructor(
    @ApplicationContext context: Context,
) : ProviderExecutor {
    private val resolver: ContentResolver = context.contentResolver

    override suspend fun execute(snapshot: ConfirmedActionSnapshot): ProviderResult = withContext(Dispatchers.IO) {
        runCatching {
            when (snapshot.type) {
                ActionType.CREATE_MEETING -> createMeeting(snapshot)
                ActionType.CREATE_CONTACT -> createContact(snapshot)
                ActionType.UPDATE_CONTACT -> updateContact(snapshot)
            }
        }.getOrElse { ProviderResult.Failed("provider_error", it.message ?: "Android Provider failed") }
    }

    private fun createMeeting(snapshot: ConfirmedActionSnapshot): ProviderResult {
        val marker = "threadmind://action/${snapshot.actionCardId}/${snapshot.version}"
        resolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.CUSTOM_APP_URI} = ?",
            arrayOf(marker),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return ProviderResult.Succeeded(cursor.getLong(0).toString())
        }
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, snapshot.fields.getValue("targetCalendarId").toLong())
            put(CalendarContract.Events.TITLE, snapshot.fields.getValue("title"))
            put(CalendarContract.Events.DTSTART, OffsetDateTime.parse(snapshot.fields.getValue("startsAt")).toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, OffsetDateTime.parse(snapshot.fields.getValue("endsAt")).toInstant().toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, snapshot.fields.getValue("timezone"))
            put(CalendarContract.Events.DESCRIPTION, snapshot.fields["notes"])
            put(CalendarContract.Events.EVENT_LOCATION, snapshot.fields["location"])
            put(CalendarContract.Events.CUSTOM_APP_PACKAGE, "app.threadmind")
            put(CalendarContract.Events.CUSTOM_APP_URI, marker)
        }
        val uri = requireNotNull(resolver.insert(CalendarContract.Events.CONTENT_URI, values))
        return ProviderResult.Succeeded(ContentUris.parseId(uri).toString())
    }

    private fun createContact(snapshot: ConfirmedActionSnapshot): ProviderResult {
        val marker = "ThreadMind:${snapshot.idempotencyKey}"
        existingContactId(marker)?.let { return ProviderResult.Succeeded(it) }
        val method = snapshot.fields.getValue("contactMethod")
        val operations = arrayListOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, snapshot.fields["accountType"])
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, snapshot.targetAccountId)
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, snapshot.fields.getValue("displayName"))
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, if (method.contains("@")) ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE else ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.Data.DATA1, method)
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Note.NOTE, marker)
                .build(),
        )
        resolver.applyBatch(ContactsContract.AUTHORITY, operations)
        return ProviderResult.Succeeded(checkNotNull(existingContactId(marker)) { "Created contact marker was not readable" })
    }

    private fun existingContactId(marker: String): String? {
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID),
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Note.NOTE} = ?",
            arrayOf(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, marker),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0).toString()
        }
        return null
    }

    private fun updateContact(snapshot: ConfirmedActionSnapshot): ProviderResult {
        val targetId = snapshot.fields.getValue("targetContactId")
        val dataId = snapshot.fields["targetDataId"] ?: error("A unique contact data row is required")
        val newValue = snapshot.fields["newValue"] ?: error("A reviewed field change is required")
        val values = ContentValues().apply { put(ContactsContract.Data.DATA1, newValue) }
        val count = resolver.update(
            ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, dataId.toLong()),
            values,
            "${ContactsContract.Data.CONTACT_ID} = ?",
            arrayOf(targetId),
        )
        check(count == 1) { "Expected one uniquely selected contact field" }
        return ProviderResult.Succeeded(targetId)
    }
}
