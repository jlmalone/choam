package vision.salient.choam.network

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import vision.salient.choam.config.ChoamConfig
import vision.salient.choam.config.MachineProfile
import vision.salient.choam.config.MachineType
import vision.salient.choam.config.NetworkMode

class TransferManagerTest {
    private fun testMachine(name: String): MachineProfile =
        MachineProfile(
            name = name,
            hostname = "localhost",
            type = MachineType.DESKTOP,
            repositories = emptyMap(),
            sshUser = null,
            sshPort = 22,
            tailscaleIp = null,
            networkPreference = NetworkMode.AUTO
        )

    @Test
    fun transfersFileBetweenLocalPaths() {
        runBlocking {
            val tempDir = Files.createTempDirectory("transfer-manager-test")
            val sourcePath = tempDir.resolve("source.txt")
            val targetPath = tempDir.resolve("target.txt")

            Files.writeString(sourcePath, "hello world")

            val config = ChoamConfig()
            val manager = TransferManager(config)
            val route =
                NetworkRoute(
                    mode = NetworkMode.LAN,
                    sourceAddress = "localhost",
                    targetAddress = "localhost",
                    estimatedBandwidth = null,
                    latency = null
                )

            var lastProgressBytes = 0L

            val result =
                manager.transferFile(
                    source = FileLocation(testMachine("source"), sourcePath.toString()),
                    target = FileLocation(testMachine("target"), targetPath.toString()),
                    route = route
                ) { progress ->
                    lastProgressBytes = progress.bytesTransferred
                }

            assertTrue(result is TransferResult.Success)
            assertTrue(Files.exists(targetPath))
            assertEquals("hello world", Files.readString(targetPath))
            assertEquals(Files.size(sourcePath), lastProgressBytes)
        }
    }

    @Test
    fun failsWhenSourceFileDoesNotExist() {
        runBlocking {
            val tempDir = Files.createTempDirectory("transfer-manager-missing-source")
            val sourcePath = tempDir.resolve("missing.txt")
            val targetPath = tempDir.resolve("target.txt")

            val config = ChoamConfig()
            val manager = TransferManager(config)
            val route =
                NetworkRoute(
                    mode = NetworkMode.LAN,
                    sourceAddress = "localhost",
                    targetAddress = "localhost",
                    estimatedBandwidth = null,
                    latency = null
                )

            val result =
                manager.transferFile(
                    source = FileLocation(testMachine("source"), sourcePath.toString()),
                    target = FileLocation(testMachine("target"), targetPath.toString()),
                    route = route
                ) { _ ->
                    // no-op
                }
            assertTrue(result is TransferResult.Failure)
        }
    }
}
