package vision.salient.choam.sync

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import vision.salient.choam.config.ChoamConfig
import vision.salient.choam.config.ConflictStrategy
import vision.salient.choam.config.MachineProfile
import vision.salient.choam.config.MachineType
import vision.salient.choam.config.NetworkMode
import vision.salient.choam.config.RepositoryConfig
import vision.salient.choam.config.RepositoryType
import vision.salient.choam.config.SyncRules
import vision.salient.choam.network.NetworkRoute
import vision.salient.choam.network.TransferManager

class SyncEngineTest {
    private fun testMachine(
        name: String,
        repoName: String,
        repoPath: String
    ): MachineProfile =
        MachineProfile(
            name = name,
            hostname = "localhost",
            type = MachineType.DESKTOP,
            repositories = mapOf(repoName to repoPath),
            sshUser = null,
            sshPort = 22,
            tailscaleIp = null,
            networkPreference = NetworkMode.AUTO
        )

    @Test
    fun unidirectionalSyncCopiesNewAndModifiedFiles() {
        runBlocking {
            val repoName = "test-repo"
            val sourceDir = Files.createTempDirectory("sync-engine-source").toFile()
            val targetDir = Files.createTempDirectory("sync-engine-target").toFile()

            // Source files
            val sourceFile1 = sourceDir.resolve("file1.txt")
            val sourceFile2 = sourceDir.resolve("file2.txt")
            sourceFile1.writeText("one")
            sourceFile2.writeText("two-modified")

            // Target has an older version of file2 and no file1
            val targetFile2 = targetDir.resolve("file2.txt")
            targetFile2.writeText("two-old")

            val sourceMachine = testMachine("source", repoName, sourceDir.absolutePath)
            val targetMachine = testMachine("target", repoName, targetDir.absolutePath)

            val config =
                ChoamConfig(
                    machines = mapOf(
                        sourceMachine.name to sourceMachine,
                        targetMachine.name to targetMachine
                    ),
                    repositories = mapOf(
                        repoName to
                            RepositoryConfig(
                                name = repoName,
                                localPath = repoName,
                                type = RepositoryType.GENERIC
                            )
                    )
                )

            val rules =
                SyncRules(
                    bidirectional = false,
                    deleteRemoved = false,
                    conflictResolution = ConflictStrategy.NEWER_WINS
                )

            val engine = SyncEngine(config, TransferManager(config), ConflictResolver())
            val route =
                NetworkRoute(
                    mode = NetworkMode.LAN,
                    sourceAddress = "localhost",
                    targetAddress = "localhost"
                )

            val session =
                engine.sync(
                    source = sourceMachine,
                    target = targetMachine,
                    repositories = listOf(repoName),
                    rules = rules,
                    route = route,
                    dryRun = false,
                    progressCallback = null
                )

            // Both files should now exist in target with source contents.
            val targetFile1 = targetDir.resolve("file1.txt")
            val updatedTargetFile2 = targetDir.resolve("file2.txt")
            assertTrue(targetFile1.exists())
            assertTrue(updatedTargetFile2.exists())
            assertEquals("one", targetFile1.readText())
            assertEquals("two-modified", updatedTargetFile2.readText())
            assertEquals(2, session.statistics.filesTransferred)
        }
    }

    @Test
    fun bidirectionalSyncCopiesNewFilesBothWays() {
        runBlocking {
            val repoName = "test-repo"
            val sourceDir = Files.createTempDirectory("sync-engine-bidir-source").toFile()
            val targetDir = Files.createTempDirectory("sync-engine-bidir-target").toFile()

            // Source has fileA; target has fileB
            val sourceFileA = sourceDir.resolve("fileA.txt")
            val targetFileB = targetDir.resolve("fileB.txt")
            sourceFileA.writeText("from-source")
            targetFileB.writeText("from-target")

            val sourceMachine = testMachine("source", repoName, sourceDir.absolutePath)
            val targetMachine = testMachine("target", repoName, targetDir.absolutePath)

            val config =
                ChoamConfig(
                    machines = mapOf(
                        sourceMachine.name to sourceMachine,
                        targetMachine.name to targetMachine
                    ),
                    repositories = mapOf(
                        repoName to
                            RepositoryConfig(
                                name = repoName,
                                localPath = repoName,
                                type = RepositoryType.GENERIC
                            )
                    )
                )

            val rules =
                SyncRules(
                    bidirectional = true,
                    deleteRemoved = false,
                    conflictResolution = ConflictStrategy.NEWER_WINS
                )

            val engine = SyncEngine(config, TransferManager(config), ConflictResolver())
            val route =
                NetworkRoute(
                    mode = NetworkMode.LAN,
                    sourceAddress = "localhost",
                    targetAddress = "localhost"
                )

            val session =
                engine.sync(
                    source = sourceMachine,
                    target = targetMachine,
                    repositories = listOf(repoName),
                    rules = rules,
                    route = route,
                    dryRun = false,
                    progressCallback = null
                )

            // After bidirectional sync, both files should exist on both sides.
            val updatedSourceFileB = sourceDir.resolve("fileB.txt")
            val updatedTargetFileA = targetDir.resolve("fileA.txt")

            assertTrue(updatedSourceFileB.exists())
            assertTrue(updatedTargetFileA.exists())
            assertEquals("from-target", updatedSourceFileB.readText())
            assertEquals("from-source", updatedTargetFileA.readText())
            assertTrue(session.statistics.filesTransferred >= 2)
        }
    }
}
