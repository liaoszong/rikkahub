package me.rerere.rikkahub.data.db.conversation

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationV2CanonicalTest {
    @Test
    fun canonicalJsonSortsObjectKeysButPreservesArrayOrder() {
        val left = Json.parseToJsonElement("""{"b":[2,1],"a":{"y":true,"x":null}}""")
        val same = Json.parseToJsonElement("""{"a":{"x":null,"y":true},"b":[2,1]}""")
        val reorderedArray = Json.parseToJsonElement("""{"a":{"x":null,"y":true},"b":[1,2]}""")

        assertEquals(left.toCanonicalJson(), same.toCanonicalJson())
        assertEquals(sha256Hex(left.toCanonicalJson()), sha256Hex(same.toCanonicalJson()))
        assertNotEquals(left.toCanonicalJson(), reorderedArray.toCanonicalJson())
    }

    @Test
    fun stablePartIdentityUsesOnlyDurableScopeComponents() {
        val base = stableConversationPartId("conversation-a", "message-a", 0)
        val variants = setOf(
            base,
            stableConversationPartId("conversation-b", "message-a", 0),
            stableConversationPartId("conversation-a", "message-b", 0),
            stableConversationPartId("conversation-a", "message-a", 1),
        )

        assertEquals(4, variants.size)
        assertEquals(base, stableConversationPartId("conversation-a", "message-a", 0))
        assertTrue(runCatching { java.util.UUID.fromString(base) }.isSuccess)
    }
}
