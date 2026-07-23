package vision.salient.choam.catalog

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vision.salient.choam.sync.FileManifest

class DiffCalculatorTest {
    @Test
    fun `detects new files in source`() {
        val now = Instant.now()
        val source = RepositoryCatalog(
            machineName = "source",
            repositoryName = "test-repo",
            manifests = listOf(
                FileManifest("file1.txt", 100, now),
                FileManifest("file2.txt", 200, now)
            )
        )
        val target = RepositoryCatalog(
            machineName = "target",
            repositoryName = "test-repo",
            manifests = listOf(
                FileManifest("file1.txt", 100, now)
            )
        )

        val calculator = DiffCalculator()
        val diff = calculator.calculateDiff(source, target)

        assertEquals(1, diff.newFiles.size)
        assertEquals("file2.txt", diff.newFiles[0].path)
        assertEquals(0, diff.deletedFiles.size)
        assertEquals(0, diff.modifiedFiles.size)
    }

    @Test
    fun `detects deleted files in target`() {
        val now = Instant.now()
        val source = RepositoryCatalog(
            machineName = "source",
            repositoryName = "test-repo",
            manifests = listOf(
                FileManifest("file1.txt", 100, now)
            )
        )
        val target = RepositoryCatalog(
            machineName = "target",
            repositoryName = "test-repo",
            manifests = listOf(
                FileManifest("file1.txt", 100, now),
                FileManifest("file2.txt", 200, now)
            )
        )

        val calculator = DiffCalculator()
        val diff = calculator.calculateDiff(source, target)

        assertEquals(0, diff.newFiles.size)
        assertEquals(1, diff.deletedFiles.size)
        assertEquals("file2.txt", diff.deletedFiles[0].path)
        assertEquals(0, diff.modifiedFiles.size)
    }

    @Test
    fun `detects modified files by size`() {
        val now = Instant.now()
        val source = RepositoryCatalog(
            machineName = "source",
            repositoryName = "test-repo",
            manifests = listOf(
                FileManifest("file1.txt", 100, now)
            )
        )
        val target = RepositoryCatalog(
            machineName = "target",
            repositoryName = "test-repo",
            manifests = listOf(
                FileManifest("file1.txt", 200, now)
            )
        )

        val calculator = DiffCalculator()
        val diff = calculator.calculateDiff(source, target)

        assertEquals(0, diff.newFiles.size)
        assertEquals(0, diff.deletedFiles.size)
        assertEquals(1, diff.modifiedFiles.size)
        assertEquals("file1.txt", diff.modifiedFiles[0].first.path)
    }

    @Test
    fun `detects modified files by timestamp`() {
        val now = Instant.now()
        val later = now.plusSeconds(60)
        val source = RepositoryCatalog(
            machineName = "source",
            repositoryName = "test-repo",
            manifests = listOf(
                FileManifest("file1.txt", 100, later)
            )
        )
        val target = RepositoryCatalog(
            machineName = "target",
            repositoryName = "test-repo",
            manifests = listOf(
                FileManifest("file1.txt", 100, now)
            )
        )

        val calculator = DiffCalculator()
        val diff = calculator.calculateDiff(source, target)

        assertEquals(0, diff.newFiles.size)
        assertEquals(0, diff.deletedFiles.size)
        assertEquals(1, diff.modifiedFiles.size)
        assertEquals("file1.txt", diff.modifiedFiles[0].first.path)
        assertEquals(later, diff.modifiedFiles[0].first.modifiedTime)
        assertEquals(now, diff.modifiedFiles[0].second.modifiedTime)
    }

    @Test
    fun `handles empty source catalog`() {
        val now = Instant.now()
        val source = RepositoryCatalog(
            machineName = "source",
            repositoryName = "test-repo",
            manifests = emptyList()
        )
        val target = RepositoryCatalog(
            machineName = "target",
            repositoryName = "test-repo",
            manifests = listOf(
                FileManifest("file1.txt", 100, now)
            )
        )

        val calculator = DiffCalculator()
        val diff = calculator.calculateDiff(source, target)

        assertEquals(0, diff.newFiles.size)
        assertEquals(1, diff.deletedFiles.size)
        assertEquals(0, diff.modifiedFiles.size)
    }

    @Test
    fun `handles empty target catalog`() {
        val now = Instant.now()
        val source = RepositoryCatalog(
            machineName = "source",
            repositoryName = "test-repo",
            manifests = listOf(
                FileManifest("file1.txt", 100, now)
            )
        )
        val target = RepositoryCatalog(
            machineName = "target",
            repositoryName = "test-repo",
            manifests = emptyList()
        )

        val calculator = DiffCalculator()
        val diff = calculator.calculateDiff(source, target)

        assertEquals(1, diff.newFiles.size)
        assertEquals(0, diff.deletedFiles.size)
        assertEquals(0, diff.modifiedFiles.size)
    }

    @Test
    fun `handles identical catalogs`() {
        val now = Instant.now()
        val source = RepositoryCatalog(
            machineName = "source",
            repositoryName = "test-repo",
            manifests = listOf(
                FileManifest("file1.txt", 100, now),
                FileManifest("file2.txt", 200, now)
            )
        )
        val target = RepositoryCatalog(
            machineName = "target",
            repositoryName = "test-repo",
            manifests = listOf(
                FileManifest("file1.txt", 100, now),
                FileManifest("file2.txt", 200, now)
            )
        )

        val calculator = DiffCalculator()
        val diff = calculator.calculateDiff(source, target)

        assertEquals(0, diff.newFiles.size)
        assertEquals(0, diff.deletedFiles.size)
        assertEquals(0, diff.modifiedFiles.size)
    }

    @Test
    fun `handles complex scenario with new modified and deleted files`() {
        val now = Instant.now()
        val later = now.plusSeconds(60)
        val source = RepositoryCatalog(
            machineName = "source",
            repositoryName = "test-repo",
            manifests = listOf(
                FileManifest("file1.txt", 100, now),
                FileManifest("file2.txt", 200, later),
                FileManifest("file3.txt", 300, now)
            )
        )
        val target = RepositoryCatalog(
            machineName = "target",
            repositoryName = "test-repo",
            manifests = listOf(
                FileManifest("file1.txt", 100, now),
                FileManifest("file2.txt", 200, now),
                FileManifest("file4.txt", 400, now)
            )
        )

        val calculator = DiffCalculator()
        val diff = calculator.calculateDiff(source, target)

        assertEquals(1, diff.newFiles.size)
        assertTrue(diff.newFiles.any { it.path == "file3.txt" })

        assertEquals(1, diff.deletedFiles.size)
        assertTrue(diff.deletedFiles.any { it.path == "file4.txt" })

        assertEquals(1, diff.modifiedFiles.size)
        assertTrue(diff.modifiedFiles.any { it.first.path == "file2.txt" })
    }
}
