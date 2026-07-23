package vision.salient.choam.dag

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DagEngineTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createEngine(): Triple<DagEngine, DagStore, java.sql.Connection> {
        val (pub, priv) = DagCrypto.generateKeyPair()
        val houseId = DagCrypto.deriveHouseId(pub)
        val store = DagStore(tempDir.resolve("dag.db").toString())
        val conn = store.open()
        val engine = DagEngine(store, houseId, "test-machine", pub, priv)
        return Triple(engine, store, conn)
    }

    // --- Event creation ---

    @Test
    fun `createEvent produces valid sha256 ID`() {
        val (engine, store, conn) = createEngine()
        val event = engine.createEvent(conn, DagEventType.HOUSE_CREATED, mapOf("name" to "test-house"))
        assertTrue(event.id.startsWith("sha256:"))
        assertEquals(71, event.id.length)
        conn.close()
    }

    @Test
    fun `createEvent stores event in store`() {
        val (engine, store, conn) = createEngine()
        val event = engine.createEvent(conn, DagEventType.HOUSE_CREATED, mapOf("name" to "test-house"))
        val retrieved = store.getEvent(conn, event.id)
        assertEquals(event.id, retrieved?.id)
        conn.close()
    }

    @Test
    fun `createEvent produces signed events`() {
        val (engine, _, conn) = createEngine()
        val event = engine.createEvent(conn, DagEventType.HOUSE_CREATED, mapOf("name" to "test"))
        assertTrue(event.signature != null)
        assertEquals(128, event.signature!!.length)
        conn.close()
    }

    @Test
    fun `genesis event has empty parents`() {
        val (engine, _, conn) = createEngine()
        val event = engine.createEvent(conn, DagEventType.HOUSE_CREATED, mapOf("name" to "test"))
        assertTrue(event.parents.isEmpty())
        conn.close()
    }

    @Test
    fun `genesis event includes public key`() {
        val (engine, _, conn) = createEngine()
        val event = engine.createEvent(conn, DagEventType.HOUSE_CREATED, mapOf("name" to "test"))
        assertTrue(event.author.publicKey != null)
        assertEquals(64, event.author.publicKey!!.length)
        conn.close()
    }

    @Test
    fun `non-genesis event has parent pointing to head`() {
        val (engine, store, conn) = createEngine()
        val genesis = engine.createEvent(conn, DagEventType.HOUSE_CREATED, mapOf("name" to "test"))
        val second = engine.createEvent(conn, DagEventType.MACHINE_JOINED, mapOf("name" to "local"))

        assertEquals(listOf(genesis.id), second.parents)
        conn.close()
    }

    @Test
    fun `lamport clock increments`() {
        val (engine, _, conn) = createEngine()
        val e1 = engine.createEvent(conn, DagEventType.HOUSE_CREATED, mapOf("name" to "test"))
        val e2 = engine.createEvent(conn, DagEventType.MACHINE_JOINED, mapOf("name" to "local"))
        val e3 = engine.createEvent(conn, DagEventType.DRIVE_ADDED, mapOf("key" to "ext"))

        assertEquals(1, e1.timestamp.lamport)
        assertEquals(2, e2.timestamp.lamport)
        assertEquals(3, e3.timestamp.lamport)
        conn.close()
    }

    @Test
    fun `non-genesis event does not include public key`() {
        val (engine, _, conn) = createEngine()
        engine.createEvent(conn, DagEventType.HOUSE_CREATED, mapOf("name" to "test"))
        val second = engine.createEvent(conn, DagEventType.MACHINE_JOINED, mapOf("name" to "m1"))
        assertTrue(second.author.publicKey == null)
        conn.close()
    }

    // --- Validation ---

    @Test
    fun `validate accepts valid event`() {
        val (engine, store, conn) = createEngine()
        val event = engine.createEvent(conn, DagEventType.HOUSE_CREATED, mapOf("name" to "test"))
        val result = engine.validate(conn, event)
        assertIs<ValidationResult.Valid>(result)
        conn.close()
    }

    @Test
    fun `validate rejects wrong version`() {
        val (engine, _, conn) = createEngine()
        val event = engine.createEvent(conn, DagEventType.HOUSE_CREATED, mapOf("name" to "test"))
        val tampered = event.copy(version = 2)
        val result = engine.validate(conn, tampered)
        assertIs<ValidationResult.Invalid>(result)
        assertTrue((result as ValidationResult.Invalid).reason.contains("version"))
        conn.close()
    }

    @Test
    fun `validate rejects tampered hash`() {
        val (engine, _, conn) = createEngine()
        val event = engine.createEvent(conn, DagEventType.HOUSE_CREATED, mapOf("name" to "test"))
        val tampered = event.copy(id = "sha256:0000000000000000000000000000000000000000000000000000000000000000")
        val result = engine.validate(conn, tampered)
        assertIs<ValidationResult.Invalid>(result)
        assertTrue((result as ValidationResult.Invalid).reason.contains("mismatch"))
        conn.close()
    }

    @Test
    fun `validate rejects genesis with parents`() {
        val (engine, _, conn) = createEngine()
        val event = engine.createEvent(conn, DagEventType.HOUSE_CREATED, mapOf("name" to "test"))
        // Manually create a bad genesis with parents
        val bad = DagEvent(
            id = "sha256:fake", version = 1,
            parents = listOf("sha256:parent"),
            timestamp = DagTimestamp(1, "2026-01-01T00:00:00.000Z"),
            author = event.author,
            type = DagEventType.HOUSE_CREATED,
            payload = mapOf("name" to "bad")
        )
        val rehashedId = CanonicalJson.hashEvent(bad)
        val result = engine.validate(conn, bad.copy(id = rehashedId))
        assertIs<ValidationResult.Invalid>(result)
        assertTrue((result as ValidationResult.Invalid).reason.contains("empty parents"))
        conn.close()
    }

    @Test
    fun `validate rejects non-genesis with no parents`() {
        val (engine, _, conn) = createEngine()
        engine.createEvent(conn, DagEventType.HOUSE_CREATED, mapOf("name" to "test"))
        val bad = DagEvent(
            id = "", version = 1,
            parents = emptyList(), // Missing parents for non-genesis
            timestamp = DagTimestamp(2, "2026-01-01T00:00:00.000Z"),
            author = DagAuthor("abcd" + "0".repeat(28), "m1"),
            type = DagEventType.MACHINE_JOINED,
            payload = mapOf("name" to "m1")
        )
        val rehashedId = CanonicalJson.hashEvent(bad)
        val result = engine.validate(conn, bad.copy(id = rehashedId))
        assertIs<ValidationResult.Invalid>(result)
        assertTrue((result as ValidationResult.Invalid).reason.contains("parent"))
        conn.close()
    }

    @Test
    fun `validate rejects negative lamport`() {
        val (engine, _, conn) = createEngine()
        engine.createEvent(conn, DagEventType.HOUSE_CREATED, mapOf("name" to "test"))
        val bad = DagEvent(
            id = "", version = 1, parents = emptyList(),
            timestamp = DagTimestamp(-1, "2026-01-01T00:00:00.000Z"),
            author = DagAuthor("abcd" + "0".repeat(28), "m1"),
            type = DagEventType.HOUSE_CREATED,
            payload = mapOf("name" to "bad")
        )
        val rehashedId = CanonicalJson.hashEvent(bad)
        val result = engine.validate(conn, bad.copy(id = rehashedId))
        assertIs<ValidationResult.Invalid>(result)
        conn.close()
    }

    // --- Content addressing ---

    @Test
    fun `recomputed hash matches stored ID`() {
        val (engine, store, conn) = createEngine()
        val event = engine.createEvent(conn, DagEventType.HOUSE_CREATED, mapOf("name" to "test"))
        val recomputed = CanonicalJson.hashEvent(event)
        assertEquals(event.id, recomputed)
        conn.close()
    }
}
