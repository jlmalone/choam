package vision.salient.choam.dag

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import vision.salient.choam.config.*
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StateMaterializerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun setup(): TestContext {
        val (pub, priv) = DagCrypto.generateKeyPair()
        val houseId = DagCrypto.deriveHouseId(pub)
        val store = DagStore(tempDir.resolve("dag.db").toString())
        val conn = store.open()
        val engine = DagEngine(store, houseId, "test-machine", pub, priv)
        val materializer = StateMaterializer(store)
        return TestContext(engine, store, conn, materializer, houseId, pub)
    }

    data class TestContext(
        val engine: DagEngine, val store: DagStore, val conn: java.sql.Connection,
        val materializer: StateMaterializer, val houseId: String, val publicKey: String
    )

    // --- House ---

    @Test
    fun `materialize empty DAG returns null house`() {
        val ctx = setup()
        val state = ctx.materializer.materialize(ctx.conn)
        assertNull(state.house)
        ctx.conn.close()
    }

    @Test
    fun `HOUSE_CREATED sets house identity`() {
        val ctx = setup()
        ctx.engine.createEvent(ctx.conn, DagEventType.HOUSE_CREATED, mapOf(
            "name" to "my-house", "publicKey" to ctx.publicKey, "description" to "Test house"
        ))

        val state = ctx.materializer.materialize(ctx.conn)
        assertNotNull(state.house)
        assertEquals("my-house", state.house!!.name)
        assertEquals(ctx.houseId, state.house!!.houseId)
        ctx.conn.close()
    }

    // --- Machines ---

    @Test
    fun `MACHINE_JOINED adds machine to state`() {
        val ctx = setup()
        ctx.engine.createEvent(ctx.conn, DagEventType.HOUSE_CREATED, mapOf("name" to "house"))
        ctx.engine.createEvent(ctx.conn, DagEventType.MACHINE_JOINED, mapOf(
            "name" to "server-a",
            "hostname" to "server-a.local",
            "type" to "DESKTOP",
            "tailscaleIp" to "100.64.0.1",
            "sshUser" to "user",
            "networkPreference" to "TAILSCALE"
        ))

        val state = ctx.materializer.materialize(ctx.conn)
        assertEquals(1, state.machines.size)
        val machine = state.machines["server-a"]!!
        assertEquals("server-a.local", machine.hostname)
        assertEquals("100.64.0.1", machine.tailscaleIp)
        assertEquals(MachineType.DESKTOP, machine.type)
        assertEquals(NetworkMode.TAILSCALE, machine.networkPreference)
        ctx.conn.close()
    }

    @Test
    fun `MACHINE_UPDATED modifies existing machine`() {
        val ctx = setup()
        ctx.engine.createEvent(ctx.conn, DagEventType.HOUSE_CREATED, mapOf("name" to "house"))
        ctx.engine.createEvent(ctx.conn, DagEventType.MACHINE_JOINED, mapOf(
            "name" to "server-a", "hostname" to "old.local", "type" to "DESKTOP"
        ))
        ctx.engine.createEvent(ctx.conn, DagEventType.MACHINE_UPDATED, mapOf(
            "name" to "server-a", "hostname" to "new.local", "tailscaleIp" to "100.64.0.99"
        ))

        val state = ctx.materializer.materialize(ctx.conn)
        val machine = state.machines["server-a"]!!
        assertEquals("new.local", machine.hostname)
        assertEquals("100.64.0.99", machine.tailscaleIp)
        ctx.conn.close()
    }

    @Test
    fun `MACHINE_LEFT removes machine`() {
        val ctx = setup()
        ctx.engine.createEvent(ctx.conn, DagEventType.HOUSE_CREATED, mapOf("name" to "house"))
        ctx.engine.createEvent(ctx.conn, DagEventType.MACHINE_JOINED, mapOf("name" to "m1", "hostname" to "h1", "type" to "DESKTOP"))
        ctx.engine.createEvent(ctx.conn, DagEventType.MACHINE_JOINED, mapOf("name" to "m2", "hostname" to "h2", "type" to "SERVER"))
        ctx.engine.createEvent(ctx.conn, DagEventType.MACHINE_LEFT, mapOf("name" to "m1"))

        val state = ctx.materializer.materialize(ctx.conn)
        assertEquals(1, state.machines.size)
        assertNull(state.machines["m1"])
        assertNotNull(state.machines["m2"])
        ctx.conn.close()
    }

    // --- Drives ---

    @Test
    fun `DRIVE_ADDED adds drive`() {
        val ctx = setup()
        ctx.engine.createEvent(ctx.conn, DagEventType.HOUSE_CREATED, mapOf("name" to "house"))
        ctx.engine.createEvent(ctx.conn, DagEventType.DRIVE_ADDED, mapOf(
            "key" to "ext-4tb", "label" to "EXT-4TB", "uuid" to "abc-123", "storageClass" to "WARM"
        ))

        val state = ctx.materializer.materialize(ctx.conn)
        assertEquals(1, state.drives.size)
        val drive = state.drives["ext-4tb"]!!
        assertEquals("EXT-4TB", drive.label)
        assertEquals("abc-123", drive.uuid)
        assertEquals(StorageClass.WARM, drive.storageClass)
        ctx.conn.close()
    }

    @Test
    fun `DRIVE_REMOVED removes drive`() {
        val ctx = setup()
        ctx.engine.createEvent(ctx.conn, DagEventType.HOUSE_CREATED, mapOf("name" to "house"))
        ctx.engine.createEvent(ctx.conn, DagEventType.DRIVE_ADDED, mapOf("key" to "d1", "label" to "D1", "uuid" to "u1"))
        ctx.engine.createEvent(ctx.conn, DagEventType.DRIVE_REMOVED, mapOf("key" to "d1"))

        val state = ctx.materializer.materialize(ctx.conn)
        assertTrue(state.drives.isEmpty())
        ctx.conn.close()
    }

    // --- Repositories ---

    @Test
    fun `REPO_CREATED adds repository with replication policy`() {
        val ctx = setup()
        ctx.engine.createEvent(ctx.conn, DagEventType.HOUSE_CREATED, mapOf("name" to "house"))
        ctx.engine.createEvent(ctx.conn, DagEventType.REPO_CREATED, mapOf(
            "name" to "film", "type" to "MEDIA", "localPath" to "/media/film",
            "minCopies" to "2", "preferredCopies" to "3"
        ))

        val state = ctx.materializer.materialize(ctx.conn)
        assertEquals(1, state.repositories.size)
        val repo = state.repositories["film"]!!
        assertEquals(RepositoryType.MEDIA, repo.type)
        assertEquals(2, repo.replication.minCopies)
        assertEquals(3, repo.replication.preferredCopies)
        ctx.conn.close()
    }

    @Test
    fun `REPO_POLICY_CHANGED updates replication policy`() {
        val ctx = setup()
        ctx.engine.createEvent(ctx.conn, DagEventType.HOUSE_CREATED, mapOf("name" to "house"))
        ctx.engine.createEvent(ctx.conn, DagEventType.REPO_CREATED, mapOf(
            "name" to "film", "type" to "MEDIA", "minCopies" to "1", "preferredCopies" to "2"
        ))
        ctx.engine.createEvent(ctx.conn, DagEventType.REPO_POLICY_CHANGED, mapOf(
            "name" to "film", "minCopies" to "2", "preferredCopies" to "4"
        ))

        val state = ctx.materializer.materialize(ctx.conn)
        assertEquals(2, state.repositories["film"]!!.replication.minCopies)
        assertEquals(4, state.repositories["film"]!!.replication.preferredCopies)
        ctx.conn.close()
    }

    // --- Peers ---

    @Test
    fun `PEER_TRUSTED adds peer to house`() {
        val ctx = setup()
        ctx.engine.createEvent(ctx.conn, DagEventType.HOUSE_CREATED, mapOf("name" to "house"))
        ctx.engine.createEvent(ctx.conn, DagEventType.PEER_TRUSTED, mapOf(
            "peerHouseId" to "peer123", "peerName" to "friend-house", "tailscaleIp" to "100.64.0.5"
        ))

        val state = ctx.materializer.materialize(ctx.conn)
        assertEquals(1, state.house!!.peers.size)
        val peer = state.house!!.peers["peer123"]!!
        assertEquals("friend-house", peer.name)
        assertEquals("100.64.0.5", peer.tailscaleIp)
        ctx.conn.close()
    }

    @Test
    fun `PEER_REVOKED removes peer`() {
        val ctx = setup()
        ctx.engine.createEvent(ctx.conn, DagEventType.HOUSE_CREATED, mapOf("name" to "house"))
        ctx.engine.createEvent(ctx.conn, DagEventType.PEER_TRUSTED, mapOf("peerHouseId" to "p1", "peerName" to "friend"))
        ctx.engine.createEvent(ctx.conn, DagEventType.PEER_REVOKED, mapOf("peerHouseId" to "p1"))

        val state = ctx.materializer.materialize(ctx.conn)
        assertTrue(state.house!!.peers.isEmpty())
        ctx.conn.close()
    }

    // --- Shares ---

    @Test
    fun `SHARE_GRANTED adds share`() {
        val ctx = setup()
        ctx.engine.createEvent(ctx.conn, DagEventType.HOUSE_CREATED, mapOf("name" to "house"))
        ctx.engine.createEvent(ctx.conn, DagEventType.SHARE_GRANTED, mapOf(
            "repository" to "film", "peerHouseId" to "peer1", "accessLevel" to "READ"
        ))

        val state = ctx.materializer.materialize(ctx.conn)
        assertEquals(1, state.shares.size)
        assertEquals("film", state.shares[0].repository)
        assertEquals(AccessLevel.READ, state.shares[0].access)
        ctx.conn.close()
    }

    @Test
    fun `SHARE_REVOKED removes share`() {
        val ctx = setup()
        ctx.engine.createEvent(ctx.conn, DagEventType.HOUSE_CREATED, mapOf("name" to "house"))
        ctx.engine.createEvent(ctx.conn, DagEventType.SHARE_GRANTED, mapOf(
            "repository" to "film", "peerHouseId" to "p1", "accessLevel" to "READ"
        ))
        ctx.engine.createEvent(ctx.conn, DagEventType.SHARE_REVOKED, mapOf(
            "repository" to "film", "peerHouseId" to "p1"
        ))

        val state = ctx.materializer.materialize(ctx.conn)
        assertTrue(state.shares.isEmpty())
        ctx.conn.close()
    }

    @Test
    fun `re-granting share replaces previous grant`() {
        val ctx = setup()
        ctx.engine.createEvent(ctx.conn, DagEventType.HOUSE_CREATED, mapOf("name" to "house"))
        ctx.engine.createEvent(ctx.conn, DagEventType.SHARE_GRANTED, mapOf(
            "repository" to "film", "peerHouseId" to "p1", "accessLevel" to "READ"
        ))
        ctx.engine.createEvent(ctx.conn, DagEventType.SHARE_GRANTED, mapOf(
            "repository" to "film", "peerHouseId" to "p1", "accessLevel" to "WRITE"
        ))

        val state = ctx.materializer.materialize(ctx.conn)
        assertEquals(1, state.shares.size)
        assertEquals(AccessLevel.WRITE, state.shares[0].access) // Upgraded
        ctx.conn.close()
    }

    // --- Backups ---

    @Test
    fun `BACKUP_OFFERED creates proposed agreement`() {
        val ctx = setup()
        ctx.engine.createEvent(ctx.conn, DagEventType.HOUSE_CREATED, mapOf("name" to "house"))
        ctx.engine.createEvent(ctx.conn, DagEventType.BACKUP_OFFERED, mapOf(
            "peerHouseId" to "p1", "offeredBytes" to "2199023255552" // 2TB
        ))

        val state = ctx.materializer.materialize(ctx.conn)
        assertEquals(1, state.backups.size)
        assertEquals(BackupStatus.PROPOSED, state.backups[0].status)
        assertEquals(2199023255552L, state.backups[0].offeredBytes)
        ctx.conn.close()
    }

    @Test
    fun `BACKUP_ACCEPTED transitions to accepted`() {
        val ctx = setup()
        ctx.engine.createEvent(ctx.conn, DagEventType.HOUSE_CREATED, mapOf("name" to "house"))
        ctx.engine.createEvent(ctx.conn, DagEventType.BACKUP_OFFERED, mapOf("peerHouseId" to "p1", "offeredBytes" to "100"))
        ctx.engine.createEvent(ctx.conn, DagEventType.BACKUP_ACCEPTED, mapOf("peerHouseId" to "p1", "theirOfferedBytes" to "200"))

        val state = ctx.materializer.materialize(ctx.conn)
        assertEquals(BackupStatus.ACCEPTED, state.backups[0].status)
        assertEquals(200L, state.backups[0].theirOfferedBytes)
        ctx.conn.close()
    }

    @Test
    fun `BACKUP_TERMINATED ends agreement`() {
        val ctx = setup()
        ctx.engine.createEvent(ctx.conn, DagEventType.HOUSE_CREATED, mapOf("name" to "house"))
        ctx.engine.createEvent(ctx.conn, DagEventType.BACKUP_OFFERED, mapOf("peerHouseId" to "p1", "offeredBytes" to "100"))
        ctx.engine.createEvent(ctx.conn, DagEventType.BACKUP_ACCEPTED, mapOf("peerHouseId" to "p1", "theirOfferedBytes" to "200"))
        ctx.engine.createEvent(ctx.conn, DagEventType.BACKUP_TERMINATED, mapOf("peerHouseId" to "p1"))

        val state = ctx.materializer.materialize(ctx.conn)
        assertEquals(BackupStatus.TERMINATED, state.backups[0].status)
        ctx.conn.close()
    }

    // --- toChoamConfig ---

    @Test
    fun `toChoamConfig produces valid ChoamConfig`() {
        val ctx = setup()
        ctx.engine.createEvent(ctx.conn, DagEventType.HOUSE_CREATED, mapOf("name" to "test-house"))
        ctx.engine.createEvent(ctx.conn, DagEventType.MACHINE_JOINED, mapOf("name" to "local", "hostname" to "h", "type" to "DESKTOP"))
        ctx.engine.createEvent(ctx.conn, DagEventType.REPO_CREATED, mapOf("name" to "film", "type" to "MEDIA"))

        val state = ctx.materializer.materialize(ctx.conn)
        val config = state.toChoamConfig()

        assertEquals("test-house", config.house?.name)
        assertEquals(1, config.machines.size)
        assertEquals(1, config.repositories.size)
        ctx.conn.close()
    }

    // --- Full scenario ---

    @Test
    fun `full lifecycle materializes correctly`() {
        val ctx = setup()

        // Build up a realistic config via events
        ctx.engine.createEvent(ctx.conn, DagEventType.HOUSE_CREATED, mapOf("name" to "my-house"))
        ctx.engine.createEvent(ctx.conn, DagEventType.MACHINE_JOINED, mapOf("name" to "local", "hostname" to "mac.local", "type" to "DESKTOP"))
        ctx.engine.createEvent(ctx.conn, DagEventType.MACHINE_JOINED, mapOf("name" to "server", "hostname" to "srv.local", "type" to "SERVER", "tailscaleIp" to "100.64.0.2"))
        ctx.engine.createEvent(ctx.conn, DagEventType.DRIVE_ADDED, mapOf("key" to "ext", "label" to "EXT-4TB", "uuid" to "uuid-1", "storageClass" to "WARM"))
        ctx.engine.createEvent(ctx.conn, DagEventType.REPO_CREATED, mapOf("name" to "film", "type" to "MEDIA", "minCopies" to "2", "preferredCopies" to "3"))
        ctx.engine.createEvent(ctx.conn, DagEventType.REPO_CREATED, mapOf("name" to "backup", "type" to "ARCHIVE", "minCopies" to "1"))
        ctx.engine.createEvent(ctx.conn, DagEventType.PEER_TRUSTED, mapOf("peerHouseId" to "friend1", "peerName" to "friend"))
        ctx.engine.createEvent(ctx.conn, DagEventType.SHARE_GRANTED, mapOf("repository" to "film", "peerHouseId" to "friend1", "accessLevel" to "READ"))
        ctx.engine.createEvent(ctx.conn, DagEventType.BACKUP_OFFERED, mapOf("peerHouseId" to "friend1", "offeredBytes" to "1000000"))
        ctx.engine.createEvent(ctx.conn, DagEventType.BACKUP_ACCEPTED, mapOf("peerHouseId" to "friend1", "theirOfferedBytes" to "500000"))

        val state = ctx.materializer.materialize(ctx.conn)

        assertEquals("my-house", state.house!!.name)
        assertEquals(2, state.machines.size)
        assertEquals(1, state.drives.size)
        assertEquals(2, state.repositories.size)
        assertEquals(1, state.house!!.peers.size)
        assertEquals(1, state.shares.size)
        assertEquals(1, state.backups.size)
        assertEquals(AccessLevel.READ, state.shares[0].access)
        assertEquals(BackupStatus.ACCEPTED, state.backups[0].status)

        // Verify replication policy
        assertEquals(2, state.repositories["film"]!!.replication.minCopies)
        assertEquals(3, state.repositories["film"]!!.replication.preferredCopies)

        ctx.conn.close()
    }
}
