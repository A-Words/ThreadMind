package app.threadmind.provider

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.telephony.PhoneNumberUtils
import android.provider.CalendarContract
import android.provider.ContactsContract
import app.threadmind.domain.ActionCard
import app.threadmind.domain.ActionType
import app.threadmind.domain.ConfirmedActionSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import javax.inject.Inject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class AndroidProviderExecutor @Inject constructor(
    @ApplicationContext context: Context,
) : ProviderExecutor, ProviderInspector {
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

    override suspend fun inspect(card: ActionCard): ProviderPreflightResult = withContext(Dispatchers.IO) {
        runCatching {
            when (card.type) {
                ActionType.CREATE_MEETING -> inspectMeeting(card)
                ActionType.CREATE_CONTACT -> inspectCreateContact(card)
                ActionType.UPDATE_CONTACT -> inspectUpdateContact(card)
            }
        }.getOrElse {
            ProviderPreflightResult.Blocked(card.id, card.version, it.message ?: "无法读取设备数据")
        }
    }

    override fun updateFields(candidate: ContactCandidate): Map<String, String> = mapOf(
        "targetContactId" to candidate.contactId,
        "changes" to json.encodeToString(candidate.proposedChanges),
    )

    private fun inspectMeeting(card: ActionCard): ProviderPreflightResult {
        val start = OffsetDateTime.parse(card.fields.getValue("startsAt")).toInstant().toEpochMilli()
        val end = OffsetDateTime.parse(card.fields.getValue("endsAt")).toInstant().toEpochMilli()
        require(end > start) { "会议结束时间必须晚于开始时间" }
        meetingAttendees(card.fields["attendees"])
        val calendarId = card.fields.getValue("targetCalendarId")
        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, start)
        ContentUris.appendId(uriBuilder, end)
        val conflicts = mutableListOf<MeetingConflict>()
        resolver.query(
            uriBuilder.build(),
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
            ),
            "${CalendarContract.Instances.CALENDAR_ID} = ? AND ${CalendarContract.Instances.BEGIN} < ? AND ${CalendarContract.Instances.END} > ?",
            arrayOf(calendarId, end.toString(), start.toString()),
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                conflicts += MeetingConflict(
                    eventId = cursor.getLong(0).toString(),
                    title = cursor.getString(1).orEmpty().ifBlank { "未命名会议" },
                    startsAtEpochMillis = cursor.getLong(2),
                    endsAtEpochMillis = cursor.getLong(3),
                )
            }
        }
        return if (conflicts.isEmpty()) ProviderPreflightResult.Clear(card.id, card.version)
        else ProviderPreflightResult.MeetingConflicts(card.id, card.version, conflicts)
    }

    private fun inspectCreateContact(card: ActionCard): ProviderPreflightResult {
        val displayName = card.fields.getValue("displayName")
        val method = card.fields.getValue("contactMethod")
        val matches = contactMatches(method, displayName)
            .mapNotNull { (contactId, matchedBy) -> loadCandidate(contactId, card.targetAccountId, matchedBy, card.fields) }
        return if (matches.isEmpty()) ProviderPreflightResult.Clear(card.id, card.version)
        else ProviderPreflightResult.ContactCandidates(card.id, card.version, matches, createContact = true)
    }

    private fun inspectUpdateContact(card: ActionCard): ProviderPreflightResult {
        val targetId = card.fields["targetContactId"]
        val matches = if (!targetId.isNullOrBlank()) {
            listOfNotNull(loadCandidate(targetId, card.targetAccountId, "已选目标", emptyMap()))
        } else {
            val query = card.fields["contactQuery"] ?: card.fields["displayName"] ?: card.fields["contactMethod"]
                ?: return ProviderPreflightResult.Blocked(card.id, card.version, "更新联系人前必须提供联系人查询条件")
            contactMatches(card.fields["contactMethod"], query)
                .mapNotNull { (contactId, matchedBy) -> loadCandidate(contactId, card.targetAccountId, matchedBy, card.fields) }
        }
        if (matches.isEmpty()) return ProviderPreflightResult.Blocked(card.id, card.version, "没有找到可唯一更新的联系人")
        if (matches.size > 1 || targetId.isNullOrBlank()) {
            return ProviderPreflightResult.ContactCandidates(card.id, card.version, matches, createContact = false)
        }
        val candidate = matches.single()
        val encoded = card.fields["changes"] ?: return ProviderPreflightResult.Blocked(card.id, card.version, "更新联系人必须包含字段差异")
        val changes = json.decodeFromString<List<ContactFieldChange>>(encoded)
        if (changes.isEmpty()) return ProviderPreflightResult.Blocked(card.id, card.version, "没有需要写入的联系人变化")
        val current = loadFieldRows(candidate.rawContactId)
        for (change in changes) {
            require(change.rawContactId == candidate.rawContactId) { "联系人目标已变化，请重新选择" }
            if (change.dataId != null) {
                val row = current.firstOrNull { it.dataId == change.dataId && it.field == change.field }
                    ?: return ProviderPreflightResult.Blocked(card.id, card.version, "联系人字段已变化，请重新检查")
                if (row.value != change.oldValue) {
                    return ProviderPreflightResult.Blocked(card.id, card.version, "联系人旧值已变化，请重新检查")
                }
            }
        }
        return ProviderPreflightResult.ContactOverwrites(card.id, card.version, candidate, changes)
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
        val operations = arrayListOf(
            ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                .withValues(values)
                .build(),
        )
        meetingAttendees(snapshot.fields["attendees"]).forEach { address ->
            operations += ContentProviderOperation.newInsert(CalendarContract.Attendees.CONTENT_URI)
                .withValueBackReference(CalendarContract.Attendees.EVENT_ID, 0)
                .withValue(CalendarContract.Attendees.ATTENDEE_EMAIL, address)
                .withValue(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED)
                .withValue(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_INVITED)
                .build()
        }
        val results = resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        val eventUri = requireNotNull(results.firstOrNull()?.uri) { "Calendar Provider did not return an event URI" }
        return ProviderResult.Succeeded(ContentUris.parseId(eventUri).toString())
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
        val primaryField = if (method.contains("@")) "email" else "phone"
        val extraMethods = listOf("email", "phone").mapNotNull { field ->
            snapshot.fields[field]?.trim()?.takeIf { it.isNotEmpty() && (field != primaryField || it != method) }?.let { field to it }
        }
        extraMethods.forEach { (field, value) ->
            operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    if (field == "email") ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
                    else ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                )
                .withValue(ContactsContract.Data.DATA1, value)
                .build()
        }
        val company = snapshot.fields["company"]?.trim().orEmpty()
        val jobTitle = snapshot.fields["jobTitle"]?.trim().orEmpty()
        if (company.isNotEmpty() || jobTitle.isNotEmpty()) {
            operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, company.ifEmpty { null })
                .withValue(ContactsContract.CommonDataKinds.Organization.TITLE, jobTitle.ifEmpty { null })
                .build()
        }
        snapshot.fields["address"]?.trim()?.takeIf(String::isNotEmpty)?.let { address ->
            operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, address)
                .build()
        }
        (snapshot.fields["notes"] ?: snapshot.fields["note"])?.trim()?.takeIf(String::isNotEmpty)?.let { note ->
            operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Note.NOTE, note)
                .build()
        }
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
        val changes = json.decodeFromString<List<ContactFieldChange>>(snapshot.fields.getValue("changes"))
        require(changes.isNotEmpty()) { "At least one reviewed contact change is required" }
        val rawIds = rawContacts(targetId).map { it.id }.toSet()
        val currentRows = changes.map(ContactFieldChange::rawContactId).distinct().associateWith(::loadFieldRows)
        val operations = changes.map { change ->
            require(change.rawContactId in rawIds) { "Contact target changed after confirmation" }
            val descriptor = descriptor(change.field)
            if (change.dataId == null) {
                require(currentRows.getValue(change.rawContactId).none { it.field == change.field }) {
                    "Contact field changed after confirmation"
                }
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, change.rawContactId.toLong())
                    .withValue(ContactsContract.Data.MIMETYPE, descriptor.mimeType)
                    .withValue(descriptor.column, change.newValue)
                    .build()
            } else {
                require(currentRows.getValue(change.rawContactId).any {
                    it.dataId == change.dataId && it.field == change.field && it.value == change.oldValue
                }) { "Contact field changed after confirmation" }
                ContentProviderOperation.newUpdate(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, change.dataId.toLong()))
                    .withSelection(
                        "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ? AND ${descriptor.column} = ?",
                        arrayOf(targetId, change.rawContactId, descriptor.mimeType, change.oldValue.orEmpty()),
                    )
                    .withValue(descriptor.column, change.newValue)
                    .withExpectedCount(1)
                    .build()
            }
        }
        resolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(operations))
        return ProviderResult.Succeeded(targetId)
    }

    private fun contactMatches(method: String?, displayName: String): Map<String, String> {
        val matches = linkedMapOf<String, String>()
        if (!method.isNullOrBlank()) {
            if (method.contains("@")) {
                resolver.query(
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Email.CONTACT_ID),
                    "${ContactsContract.CommonDataKinds.Email.ADDRESS} = ? COLLATE NOCASE",
                    arrayOf(method.trim()),
                    null,
                )?.use { cursor -> while (cursor.moveToNext()) matches[cursor.getLong(0).toString()] = "邮箱完全匹配" }
            } else {
                val normalized = PhoneNumberUtils.normalizeNumber(method)
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(normalized))
                resolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) matches[cursor.getLong(0).toString()] = "电话号码匹配"
                }
            }
        }
        val nameUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(displayName.trim()))
        resolver.query(
            nameUri,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1).orEmpty().equals(displayName.trim(), ignoreCase = true)) {
                    matches.putIfAbsent(cursor.getLong(0).toString(), "姓名匹配，需人工确认")
                }
            }
        }
        return matches
    }

    private fun loadCandidate(
        contactId: String,
        preferredAccount: String?,
        matchedBy: String,
        proposedFields: Map<String, String>,
    ): ContactCandidate? {
        val raw = rawContacts(contactId).let { rows ->
            rows.firstOrNull { it.accountName == preferredAccount } ?: rows.firstOrNull()
        } ?: return null
        val displayName = resolver.query(
            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId.toLong()),
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" }.orEmpty()
        val current = loadFieldRows(raw.id)
        val changes = buildProposedContactChanges(raw.id, current, proposedFields)
        return ContactCandidate(contactId, raw.id, displayName, raw.accountName, raw.accountType, matchedBy, changes)
    }

    private fun rawContacts(contactId: String): List<RawContact> {
        val rows = mutableListOf<RawContact>()
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(
                ContactsContract.RawContacts._ID,
                ContactsContract.RawContacts.ACCOUNT_NAME,
                ContactsContract.RawContacts.ACCOUNT_TYPE,
            ),
            "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.DELETED} = 0",
            arrayOf(contactId),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) rows += RawContact(
                id = cursor.getLong(0).toString(),
                accountName = cursor.getString(1),
                accountType = cursor.getString(2),
            )
        }
        return rows
    }

    private fun loadFieldRows(rawContactId: String): List<ContactFieldSnapshot> {
        val rows = mutableListOf<ContactFieldSnapshot>()
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data._ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA4,
            ),
            "${ContactsContract.Data.RAW_CONTACT_ID} = ?",
            arrayOf(rawContactId),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val dataId = cursor.getLong(0).toString()
                val mimeType = cursor.getString(1)
                descriptors.filter { it.mimeType == mimeType }.forEach { descriptor ->
                    val value = cursor.getString(if (descriptor.column == ContactsContract.Data.DATA4) 3 else 2)
                    if (!value.isNullOrBlank()) rows += ContactFieldSnapshot(dataId, descriptor.field, value)
                }
            }
        }
        return rows
    }

    private data class RawContact(val id: String, val accountName: String?, val accountType: String?)
    private data class FieldDescriptor(val field: String, val mimeType: String, val column: String)

    private fun descriptor(field: String) = descriptors.singleOrNull { it.field == field }
        ?: error("Unsupported contact field: $field")

    private fun meetingAttendees(value: String?): List<String> = value.orEmpty()
        .split(',', ';', '\n')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .onEach { require(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(it)) { "参与人必须是可识别的邮箱地址：$it" } }

    private companion object {
        val json = Json { ignoreUnknownKeys = false; explicitNulls = false }
        val descriptors = listOf(
            FieldDescriptor("email", ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Email.ADDRESS),
            FieldDescriptor("phone", ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Phone.NUMBER),
            FieldDescriptor("company", ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Organization.COMPANY),
            FieldDescriptor("jobTitle", ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Organization.TITLE),
            FieldDescriptor("address", ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS),
            FieldDescriptor("note", ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Note.NOTE),
        )
    }
}
