package me.rerere.rikkahub.data.credential

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialSettingsProjectionTest {
    private val json = Json

    @Test
    fun `legacy plaintext is sealed and reference resolves to runtime memory projection`() {
        val store = FakeStore()
        val projection = CredentialSettingsProjection(store)
        val legacy = parse(
            """{"providers":[{"id":"provider-1","type":"openai","baseUrl":"https://api.example/v1","apiKey":"sk-live","name":"Mine"}]}""",
        )

        val persisted = projection.toPersisted(legacy).success()
        val reference = persisted.settings
            .jsonObject["providers"]!!.jsonArray[0].jsonObject["apiKey"]!!.jsonPrimitive.content

        assertTrue(CredentialRefId.isReference(reference))
        assertFalse(persisted.settings.toString().contains("sk-live"))
        assertEquals("settings.providers", persisted.bindings.single().address.namespace)
        assertEquals("openai:provider-1", persisted.bindings.single().address.ownerStableId)
        assertEquals("apikey", persisted.bindings.single().address.fieldSlot)

        val runtime = projection.toRuntime(persisted.settings).success()
        assertEquals(
            "sk-live",
            runtime.settings.jsonObject["providers"]!!.jsonArray[0].jsonObject["apiKey"]!!.jsonPrimitive.content,
        )
        assertEquals(reference, runtime.bindings.single().reference)
    }

    @Test
    fun `stable ids keep custom header and body slots unchanged after reorder`() {
        val store = FakeStore()
        val projection = CredentialSettingsProjection(store)
        val first = providerWithCustomEntries(order = listOf("header-1", "body-1"))
        val second = providerWithCustomEntries(order = listOf("body-1", "header-1"))

        val firstResult = projection.toPersisted(first).success()
        val secondResult = projection.toPersisted(second).success()
        val firstSlots = firstResult.bindings.associate { it.address.ownerStableId to it.address.slotId() }
        val secondSlots = secondResult.bindings.associate { it.address.ownerStableId to it.address.slotId() }

        assertEquals(firstSlots, secondSlots)
        assertEquals(firstResult.bindings.map { it.reference }.toSet(), secondResult.bindings.map { it.reference }.toSet())
        assertEquals(setOf("header-1", "body-1"), firstSlots.keys)
    }

    @Test
    fun `semantic headers bodies and mcp pair protect only secret values`() {
        val store = FakeStore()
        val projection = CredentialSettingsProjection(store)
        val source = parse(
            """
            {
              "providers":[{
                "id":"provider-1","type":"openai","baseUrl":"https://api.example",
                "customHeaders":[
                  {"id":"header-secret","name":"Authorization","value":"Bearer secret"},
                  {"id":"header-safe","name":"Content-Type","value":"application/json"}
                ],
                "customBodies":[
                  {"id":"body-secret","key":"organization_key","value":"secret-org"},
                  {"id":"body-safe","key":"temperature","value":"0.7"}
                ]
              }],
              "mcpServers":[{
                "id":"mcp-1","type":"sse","url":"https://mcp.example",
                "commonOptions":{"headers":[{"id":"mcp-header-1","first":"X-Api-Key","second":"mcp-secret"}]}
              }]
            }
            """.trimIndent(),
        )

        val result = projection.toPersisted(source).success()
        val serialized = result.settings.toString()

        assertFalse(serialized.contains("Bearer secret"))
        assertFalse(serialized.contains("secret-org"))
        assertFalse(serialized.contains("mcp-secret"))
        assertTrue(serialized.contains("application/json"))
        assertTrue(serialized.contains("0.7"))
        assertEquals(setOf("header-secret", "body-secret", "mcp-header-1"), result.bindings.map {
            it.address.ownerStableId
        }.toSet())
    }

    @Test
    fun `structured custom body secret remains structured after vault round trip`() {
        val store = FakeStore()
        val projection = CredentialSettingsProjection(store)
        val source = parse(
            """{"providers":[{"id":"provider-1","customBodies":[{"id":"body-auth","key":"auth","value":{"token":"nested-secret","scope":["a","b"]}}]}]}""",
        )

        val persisted = projection.toPersisted(source).success()
        assertFalse(persisted.settings.toString().contains("nested-secret"))

        val runtime = projection.toRuntime(persisted.settings).success()
        assertEquals(source, runtime.settings)
    }

    @Test
    fun `non sensitive values and blank secrets are preserved byte for byte`() {
        val store = FakeStore()
        val projection = CredentialSettingsProjection(store)
        val source = parse(
            """{"webDavConfig":{"url":"https://dav.example","username":"","password":"","path":"archive"},"launchCount":7,"themeId":"warm"}""",
        )

        val result = projection.toPersisted(source).success()

        assertEquals(source, result.settings)
        assertTrue(result.bindings.isEmpty())
        assertTrue(store.sealed.isEmpty())
    }

    @Test
    fun `webdav username is treated as credential while endpoint remains visible`() {
        val store = FakeStore()
        val projection = CredentialSettingsProjection(store)
        val source = parse(
            """{"webDavConfig":{"url":"https://dav.example","username":"alice","password":"pw","path":"archive"}}""",
        )

        val result = projection.toPersisted(source).success()
        val config = result.settings.jsonObject["webDavConfig"]!!.jsonObject

        assertTrue(CredentialRefId.isReference(config["username"]!!.jsonPrimitive.content))
        assertTrue(CredentialRefId.isReference(config["password"]!!.jsonPrimitive.content))
        assertEquals("https://dav.example", config["url"]!!.jsonPrimitive.content)
        assertEquals(setOf("username", "password"), result.bindings.map { it.address.fieldSlot }.toSet())
    }

    @Test
    fun `searxng basic auth pair is sealed and restored while endpoint remains visible`() {
        val store = FakeStore()
        val projection = CredentialSettingsProjection(store)
        val source = parse(
            """{"searchServices":[{"type":"searxng","id":"search-1","url":"https://search.example","engines":"google","username":"alice","password":"pw"}]}""",
        )

        val persisted = projection.toPersisted(source).success()
        val service = persisted.settings.jsonObject["searchServices"]!!.jsonArray[0].jsonObject

        assertTrue(CredentialRefId.isReference(service["username"]!!.jsonPrimitive.content))
        assertTrue(CredentialRefId.isReference(service["password"]!!.jsonPrimitive.content))
        assertEquals("https://search.example", service["url"]!!.jsonPrimitive.content)
        assertFalse(persisted.settings.toString().contains("alice"))
        assertFalse(persisted.settings.toString().contains("\"pw\""))
        assertEquals(setOf("username", "password"), persisted.bindings.map { it.address.fieldSlot }.toSet())

        val runtime = projection.toRuntime(persisted.settings).success()
            .settings.jsonObject["searchServices"]!!.jsonArray[0].jsonObject
        assertEquals("alice", runtime["username"]!!.jsonPrimitive.content)
        assertEquals("pw", runtime["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun `mcp oauth audiences bind access token to resource and refresh credentials to token endpoint`() {
        val projection = CredentialSettingsProjection(FakeStore())
        fun oauth(serverUrl: String, tokenEndpoint: String) = parse(
            """{"mcpServers":[{"id":"mcp-1","type":"sse","url":"$serverUrl","commonOptions":{"oauth":{"enabled":true,"tokenEndpoint":"$tokenEndpoint","clientSecret":"client-secret","accessToken":"access-token","refreshToken":"refresh-token"}}}]}""",
        )

        val first = projection.toPersisted(oauth("https://resource-a.example/mcp", "https://auth.example/token")).success()
        val resourceChanged = projection.toPersisted(
            oauth("https://resource-b.example/mcp", "https://auth.example/token"),
        ).success()
        val tokenEndpointChanged = projection.toPersisted(
            oauth("https://resource-b.example/mcp", "https://auth-2.example/token"),
        ).success()

        val firstAudience = first.bindings.associate { it.address.fieldSlot to it.address.audience }
        val resourceChangedAudience = resourceChanged.bindings.associate { it.address.fieldSlot to it.address.audience }
        val endpointChangedAudience = tokenEndpointChanged.bindings.associate { it.address.fieldSlot to it.address.audience }

        assertFalse(firstAudience.getValue("accesstoken") == resourceChangedAudience.getValue("accesstoken"))
        assertEquals(firstAudience.getValue("refreshtoken"), resourceChangedAudience.getValue("refreshtoken"))
        assertEquals(firstAudience.getValue("clientsecret"), resourceChangedAudience.getValue("clientsecret"))
        assertEquals(resourceChangedAudience.getValue("accesstoken"), endpointChangedAudience.getValue("accesstoken"))
        assertFalse(resourceChangedAudience.getValue("refreshtoken") == endpointChangedAudience.getValue("refreshtoken"))
        assertFalse(resourceChangedAudience.getValue("clientsecret") == endpointChangedAudience.getValue("clientsecret"))
    }

    @Test
    fun `mcp access token reference is rejected after resource url changes`() {
        val store = FakeStore()
        val projection = CredentialSettingsProjection(store)
        val original = parse(
            """{"mcpServers":[{"id":"mcp-1","type":"sse","url":"https://resource-a.example/mcp","commonOptions":{"oauth":{"enabled":true,"accessToken":"access-token"}}}]}""",
        )
        val persisted = projection.toPersisted(original).success()
        val reference = persisted.bindings.single { it.address.fieldSlot == "accesstoken" }.reference
        val moved = parse(
            """{"mcpServers":[{"id":"mcp-1","type":"sse","url":"https://resource-b.example/mcp","commonOptions":{"oauth":{"enabled":true,"accessToken":"$reference"}}}]}""",
        )

        val result = projection.toRuntime(moved)

        assertTrue(result.failure().issue is CredentialSettingsProjectionIssue.Missing)
    }

    @Test
    fun `locked and missing references are explicit failures`() {
        val lockedRef = CredentialRefId.new().referenceString()
        val missingRef = CredentialRefId.new().referenceString()
        val store = FakeStore().apply {
            reads[lockedRef] = CredentialSettingsResolveResult.Locked("keystore invalidated")
            reads[missingRef] = CredentialSettingsResolveResult.Missing
        }
        val projection = CredentialSettingsProjection(store)

        val locked = projection.toRuntime(providerWithApiKey(lockedRef))
        val missing = projection.toRuntime(providerWithApiKey(missingRef))

        assertTrue(locked.failure().issue is CredentialSettingsProjectionIssue.Locked)
        assertTrue(missing.failure().issue is CredentialSettingsProjectionIssue.Missing)
    }

    @Test
    fun `persist reports locked vault instead of retaining plaintext`() {
        val store = FakeStore().apply { sealFailure = CredentialSettingsSealResult.Locked("device locked") }
        val result = CredentialSettingsProjection(store).toPersisted(providerWithApiKey("must-not-persist"))

        assertTrue(result.failure().issue is CredentialSettingsProjectionIssue.Locked)
    }

    @Test
    fun `semantic secret without stable entry id is rejected`() {
        val source = parse(
            """{"providers":[{"id":"provider-1","type":"openai","customHeaders":[{"name":"Authorization","value":"secret"}]}]}""",
        )

        val result = CredentialSettingsProjection(FakeStore()).toPersisted(source)

        assertTrue(result.failure().issue is CredentialSettingsProjectionIssue.UnstableOwner)
    }

    private fun providerWithCustomEntries(order: List<String>): JsonElement {
        val entries = mapOf(
            "header-1" to """{"id":"header-1","name":"Authorization","value":"Bearer one"}""",
            "body-1" to """{"id":"body-1","key":"tenant_secret","value":"tenant one"}""",
        )
        val customHeaders = order.filter { it.startsWith("header") }.joinToString(",") { entries.getValue(it) }
        val customBodies = order.filter { it.startsWith("body") }.joinToString(",") { entries.getValue(it) }
        // Reverse the surrounding arrays/fields too; neither is allowed to affect a credential address.
        return parse(
            """{"providers":[{"id":"provider-1","type":"openai","baseUrl":"https://api.example","customBodies":[$customBodies],"customHeaders":[$customHeaders]}]}""",
        )
    }

    private fun providerWithApiKey(value: String): JsonElement = parse(
        """{"providers":[{"id":"provider-1","type":"openai","baseUrl":"https://api.example","apiKey":"$value"}]}""",
    )

    private fun parse(value: String): JsonElement = json.parseToJsonElement(value)

    private fun CredentialSettingsProjectionResult.success() =
        (this as? CredentialSettingsProjectionResult.Success)
            ?: throw AssertionError("Expected projection success, got $this")

    private fun CredentialSettingsProjectionResult.failure() =
        (this as? CredentialSettingsProjectionResult.Failure)
            ?: throw AssertionError("Expected projection failure, got $this")

    private class FakeStore : CredentialSettingsProjectionStore {
        val sealed = linkedMapOf<CredentialSlotId, JsonElement>()
        val references = linkedMapOf<CredentialSlotId, String>()
        val addresses = mutableMapOf<String, CredentialSettingsAddress>()
        val reads = mutableMapOf<String, CredentialSettingsResolveResult>()
        var sealFailure: CredentialSettingsSealResult? = null

        override fun seal(address: CredentialSettingsAddress, secret: JsonElement): CredentialSettingsSealResult {
            sealFailure?.let { return it }
            val slot = address.slotId()
            val reference = references.getOrPut(slot) { CredentialRefId.new().referenceString() }
            sealed[slot] = secret
            addresses[reference] = address
            reads[reference] = CredentialSettingsResolveResult.Found(secret, revision = 1)
            return CredentialSettingsSealResult.Stored(reference, revision = 1)
        }

        override fun resolve(
            reference: String,
            address: CredentialSettingsAddress,
        ): CredentialSettingsResolveResult {
            val sealedAddress = addresses[reference]
            if (sealedAddress != null && sealedAddress != address) return CredentialSettingsResolveResult.Missing
            return reads[reference] ?: CredentialSettingsResolveResult.Missing
        }
    }
}
