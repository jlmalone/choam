package vision.salient.choam.cli

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobalSearchCommandTest {

    @Test
    fun `marks stale results correctly`() {
        // A date more than 30 days ago should be stale
        assertTrue(GlobalSearchCommand.isStale("2025-01-01 00:00:00"))
    }

    @Test
    fun `recent sync is not stale`() {
        // Current time should not be stale
        val now = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        assertFalse(GlobalSearchCommand.isStale(now))
    }

    @Test
    fun `yesterday sync is not stale`() {
        val yesterday = java.time.LocalDateTime.now().minusDays(1)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        assertFalse(GlobalSearchCommand.isStale(yesterday))
    }

    @Test
    fun `29 day old sync is not stale`() {
        val recent = java.time.LocalDateTime.now().minusDays(29)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        assertFalse(GlobalSearchCommand.isStale(recent))
    }

    @Test
    fun `31 day old sync is stale`() {
        val old = java.time.LocalDateTime.now().minusDays(31)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        assertTrue(GlobalSearchCommand.isStale(old))
    }

    @Test
    fun `invalid date string is not stale`() {
        // Graceful handling of bad data
        assertFalse(GlobalSearchCommand.isStale("not-a-date"))
    }

    // ============================
    // NEW TESTS: isStale edge cases
    // ============================

    @Test
    fun `isStale returns true for very old timestamp`() {
        assertTrue(GlobalSearchCommand.isStale("2020-01-01 00:00:00"))
    }

    @Test
    fun `isStale returns false for timestamp exactly 30 days ago`() {
        val exactly30 = java.time.LocalDateTime.now().minusDays(30)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        assertFalse(GlobalSearchCommand.isStale(exactly30))
    }

    @Test
    fun `isStale handles empty string gracefully`() {
        assertFalse(GlobalSearchCommand.isStale(""))
    }

    @Test
    fun `isStale handles partial date string gracefully`() {
        assertFalse(GlobalSearchCommand.isStale("2026-01"))
    }

    @Test
    fun `isStale handles date without time gracefully`() {
        // "yyyy-MM-dd" without " HH:mm:ss" should be handled gracefully
        assertFalse(GlobalSearchCommand.isStale("2025-01-01"))
    }

    @Test
    fun `isStale handles ISO format with T separator gracefully`() {
        // The function expects "yyyy-MM-dd HH:mm:ss" — ISO "T" format should not crash
        assertFalse(GlobalSearchCommand.isStale("2025-01-01T00:00:00"))
    }

    @Test
    fun `isStale handles null-like strings gracefully`() {
        assertFalse(GlobalSearchCommand.isStale("null"))
        assertFalse(GlobalSearchCommand.isStale("unknown"))
    }

    @Test
    fun `isStale 60 days old is stale`() {
        val old = java.time.LocalDateTime.now().minusDays(60)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        assertTrue(GlobalSearchCommand.isStale(old))
    }

    @Test
    fun `isStale future timestamp is not stale`() {
        val future = java.time.LocalDateTime.now().plusDays(30)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        assertFalse(GlobalSearchCommand.isStale(future))
    }

    // ============================
    // globToSqlLike tests
    // ============================

    @Test
    fun `globToSqlLike converts star to percent`() {
        assertEquals("%", GlobalSearchCommand.globToSqlLike("*"))
        assertEquals("%/tv/%", GlobalSearchCommand.globToSqlLike("*/tv/*"))
    }

    @Test
    fun `globToSqlLike converts question mark to underscore`() {
        assertEquals("file_", GlobalSearchCommand.globToSqlLike("file?"))
        assertEquals("_oo", GlobalSearchCommand.globToSqlLike("?oo"))
    }

    @Test
    fun `globToSqlLike escapes SQL LIKE special chars`() {
        assertEquals("100\\%", GlobalSearchCommand.globToSqlLike("100%"))
        assertEquals("file\\_name", GlobalSearchCommand.globToSqlLike("file_name"))
    }

    @Test
    fun `globToSqlLike passthrough for plain strings`() {
        assertEquals("movies", GlobalSearchCommand.globToSqlLike("movies"))
        assertEquals("/Volumes/EXT-4TB/tv/", GlobalSearchCommand.globToSqlLike("/Volumes/EXT-4TB/tv/"))
    }

    @Test
    fun `globToSqlLike double star`() {
        // ** in glob → %% in SQL LIKE (both mean "any chars")
        assertEquals("%%", GlobalSearchCommand.globToSqlLike("**"))
        assertEquals("src/%%/test", GlobalSearchCommand.globToSqlLike("src/**/test"))
    }
}
