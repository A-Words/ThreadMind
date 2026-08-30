package app.threadmind.network

import app.threadmind.domain.ActionStatus
import app.threadmind.domain.ActionType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionCardResponseTest {
    @Test fun `maps server review metadata and confirmed snapshot into domain card`() {
        val response = Json { ignoreUnknownKeys = true }.decodeFromString<ActionCardResponse>(
            """
            {
              "id":"card-1",
              "accountId":"account-1",
              "submissionId":"submission-1",
              "type":"create_contact",
              "version":2,
              "fields":{"displayName":"陈先生","contactMethod":"chen@example.com","targetContactAccountId":"local"},
              "evidence":[{"sourceId":"submission-1","messageId":"m1","excerpt":"chen@example.com","confidence":0.99}],
              "fieldConfidence":{"displayName":0.72,"contactMethod":0.99,"targetContactAccountId":1.0},
              "validationIssues":[],
              "targetAccountId":"local",
              "status":"confirmed",
              "blockers":[],
              "confirmedSnapshot":{
                "actionCardId":"card-1",
                "accountId":"account-1",
                "type":"create_contact",
                "version":2,
                "fields":{"displayName":"陈先生","contactMethod":"chen@example.com","targetContactAccountId":"local"},
                "targetAccountId":"local",
                "evidence":[{"sourceId":"submission-1","messageId":"m1","excerpt":"chen@example.com","confidence":0.99}],
                "idempotencyKey":"stable-key"
              }
            }
            """.trimIndent(),
        )

        val card = response.toDomain()

        assertEquals(ActionType.CREATE_CONTACT, card.type)
        assertEquals(ActionStatus.CONFIRMED, card.status)
        assertEquals("陈先生", card.fields["displayName"])
        assertEquals(0.72, card.fieldConfidence["displayName"])
        assertEquals("stable-key", card.confirmedSnapshot?.idempotencyKey)
    }
}
