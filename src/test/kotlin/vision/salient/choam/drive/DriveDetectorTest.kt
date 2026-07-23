package vision.salient.choam.drive

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vision.salient.choam.config.Drive
import vision.salient.choam.config.MountedDrive

class DriveDetectorTest {

    private val detector = DriveDetector()

    @Test
    fun `parseDiskUtilOutput extracts key-value pairs`() {
        val output = """
            Device Identifier:        disk4s1
            Device Node:              /dev/disk4s1
            Whole:                    No
            Part of Whole:            disk4
            Volume Name:              movies-4tb
            Mounted:                  Yes
            Mount Point:              /Volumes/movies-4tb
            Volume UUID:              A1B2C3D4-E5F6-7890-ABCD-EF1234567890
            File System:              APFS
            Type (Bundle):            apfs
            Disk Size:                4000787030016 B (4.0 TB)
            Free Space:              1200000000000 B (1.2 TB)
        """.trimIndent()

        val info = detector.parseDiskUtilOutput(output)

        assertEquals("disk4s1", info["Device Identifier"])
        assertEquals("movies-4tb", info["Volume Name"])
        assertEquals("A1B2C3D4-E5F6-7890-ABCD-EF1234567890", info["Volume UUID"])
        assertEquals("/Volumes/movies-4tb", info["Mount Point"])
        assertEquals("Yes", info["Mounted"])
    }

    @Test
    fun `parseDiskUtilOutput handles empty output`() {
        val info = detector.parseDiskUtilOutput("")
        assertTrue(info.isEmpty())
    }

    @Test
    fun `parseDiskUtilOutput handles lines without colons`() {
        val output = "No colon here\nAnother line without colon"
        val info = detector.parseDiskUtilOutput(output)
        assertTrue(info.isEmpty())
    }

    @Test
    fun `resolveRepositoryPath returns correct path when drive is mounted`() {
        val drive = Drive(
            uuid = "UUID-123",
            label = "test-drive",
            repositories = mapOf("media" to "movies")
        )

        val mounted = mapOf(
            "test-drive" to MountedDrive(
                uuid = "UUID-123",
                label = "test-drive",
                mountPoint = "/Volumes/test-drive"
            )
        )

        val path = detector.resolveRepositoryPath(drive, "media", mounted)
        assertEquals("/Volumes/test-drive/movies", path)
    }

    @Test
    fun `resolveRepositoryPath returns null when drive not mounted`() {
        val drive = Drive(
            uuid = "UUID-123",
            label = "test-drive",
            repositories = mapOf("media" to "movies")
        )

        val path = detector.resolveRepositoryPath(drive, "media", emptyMap())
        assertNull(path)
    }

    @Test
    fun `resolveRepositoryPath returns null for unknown repository`() {
        val drive = Drive(
            uuid = "UUID-123",
            label = "test-drive",
            repositories = mapOf("media" to "movies")
        )

        val mounted = mapOf(
            "test-drive" to MountedDrive(
                uuid = "UUID-123",
                label = "test-drive",
                mountPoint = "/Volumes/test-drive"
            )
        )

        val path = detector.resolveRepositoryPath(drive, "nonexistent", mounted)
        assertNull(path)
    }

    @Test
    fun `formatDriveSize formats correctly`() {
        assertEquals("0 B", detector.formatDriveSize(0))
        assertEquals("512 B", detector.formatDriveSize(512))
        assertEquals("1.0 KB", detector.formatDriveSize(1024))
        assertEquals("1.5 MB", detector.formatDriveSize(1_572_864))
        assertEquals("2.0 GB", detector.formatDriveSize(2_147_483_648))
        assertEquals("1.0 TB", detector.formatDriveSize(1_099_511_627_776))
    }
}
