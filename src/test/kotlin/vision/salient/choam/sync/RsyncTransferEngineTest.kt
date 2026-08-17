package vision.salient.choam.sync

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vision.salient.choam.config.MachineProfile
import vision.salient.choam.config.MachineType
import vision.salient.choam.config.NetworkMode
import vision.salient.choam.network.NetworkRoute
import vision.salient.choam.network.RsyncErrorClass
import vision.salient.choam.network.classifyRsyncExit

class RsyncTransferEngineTest {

    private val engine = RsyncTransferEngine()

    @Test
    fun `buildRsyncCommand includes archive verbose progress partial-dir`() {
        val cmd = engine.buildRsyncCommand(
            sourcePath = "/src",
            targetPath = "/dst"
        )

        assertTrue(cmd.first().endsWith("rsync"), "argv0 should be an rsync binary")
        assertTrue(cmd.contains("--archive"))
        assertTrue(cmd.contains("--verbose"))
        assertTrue(cmd.contains("--progress"))
        assertTrue(cmd.contains("--partial-dir=.rsync-partial"))
        assertFalse(cmd.contains("--checksum"), "checksum should not be included by default")
    }

    // ── rsync binary selection (openrsync on macOS breaks --partial-dir resume) ──

    @Test
    fun `buildRsyncCommand uses the given local rsync binary as argv0`() {
        val cmd = engine.buildRsyncCommand(
            sourcePath = "/src",
            targetPath = "/dst",
            rsyncBinary = "/opt/homebrew/bin/rsync"
        )
        assertEquals("/opt/homebrew/bin/rsync", cmd.first())
    }

    @Test
    fun `buildRsyncCommand passes rsync-path for remote transfers when probed`() {
        val machine = MachineProfile(
            name = "remote", hostname = "remote.local", type = MachineType.SERVER,
            repositories = emptyMap(), sshUser = "joe"
        )
        val route = NetworkRoute(
            mode = NetworkMode.TAILSCALE, sourceAddress = "100.64.0.1", targetAddress = "100.64.0.2"
        )
        val cmd = engine.buildRsyncCommand(
            sourcePath = "/src",
            targetPath = "/dst",
            targetMachine = machine,
            route = route,
            remoteRsyncPath = "/opt/homebrew/bin/rsync"
        )
        assertTrue(cmd.contains("--rsync-path=nice -n 19 /opt/homebrew/bin/rsync"))
    }

    @Test
    fun `buildRsyncCommand omits rsync-path when none was probed`() {
        val machine = MachineProfile(
            name = "remote", hostname = "remote.local", type = MachineType.SERVER,
            repositories = emptyMap(), sshUser = "joe"
        )
        val route = NetworkRoute(
            mode = NetworkMode.TAILSCALE, sourceAddress = "100.64.0.1", targetAddress = "100.64.0.2"
        )
        val cmd = engine.buildRsyncCommand(
            sourcePath = "/src",
            targetPath = "/dst",
            targetMachine = machine,
            route = route
        )
        assertFalse(cmd.any { it.startsWith("--rsync-path=") })
    }

    @Test
    fun `buildRsyncCommand includes checksum when requested`() {
        val cmd = engine.buildRsyncCommand(
            sourcePath = "/src",
            targetPath = "/dst",
            useChecksum = true
        )
        assertTrue(cmd.contains("--checksum"))
    }

    @Test
    fun `buildRsyncCommand uses custom timeout`() {
        val cmd = engine.buildRsyncCommand(
            sourcePath = "/src",
            targetPath = "/dst",
            timeoutSeconds = 600
        )
        assertTrue(cmd.contains("--timeout=600"))
    }

    @Test
    fun `buildRsyncCommand uses default 300s timeout`() {
        val cmd = engine.buildRsyncCommand(
            sourcePath = "/src",
            targetPath = "/dst"
        )
        assertTrue(cmd.contains("--timeout=300"))
    }

    @Test
    fun `buildRsyncCommand adds dry-run and itemize-changes for dry run`() {
        val cmd = engine.buildRsyncCommand(
            sourcePath = "/src",
            targetPath = "/dst",
            dryRun = true
        )

        assertTrue(cmd.contains("--dry-run"))
        assertTrue(cmd.contains("--itemize-changes"))
    }

    @Test
    fun `buildRsyncCommand adds bandwidth limit`() {
        val cmd = engine.buildRsyncCommand(
            sourcePath = "/src",
            targetPath = "/dst",
            bandwidthLimitKBps = 5000
        )

        assertTrue(cmd.contains("--bwlimit=5000"))
    }

    @Test
    fun `buildRsyncCommand adds exclude patterns`() {
        val cmd = engine.buildRsyncCommand(
            sourcePath = "/src",
            targetPath = "/dst",
            excludePatterns = listOf("*.tmp", "*.part", ".DS_Store")
        )

        assertTrue(cmd.contains("--exclude=*.tmp"))
        assertTrue(cmd.contains("--exclude=*.part"))
        assertTrue(cmd.contains("--exclude=.DS_Store"))
    }

    @Test
    fun `buildRsyncCommand adds ssh port for remote target transfer`() {
        val machine = MachineProfile(
            name = "remote",
            hostname = "remote.local",
            type = MachineType.SERVER,
            repositories = emptyMap(),
            sshUser = "user",
            sshPort = 2222
        )

        val route = NetworkRoute(
            mode = NetworkMode.LAN,
            sourceAddress = "localhost",
            targetAddress = "remote.local"
        )

        // Source is local path, target is remote with custom SSH port
        val cmd = engine.buildRsyncCommand(
            sourcePath = "/src",
            targetPath = "/dst",
            targetMachine = machine,
            route = route
        )

        assertTrue(cmd.contains("-e"), "Should include SSH options for remote target")
        assertTrue(cmd.any { it.contains("ssh") && it.contains("-p 2222") }, "Should use custom SSH port")
        // Target should have SSH spec with trailing slash for directory
        assertTrue(cmd.last().contains("user@remote.local:/dst/"), "Target should use SSH spec for directory")
    }

    @Test
    fun `buildRsyncCommand builds remote target spec with user and host for local-to-remote`() {
        val machine = MachineProfile(
            name = "remote",
            hostname = "remote.local",
            type = MachineType.SERVER,
            repositories = emptyMap(),
            sshUser = "admin",
            sshPort = 22
        )

        val route = NetworkRoute(
            mode = NetworkMode.LAN,
            sourceAddress = "localhost",
            targetAddress = "remote.local"
        )

        // Source is local path, target is remote
        val cmd = engine.buildRsyncCommand(
            sourcePath = "/src",
            targetPath = "/dst",
            targetMachine = machine,
            route = route
        )

        // Second-to-last arg should be local source, last should be remote target spec
        val sourceSpec = cmd[cmd.size - 2]
        val targetSpec = cmd.last()

        // Directories get trailing slashes in rsync
        assertEquals("/src/", sourceSpec, "Source directory should have trailing slash")
        assertTrue(targetSpec.startsWith("admin@remote.local:/dst/"), "Target should use SSH spec with trailing slash for directory")
    }

    @Test
    fun `buildRsyncCommand directories get trailing slash but files do not`() {
        // Test directory paths - should have trailing slash
        val dirCmd = engine.buildRsyncCommand(
            sourcePath = "/src/dir",
            targetPath = "/dst/dir"
        )
        assertTrue(dirCmd[dirCmd.size - 2].endsWith("/"), "Directory source should have trailing slash")
        assertTrue(dirCmd[dirCmd.size - 1].endsWith("/"), "Directory target should have trailing slash")

        // Test file paths - should NOT have trailing slash
        val fileCmd = engine.buildRsyncCommand(
            sourcePath = "/src/dir/file.txt",
            targetPath = "/dst/dir/file.txt"
        )
        assertFalse(fileCmd[fileCmd.size - 2].endsWith("/"), "File source should NOT have trailing slash")
        assertFalse(fileCmd[fileCmd.size - 1].endsWith("/"), "File target should NOT have trailing slash")
    }

    // ── appendSourceSlash: explicit placement semantics (regression for the trailing-slash bug) ──

    @Test
    fun `appendSourceSlash false keeps dotless directory source without trailing slash`() {
        // Regression: dotless directory names (e.g. "sample-collection") were given a
        // trailing slash by the legacy heuristic, dumping contents into the parent instead of
        // creating <target>/<basename> — which then failed post-transfer verification forever.
        val cmd = engine.buildRsyncCommand(
            sourcePath = "/src/sample-collection",
            targetPath = "/dst/media_library/",
            appendSourceSlash = false
        )
        assertEquals("/src/sample-collection", cmd[cmd.size - 2],
            "Source must NOT get a trailing slash so rsync creates <target>/<basename>")
    }

    @Test
    fun `appendSourceSlash false strips an existing trailing slash on the source`() {
        val cmd = engine.buildRsyncCommand(
            sourcePath = "/src/mydir/",
            targetPath = "/dst/",
            appendSourceSlash = false
        )
        assertEquals("/src/mydir", cmd[cmd.size - 2])
    }

    @Test
    fun `appendSourceSlash false applies to remote source path component`() {
        val machine = MachineProfile(
            name = "remote", hostname = "remote.local", type = MachineType.SERVER,
            repositories = emptyMap(), sshUser = "joe"
        )
        val route = NetworkRoute(
            mode = NetworkMode.TAILSCALE, sourceAddress = "100.64.0.1", targetAddress = "100.64.0.2"
        )
        val cmd = engine.buildRsyncCommand(
            sourcePath = "/data/Sample Collection (BD)",
            targetPath = "/backup/",
            sourceMachine = machine,
            route = route,
            appendSourceSlash = false
        )
        assertEquals("joe@100.64.0.1:/data/Sample Collection (BD)", cmd[cmd.size - 2],
            "Remote source must keep its user@host: prefix and have no trailing slash")
    }

    @Test
    fun `appendSourceSlash true forces trailing slash for merge semantics`() {
        val cmd = engine.buildRsyncCommand(
            sourcePath = "/src/repo",
            targetPath = "/dst/repo/",
            appendSourceSlash = true
        )
        assertTrue(cmd[cmd.size - 2].endsWith("/"), "Merge semantics must keep the source trailing slash")
    }

    @Test
    fun `appendSourceSlash null preserves legacy heuristic`() {
        // Default (null) keeps historical behavior so sync/push/pull/move are unaffected.
        val dirCmd = engine.buildRsyncCommand(sourcePath = "/src/dir", targetPath = "/dst/dir")
        assertTrue(dirCmd[dirCmd.size - 2].endsWith("/"))
        val fileCmd = engine.buildRsyncCommand(sourcePath = "/src/a.txt", targetPath = "/dst/a.txt")
        assertFalse(fileCmd[fileCmd.size - 2].endsWith("/"))
    }

    @Test
    fun `buildRsyncCommand does not add ssh for local transfer`() {
        val cmd = engine.buildRsyncCommand(
            sourcePath = "/src",
            targetPath = "/dst"
        )

        assertFalse(cmd.contains("-e"))
    }

    @Test
    fun `buildRsyncCommand remote file transfer does not add trailing slash`() {
        val machine = MachineProfile(
            name = "remote",
            hostname = "remote.local",
            type = MachineType.SERVER,
            repositories = emptyMap(),
            sshUser = "admin",
            sshPort = 22
        )

        val route = NetworkRoute(
            mode = NetworkMode.LAN,
            sourceAddress = "localhost",
            targetAddress = "remote.local"
        )

        val cmd = engine.buildRsyncCommand(
            sourcePath = "/src/video.mkv",
            targetPath = "/dst/video.mkv",
            targetMachine = machine,
            route = route
        )

        val targetSpec = cmd.last()
        assertFalse(targetSpec.endsWith("/"), "Remote file paths should not have trailing slash")
        assertTrue(targetSpec.startsWith("admin@remote.local:/dst/video.mkv"))
    }

    @Test
    fun `buildRsyncCommand glob patterns get trailing slash`() {
        val cmd = engine.buildRsyncCommand(
            sourcePath = "/src/dir/*",
            targetPath = "/dst/dir/"
        )

        // Glob patterns should have trailing slash
        assertTrue(cmd[cmd.size - 2].endsWith("/"), "Glob source should have trailing slash")
        assertTrue(cmd[cmd.size - 1].endsWith("/"), "Directory target should have trailing slash")
    }

    @Test
    fun `buildRsyncCommand remote-to-local with source SSH spec`() {
        val machine = MachineProfile(
            name = "source-machine",
            hostname = "source.local",
            type = MachineType.DESKTOP,
            repositories = emptyMap(),
            sshUser = "joe"
        )

        val route = NetworkRoute(
            mode = NetworkMode.TAILSCALE,
            sourceAddress = "100.64.0.1",
            targetAddress = "100.64.0.2"
        )

        // Source is remote, target is local path
        val cmd = engine.buildRsyncCommand(
            sourcePath = "/data/media",
            targetPath = "/backup/media",
            sourceMachine = machine,
            route = route
        )

        val sourceSpec = cmd[cmd.size - 2]
        val targetSpec = cmd.last()

        // Both directories (no extension), should have trailing slashes
        assertTrue(sourceSpec.startsWith("joe@100.64.0.1:/data/media/"), "Source should use SSH spec with trailing slash")
        assertEquals("/backup/media/", targetSpec, "Target should be local path with trailing slash (directory)")
    }

    // ── Rsync error classification ──

    @Test
    fun `classifyRsyncExit returns TRANSIENT for connection failures`() {
        assertEquals(RsyncErrorClass.TRANSIENT, classifyRsyncExit(12))   // protocol stream error
        assertEquals(RsyncErrorClass.TRANSIENT, classifyRsyncExit(255))  // SSH fail
        assertEquals(RsyncErrorClass.TRANSIENT, classifyRsyncExit(137))  // killed by signal
        assertEquals(RsyncErrorClass.TRANSIENT, classifyRsyncExit(30))   // timeout
        assertEquals(RsyncErrorClass.TRANSIENT, classifyRsyncExit(20))   // SIGUSR1/SIGINT
        assertEquals(RsyncErrorClass.TRANSIENT, classifyRsyncExit(35))   // daemon timeout
    }

    @Test
    fun `classifyRsyncExit returns PARTIAL for partial transfers`() {
        assertEquals(RsyncErrorClass.PARTIAL, classifyRsyncExit(23))
        assertEquals(RsyncErrorClass.PARTIAL, classifyRsyncExit(24))
    }

    @Test
    fun `classifyRsyncExit returns PERMANENT for syntax and IO errors`() {
        assertEquals(RsyncErrorClass.PERMANENT, classifyRsyncExit(1))   // syntax
        assertEquals(RsyncErrorClass.PERMANENT, classifyRsyncExit(2))   // protocol
        assertEquals(RsyncErrorClass.PERMANENT, classifyRsyncExit(3))   // file selection
        assertEquals(RsyncErrorClass.PERMANENT, classifyRsyncExit(11))  // file I/O
    }

    @Test
    fun `classifyRsyncExit returns PERMANENT for unknown codes`() {
        assertEquals(RsyncErrorClass.PERMANENT, classifyRsyncExit(99))
        assertEquals(RsyncErrorClass.PERMANENT, classifyRsyncExit(42))
    }
}
