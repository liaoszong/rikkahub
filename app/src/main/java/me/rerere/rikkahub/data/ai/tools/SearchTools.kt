package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.content.LocalContentBlobStore
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import me.rerere.search.SearchEvidenceCompiler
import me.rerere.search.ScrapeEvidenceCompiler
import me.rerere.search.SearchUrlPolicy
import me.rerere.pale.content.ContentOwnerKind
import me.rerere.pale.content.ContentOwnerRef
import me.rerere.pale.product.RawPayloadRetention
import java.time.LocalDate

fun createSearchTools(settings: Settings, context: Context, conversationId: String): Set<Tool> {
    val blobStore = LocalContentBlobStore(context)
    val owner = ContentOwnerRef(ContentOwnerKind.CONVERSATION, conversationId, "web_search_raw")
    return buildSet {
        add(
            Tool(
                name = "search_web",
                description = """
                    Search the web for up-to-date or specific information.
                    Use this when the user asks for the latest news, current facts, or needs verification.
                    Generate focused keywords and run multiple searches if needed.
                    Today is ${LocalDate.now().toLocalString(true)}.

                    Response format:
                    - items[].id (short id), title, url, text
                    - images[]: image urls related to the query (may be empty)

                    Citations:
                    - After using results, add `[citation,domain](id)` after the sentence.
                    - Multiple citations are allowed.
                    - If no results are cited, omit citations.

                    Images:
                    - When images help the user understand the answer, embed relevant ones using Markdown: `![](url)`.
                    - Embed 2 to 4 images, and only use urls from `images[]` (never fabricate or alter urls).
                    - Usually place the images at the very beginning of your reply; skip them entirely if none are relevant.

                    Example:
                    The capital of France is Paris. [citation,example.com](abc123)
                    The population is about 2.1 million. [citation,example.com](abc123) [citation,example2.com](def456)
                    """.trimIndent(),
                parameters = {
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    service.parameters(options)
                },
                execute = {
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    val result = service.search(
                        params = it.jsonObject,
                        commonOptions = settings.searchCommonOptions,
                        serviceOptions = options,
                    )
                    val rawResult = result.getOrThrow()
                    val rawJson = JsonInstantPretty.encodeToString(rawResult)
                    val blobRef = if (settings.agentPrivacyPolicy.rawPayloadRetention == RawPayloadRetention.NONE) {
                        null
                    } else {
                        if (settings.agentPrivacyPolicy.rawPayloadRetention == RawPayloadRetention.SHORT_LIVED_PLATFORM_MANAGED) {
                            blobStore.pruneExpired()
                        }
                        blobStore.putJson(owner, rawJson.toByteArray()).blobId
                    }
                    val results = JsonInstantPretty.encodeToJsonElement(
                        SearchEvidenceCompiler.compile(rawResult, rawContentBlobRef = blobRef)
                    )
                    listOf(UIMessagePart.Text(results.toString(), metadata = webEvidenceMetadata()))
                }
            )
        )

        val options = settings.searchServices.getOrElse(
            index = settings.searchServiceSelected,
            defaultValue = { SearchServiceOptions.DEFAULT })
        val service = SearchService.getService(options)
        if (service.scrapingParameters(options) != null) {
            add(
                Tool(
                    name = "scrape_web",
                    description = """
                        Scrape a URL for detailed page content.
                        Use this when the user requests content from a specific page or when search snippets are insufficient.
                        Avoid using it for common questions unless the user asks.
                        """.trimIndent(),
                    parameters = {
                        val options = settings.searchServices.getOrElse(
                            index = settings.searchServiceSelected,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        service.scrapingParameters(options)
                    },
                    execute = { input ->
                        validatePublicUrls(input.jsonObject)
                        val options = settings.searchServices.getOrElse(
                            index = settings.searchServiceSelected,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        val result = service.scrape(
                            params = input.jsonObject,
                            commonOptions = settings.searchCommonOptions,
                            serviceOptions = options,
                        )
                        val rawResult = result.getOrThrow()
                        val rawJson = JsonInstantPretty.encodeToString(rawResult)
                        val blobRef = if (settings.agentPrivacyPolicy.rawPayloadRetention == RawPayloadRetention.NONE) {
                            null
                        } else {
                            if (settings.agentPrivacyPolicy.rawPayloadRetention == RawPayloadRetention.SHORT_LIVED_PLATFORM_MANAGED) {
                                blobStore.pruneExpired()
                            }
                            blobStore.putJson(owner, rawJson.toByteArray()).blobId
                        }
                        val payload = JsonInstantPretty.encodeToJsonElement(
                            ScrapeEvidenceCompiler.compile(rawResult, rawContentBlobRef = blobRef)
                        ).jsonObject
                        listOf(UIMessagePart.Text(payload.toString(), metadata = webEvidenceMetadata()))
                    }
                ))
        }
    }
}

private fun webEvidenceMetadata() = buildJsonObject {
    put("trust", "untrusted_web")
    put("may_authorize_tools", false)
}

private fun validatePublicUrls(value: JsonElement, key: String? = null) {
    when (value) {
        is JsonObject -> value.forEach { (childKey, child) -> validatePublicUrls(child, childKey) }
        is kotlinx.serialization.json.JsonArray -> value.forEach { validatePublicUrls(it, key) }
        is JsonPrimitive -> if (key?.contains("url", ignoreCase = true) == true && value.isString) {
            SearchUrlPolicy.requirePublicUrl(value.content)
        }
    }
}
