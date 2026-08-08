package me.rerere.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEvidenceCompilerTest {
    @Test
    fun `raw blob handle is retained without expanding evidence`() {
        val bundle = SearchEvidenceCompiler.compile(
            SearchResult(items = listOf(SearchResult.SearchResultItem("Title", "https://example.com/a", "ok"))),
            rawContentBlobRef = "sha256:abc",
        )

        assertEquals("sha256:abc", bundle.rawContentBlobRef)
    }

    @Test
    fun `url policy rejects local and credential bearing targets`() {
        assertNull(SearchUrlPolicy.canonicalPublicUrl("http://127.0.0.1/admin"))
        assertNull(SearchUrlPolicy.canonicalPublicUrl("http://user:pass@example.com/"))
        assertNull(SearchUrlPolicy.canonicalPublicUrl("file:///etc/passwd"))
    }

    @Test
    fun `scrape evidence is hard bounded and points to raw blob`() {
        val result = ScrapedResult(
            urls = (1..10).map { index ->
                ScrapedResultUrl("https://example.com/$index", "x".repeat(20_000))
            },
        )

        val bundle = ScrapeEvidenceCompiler.compile(result, "sha256:raw")

        assertTrue(bundle.pages.size <= 8)
        assertTrue(bundle.pages.sumOf { it.content.length } <= 48_000)
        assertTrue(bundle.truncated)
        assertEquals("sha256:raw", bundle.rawContentBlobRef)
    }

    @Test
    fun `ids are deterministic and fragments do not affect canonical evidence`() {
        val input = SearchResult(
            items = listOf(SearchResult.SearchResultItem("Title", "HTTPS://Example.com/a#part", "text")),
        )

        val first = SearchEvidenceCompiler.compile(input)
        val second = SearchEvidenceCompiler.compile(input)

        assertEquals(first, second)
        assertEquals("https://example.com/a", first.items.single().url)
        assertEquals(6, first.items.single().id.length)
    }

    @Test
    fun `large result sets obey item and total budgets`() {
        val input = SearchResult(
            answer = "a".repeat(10_000),
            items = (1..100).map {
                SearchResult.SearchResultItem("Title $it", "https://example.com/$it", "x".repeat(8_000))
            },
            images = (1..30).map { "https://example.com/$it.png" },
        )
        val policy = SearchEvidencePolicy(maxItems = 10, maxImages = 3, maxBundleChars = 8_000)

        val bundle = SearchEvidenceCompiler.compile(input, policy)

        assertTrue(bundle.truncated)
        assertTrue(bundle.items.size <= 10)
        assertEquals(3, bundle.images.size)
        assertTrue(SearchEvidenceTruncationReason.TOTAL_BUDGET in bundle.truncationReasons)
    }

    @Test
    fun `invalid and credential-bearing urls are excluded`() {
        val input = SearchResult(
            items = listOf(
                SearchResult.SearchResultItem("Local", "file:///secret", "x"),
                SearchResult.SearchResultItem("Credential", "https://user:pass@example.com/", "x"),
                SearchResult.SearchResultItem("Good", "https://example.com/", "x"),
            ),
        )

        val bundle = SearchEvidenceCompiler.compile(input)

        assertEquals(1, bundle.items.size)
        assertFalse(bundle.items.single().url.contains("@"))
        assertTrue(SearchEvidenceTruncationReason.INVALID_URL in bundle.truncationReasons)
    }

    @Test
    fun `unicode truncation never leaves a dangling surrogate`() {
        val input = SearchResult(
            items = listOf(SearchResult.SearchResultItem("Emoji", "https://example.com", "ab😀cd")),
        )
        val bundle = SearchEvidenceCompiler.compile(
            input,
            SearchEvidencePolicy(maxSnippetChars = 3, maxBundleChars = 1_024),
        )

        assertEquals("ab", bundle.items.single().text)
    }
}
