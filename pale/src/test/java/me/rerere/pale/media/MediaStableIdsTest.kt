package me.rerere.pale.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaStableIdsTest {
    @Test
    fun digestIdsAreCanonicalAndUnknownDigestStaysUnknown() {
        val upper = "AB".repeat(32)

        assertEquals("sha256:${upper.lowercase()}", MediaStableIds.blobIdForSha256(upper))
        assertNull(MediaStableIds.blobIdForSha256(null))
        assertNull(MediaStableIds.blobIdForSha256("not-a-digest"))
    }

    @Test
    fun derivedIdsAreReplayStableAndNamespaceSeparated() {
        val first = MediaStableIds.derived("media-replica", "file-1")

        assertEquals(first, MediaStableIds.derived("media-replica", "file-1"))
        assertNotEquals(first, MediaStableIds.derived("media-relation", "file-1"))
    }
}
