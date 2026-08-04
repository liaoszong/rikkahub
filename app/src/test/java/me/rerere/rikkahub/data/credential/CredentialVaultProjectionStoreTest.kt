package me.rerere.rikkahub.data.credential

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialVaultProjectionStoreTest {
    @Test
    fun `seal reuses identical value rotates changes and completes migration journal`() = withStore { store, journal ->
        val address = address(audience = "https://api.example/v1")
        val secret = Json.parseToJsonElement("""{"token":"secret","scope":["a"]}""")

        val first = store.seal(address, secret) as CredentialSettingsSealResult.Stored
        val reused = store.seal(address, secret) as CredentialSettingsSealResult.Stored
        assertEquals(first.reference, reused.reference)
        assertEquals(secret, (store.resolve(first.reference, address) as CredentialSettingsResolveResult.Found).secret)

        val binding = CredentialSettingsBinding(address, first.reference, first.revision, "$.providers.0.apiKey")
        assertEquals(CredentialMigrationStage.ENVELOPE_VERIFIED, journal.incomplete().single().stage)
        store.completePersistence(listOf(binding))
        assertTrue(journal.incomplete().isEmpty())

        val changed = store.seal(address, Json.parseToJsonElement("\"rotated\"")) as CredentialSettingsSealResult.Stored
        assertNotEquals(first.reference, changed.reference)
    }

    @Test
    fun `same slot never forwards credential to a changed audience`() = withStore { store, _ ->
        val originalAddress = address("https://api.example/v1")
        val original = store.seal(originalAddress, Json.parseToJsonElement("\"secret\""))
        assertTrue(original is CredentialSettingsSealResult.Stored)

        val changedAddress = address("https://evil.example/v1")
        val rebound = store.seal(changedAddress, Json.parseToJsonElement("\"secret\""))
        assertTrue(rebound is CredentialSettingsSealResult.Failed)
        assertTrue(
            store.resolve(
                (original as CredentialSettingsSealResult.Stored).reference,
                changedAddress,
            ) is CredentialSettingsResolveResult.Missing,
        )
    }

    @Test
    fun `changed audience can only be rebound with explicit old proof and replacement input`() = withStore { store, _ ->
        val originalAddress = address("https://api.example/v1")
        val original = store.seal(originalAddress, Json.parseToJsonElement("\"old-secret\""))
            as CredentialSettingsSealResult.Stored
        val changedAddress = address("https://api.example/v2")

        val stale = store.rebindAudience(
            changedAddress,
            original.reference,
            original.revision + 1,
            Json.parseToJsonElement("\"replacement-secret\""),
        )
        assertTrue(stale is CredentialSettingsSealResult.Failed)

        val rebound = store.rebindAudience(
            changedAddress,
            original.reference,
            original.revision,
            Json.parseToJsonElement("\"replacement-secret\""),
        ) as CredentialSettingsSealResult.Stored
        assertNotEquals(original.reference, rebound.reference)
        assertEquals(
            Json.parseToJsonElement("\"old-secret\""),
            (store.resolve(original.reference, originalAddress) as CredentialSettingsResolveResult.Found).secret,
        )
        assertEquals(
            Json.parseToJsonElement("\"replacement-secret\""),
            (store.resolve(rebound.reference, changedAddress) as CredentialSettingsResolveResult.Found).secret,
        )
    }

    @Test
    fun `mcp oauth refresh increments revision with a new immutable credential reference`() = withStore { store, _ ->
        val oauthAddress = CredentialSettingsAddress(
            namespace = "settings.mcpServers",
            ownerStableId = "http:server-1",
            fieldSlot = "accesstoken",
            kind = "secret",
            audience = "settings.mcpServers|http:server-1|url=23:https://mcp.example.test",
        )
        val initial = store.seal(oauthAddress, Json.parseToJsonElement("\"access-v1\""))
            as CredentialSettingsSealResult.Stored
        val refreshed = store.seal(oauthAddress, Json.parseToJsonElement("\"access-v2\""))
            as CredentialSettingsSealResult.Stored

        assertNotEquals(initial.reference, refreshed.reference)
        assertEquals(initial.revision + 1, refreshed.revision)
        assertEquals(
            Json.parseToJsonElement("\"access-v2\""),
            (store.resolve(refreshed.reference, oauthAddress) as CredentialSettingsResolveResult.Found).secret,
        )
    }

    @Test
    fun `real projection rejects persisted mcp access reference for another resource`() = withStore { store, _ ->
        val projection = CredentialSettingsProjection(store)
        val original = Json.parseToJsonElement(
            """{"mcpServers":[{"id":"server-1","type":"sse","url":"https://resource-a.example/mcp","commonOptions":{"oauth":{"enabled":true,"accessToken":"access-secret"}}}]}""",
        )
        val persisted = projection.toPersisted(original) as CredentialSettingsProjectionResult.Success
        val reference = persisted.bindings.single().reference
        val moved = Json.parseToJsonElement(
            """{"mcpServers":[{"id":"server-1","type":"sse","url":"https://resource-b.example/mcp","commonOptions":{"oauth":{"enabled":true,"accessToken":"$reference"}}}]}""",
        )

        val restored = projection.toRuntime(moved)

        assertTrue(restored is CredentialSettingsProjectionResult.Failure)
        assertTrue(
            (restored as CredentialSettingsProjectionResult.Failure).issue is
                CredentialSettingsProjectionIssue.Missing,
        )
    }

    @Test
    fun `failed datastore commit restores old active binding and retry remains possible`() =
        withStore { store, journal ->
            val originalAddress = address("https://api.example/v1")
            val original = store.seal(originalAddress, Json.parseToJsonElement("\"old-secret\""))
                as CredentialSettingsSealResult.Stored
            store.completePersistence(
                listOf(CredentialSettingsBinding(originalAddress, original.reference, original.revision, "$.providers.0.apiKey")),
            )
            val changedAddress = address("https://api.example/v2")
            val replacement = Json.parseToJsonElement("\"replacement-secret\"")
            val rebound = store.rebindAudience(
                changedAddress,
                original.reference,
                original.revision,
                replacement,
            ) as CredentialSettingsSealResult.Stored

            val committer = CredentialProjectionCommitter(store)
            val failure = runCatching {
                runBlocking {
                    committer.commit(
                        previousReferencesBySlot = mapOf(originalAddress.slotId().value to original.reference),
                        projectedBindings = listOf(
                            CredentialSettingsBinding(changedAddress, rebound.reference, rebound.revision, "$.providers.0.apiKey"),
                        ),
                    ) { error("injected datastore failure") }
                }
            }.exceptionOrNull()
            assertEquals("injected datastore failure", failure?.message)
            assertTrue(journal.incomplete().isEmpty())

            val restored = store.seal(originalAddress, Json.parseToJsonElement("\"old-secret\""))
                as CredentialSettingsSealResult.Stored
            assertEquals(original.reference, restored.reference)

            val retry = store.rebindAudience(
                changedAddress,
                original.reference,
                original.revision,
                replacement,
            ) as CredentialSettingsSealResult.Stored
            assertNotEquals(original.reference, retry.reference)
        }

    @Test
    fun `startup reconciliation rolls back every partially switched batch member`() =
        withStore { store, journal ->
            val firstOldAddress = address("https://api.example/v1")
            val secondOldAddress = CredentialSettingsAddress(
                namespace = "settings.providers",
                ownerStableId = "openai:provider-1",
                fieldSlot = "header.value",
                kind = "header",
                audience = "https://api.example/v1",
            )
            val firstOld = store.seal(firstOldAddress, Json.parseToJsonElement("\"api-old\""))
                as CredentialSettingsSealResult.Stored
            val secondOld = store.seal(secondOldAddress, Json.parseToJsonElement("\"header-old\""))
                as CredentialSettingsSealResult.Stored
            store.completePersistence(
                listOf(
                    CredentialSettingsBinding(firstOldAddress, firstOld.reference, firstOld.revision, "$.providers.0.apiKey"),
                    CredentialSettingsBinding(secondOldAddress, secondOld.reference, secondOld.revision, "$.providers.0.headers.0.value"),
                ),
            )
            val firstNewAddress = firstOldAddress.copy(audience = "https://api.example/v2")
            val secondNewAddress = secondOldAddress.copy(audience = "https://api.example/v2")
            store.rebindAudience(
                firstNewAddress,
                firstOld.reference,
                firstOld.revision,
                Json.parseToJsonElement("\"api-new\""),
            ) as CredentialSettingsSealResult.Stored
            store.rebindAudience(
                secondNewAddress,
                secondOld.reference,
                secondOld.revision,
                Json.parseToJsonElement("\"header-new\""),
            ) as CredentialSettingsSealResult.Stored

            store.rollbackUncommittedBindings(
                mapOf(
                    firstOldAddress.slotId().value to firstOld.reference,
                    secondOldAddress.slotId().value to secondOld.reference,
                ),
            )

            assertTrue(journal.incomplete().isEmpty())
            assertEquals(
                firstOld.reference,
                (store.seal(firstOldAddress, Json.parseToJsonElement("\"api-old\"")) as CredentialSettingsSealResult.Stored)
                    .reference,
            )
            assertEquals(
                secondOld.reference,
                (store.seal(secondOldAddress, Json.parseToJsonElement("\"header-old\"")) as CredentialSettingsSealResult.Stored)
                    .reference,
            )
        }

    @Test
    fun `failed datastore commit rolls back ordinary rotation and oauth cas`() = withStore { store, journal ->
        val ordinaryAddress = address("https://api.example/v1")
        val oauthAddress = CredentialSettingsAddress(
            namespace = "settings.mcpServers",
            ownerStableId = "http:server-1",
            fieldSlot = "accesstoken",
            kind = "secret",
            audience = "settings.mcpServers|http:server-1|url=23:https://mcp.example.test",
        )
        val ordinaryOld = store.seal(ordinaryAddress, Json.parseToJsonElement("\"ordinary-v1\""))
            as CredentialSettingsSealResult.Stored
        val oauthOld = store.seal(oauthAddress, Json.parseToJsonElement("\"oauth-v1\""))
            as CredentialSettingsSealResult.Stored
        store.completePersistence(
            listOf(
                CredentialSettingsBinding(ordinaryAddress, ordinaryOld.reference, ordinaryOld.revision, "$.ordinary"),
                CredentialSettingsBinding(oauthAddress, oauthOld.reference, oauthOld.revision, "$.oauth"),
            ),
        )

        val ordinaryNew = store.seal(ordinaryAddress, Json.parseToJsonElement("\"ordinary-v2\""))
            as CredentialSettingsSealResult.Stored
        val oauthNew = store.seal(oauthAddress, Json.parseToJsonElement("\"oauth-v2\""))
            as CredentialSettingsSealResult.Stored
        assertNotEquals(ordinaryOld.reference, ordinaryNew.reference)
        assertNotEquals(oauthOld.reference, oauthNew.reference)

        val failure = runCatching {
            runBlocking {
                CredentialProjectionCommitter(store).commit(
                    previousReferencesBySlot = mapOf(
                        ordinaryAddress.slotId().value to ordinaryOld.reference,
                        oauthAddress.slotId().value to oauthOld.reference,
                    ),
                    projectedBindings = listOf(
                        CredentialSettingsBinding(ordinaryAddress, ordinaryNew.reference, ordinaryNew.revision, "$.ordinary"),
                        CredentialSettingsBinding(oauthAddress, oauthNew.reference, oauthNew.revision, "$.oauth"),
                    ),
                ) { error("injected datastore failure") }
            }
        }.exceptionOrNull()

        assertEquals("injected datastore failure", failure?.message)
        assertTrue(journal.incomplete().isEmpty())
        assertEquals(
            ordinaryOld.reference,
            (store.seal(ordinaryAddress, Json.parseToJsonElement("\"ordinary-v1\"")) as CredentialSettingsSealResult.Stored)
                .reference,
        )
        assertEquals(
            oauthOld.reference,
            (store.seal(oauthAddress, Json.parseToJsonElement("\"oauth-v1\"")) as CredentialSettingsSealResult.Stored)
                .reference,
        )
    }

    @Test
    fun `prepare phase crash restores datastore ref without overwriting either envelope`() {
        val root = Files.createTempDirectory("credential-prepare-crash").toFile()
        try {
            val vault = CredentialVault(root, InMemoryWrappingKeyProvider(ByteArray(32) { (it + 7).toByte() }))
            val journal = CredentialMigrationJournal(root)
            val store = CredentialVaultProjectionStore(vault, journal)
            val settingsAddress = address("https://api.example/v1")
            val vaultAddress = CredentialAddress(
                slotId = settingsAddress.slotId(),
                namespace = settingsAddress.namespace,
                ownerStableId = settingsAddress.ownerStableId,
                fieldSlot = settingsAddress.fieldSlot,
                kind = settingsAddress.kind,
                audience = settingsAddress.audience,
            )
            val old = store.seal(settingsAddress, Json.parseToJsonElement("\"old\""))
                as CredentialSettingsSealResult.Stored
            store.completePersistence(
                listOf(CredentialSettingsBinding(settingsAddress, old.reference, old.revision, "$.secret")),
            )

            val newRef = CredentialRefId.new()
            journal.prepare("settings-v4:${settingsAddress.slotId().value}:${newRef.value}", settingsAddress.slotId(), newRef)
            val switched = vault.rotate(
                vaultAddress,
                CredentialExpectation(CredentialRefId.parseReference(old.reference), old.revision),
                "new".toByteArray(),
                newRef,
            ) as CredentialWriteResult.Written
            assertEquals(newRef, switched.refId)

            store.rollbackUncommittedBindings(mapOf(settingsAddress.slotId().value to old.reference))

            assertTrue(journal.incomplete().isEmpty())
            val active = vault.resolveActive(settingsAddress.slotId(), settingsAddress.audience) as CredentialReadResult.Found
            assertEquals(CredentialRefId.parseReference(old.reference), active.value.refId)
            assertEquals("\"old\"", String(active.value.secret))
            val orphan = vault.resolve(newRef) as CredentialReadResult.Found
            assertEquals("new", String(orphan.value.secret))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun address(audience: String) = CredentialSettingsAddress(
        namespace = "settings.providers",
        ownerStableId = "openai:provider-1",
        fieldSlot = "apikey",
        kind = "secret",
        audience = audience,
    )

    private fun withStore(block: (CredentialVaultProjectionStore, CredentialMigrationJournal) -> Unit) {
        val root = Files.createTempDirectory("credential-projection-store").toFile()
        try {
            val vault = CredentialVault(root, InMemoryWrappingKeyProvider(ByteArray(32) { (it + 7).toByte() }))
            val journal = CredentialMigrationJournal(root)
            block(CredentialVaultProjectionStore(vault, journal), journal)
        } finally {
            root.deleteRecursively()
        }
    }
}
