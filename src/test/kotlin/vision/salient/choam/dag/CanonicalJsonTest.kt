package vision.salient.choam.dag

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CanonicalJsonTest {

    // --- Key sorting ---

    @Test
    fun `object keys are sorted alphabetically`() {
        val json = CanonicalJson.stringify(mapOf("z" to "1", "a" to "2", "m" to "3"))
        assertEquals("""{"a":"2","m":"3","z":"1"}""", json)
    }

    @Test
    fun `nested object keys are also sorted`() {
        val json = CanonicalJson.stringify(mapOf(
            "b" to mapOf("y" to "1", "x" to "2"),
            "a" to "3"
        ))
        assertEquals("""{"a":"3","b":{"x":"2","y":"1"}}""", json)
    }

    @Test
    fun `deeply nested objects sort recursively`() {
        val json = CanonicalJson.stringify(mapOf(
            "c" to mapOf("z" to mapOf("b" to "1", "a" to "2")),
            "a" to "3"
        ))
        assertEquals("""{"a":"3","c":{"z":{"a":"2","b":"1"}}}""", json)
    }

    // --- No whitespace ---

    @Test
    fun `output is compact with no whitespace`() {
        val json = CanonicalJson.stringify(mapOf("key" to "value", "list" to listOf("a", "b")))
        assertTrue(!json.contains(" "))
        assertTrue(!json.contains("\n"))
    }

    // --- Null handling ---

    @Test
    fun `null values are omitted from objects`() {
        val json = CanonicalJson.stringify(mapOf("a" to "1", "b" to null, "c" to "3"))
        assertEquals("""{"a":"1","c":"3"}""", json)
    }

    // --- Empty strings preserved ---

    @Test
    fun `empty strings are preserved`() {
        val json = CanonicalJson.stringify(mapOf("a" to "", "b" to "x"))
        assertEquals("""{"a":"","b":"x"}""", json)
    }

    // --- Number formatting ---

    @Test
    fun `integers have no trailing zeros`() {
        assertEquals("42", CanonicalJson.stringify(42))
        assertEquals("0", CanonicalJson.stringify(0))
        assertEquals("-1", CanonicalJson.stringify(-1))
    }

    @Test
    fun `longs render without L suffix`() {
        assertEquals("9999999999", CanonicalJson.stringify(9999999999L))
    }

    @Test
    fun `doubles that are whole numbers render as integers`() {
        assertEquals("42", CanonicalJson.stringify(42.0))
        assertEquals("0", CanonicalJson.stringify(0.0))
    }

    // --- String escaping ---

    @Test
    fun `strings are quoted`() {
        assertEquals("\"hello\"", CanonicalJson.stringify("hello"))
    }

    @Test
    fun `special characters are escaped`() {
        val json = CanonicalJson.stringify("line1\nline2\ttab\"quote\\backslash")
        assertEquals("\"line1\\nline2\\ttab\\\"quote\\\\backslash\"", json)
    }

    // --- Lists ---

    @Test
    fun `lists render as JSON arrays`() {
        assertEquals("[\"a\",\"b\",\"c\"]", CanonicalJson.stringify(listOf("a", "b", "c")))
    }

    @Test
    fun `empty list renders as empty array`() {
        assertEquals("[]", CanonicalJson.stringify(emptyList<String>()))
    }

    @Test
    fun `nested lists work`() {
        val json = CanonicalJson.stringify(listOf(listOf("a", "b"), listOf("c")))
        assertEquals("[[\"a\",\"b\"],[\"c\"]]", json)
    }

    // --- Booleans ---

    @Test
    fun `booleans render as true and false`() {
        assertEquals("true", CanonicalJson.stringify(true))
        assertEquals("false", CanonicalJson.stringify(false))
    }

    // --- DagEvent hashing ---

    @Test
    fun `hashEvent produces sha256 prefixed ID`() {
        val event = createTestEvent()
        val hash = CanonicalJson.hashEvent(event)
        assertTrue(hash.startsWith("sha256:"))
        assertEquals(71, hash.length) // "sha256:" (7) + 64 hex chars
    }

    @Test
    fun `same event always produces same hash`() {
        val event = createTestEvent()
        val hash1 = CanonicalJson.hashEvent(event)
        val hash2 = CanonicalJson.hashEvent(event)
        assertEquals(hash1, hash2)
    }

    @Test
    fun `different payload produces different hash`() {
        val event1 = createTestEvent(payload = mapOf("name" to "alice"))
        val event2 = createTestEvent(payload = mapOf("name" to "bob"))
        assertNotEquals(CanonicalJson.hashEvent(event1), CanonicalJson.hashEvent(event2))
    }

    @Test
    fun `id and signature fields are excluded from hash`() {
        val event1 = createTestEvent().copy(id = "sha256:aaa", signature = "sig1")
        val event2 = createTestEvent().copy(id = "sha256:bbb", signature = "sig2")
        // Same content, different id/sig → same hash
        assertEquals(CanonicalJson.hashEvent(event1), CanonicalJson.hashEvent(event2))
    }

    @Test
    fun `hash is lowercase hex`() {
        val hash = CanonicalJson.hashEvent(createTestEvent())
        val hexPart = hash.removePrefix("sha256:")
        assertTrue(hexPart.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // --- sha256 utility ---

    @Test
    fun `sha256 of known input matches expected`() {
        val hash = CanonicalJson.sha256("hello")
        assertTrue(hash.startsWith("sha256:"))
        // SHA-256 of "hello" is well-known
        assertEquals("sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash)
    }

    // --- Helpers ---

    private fun createTestEvent(
        type: String = "TEST",
        payload: Map<String, String> = mapOf("key" to "value")
    ) = DagEvent(
        id = "",
        version = 1,
        parents = emptyList(),
        timestamp = DagTimestamp(lamport = 1, wall = "2026-03-06T12:00:00.000Z"),
        author = DagAuthor(houseId = "abcd1234abcd1234abcd1234abcd1234", machineId = "test"),
        type = type,
        payload = payload
    )
}
