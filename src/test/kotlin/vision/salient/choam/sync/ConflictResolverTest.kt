package vision.salient.choam.sync

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue
import vision.salient.choam.config.ConflictStrategy

class ConflictResolverTest {
    @Test
    fun `newer wins selects newer file from source`() {
        val now = Instant.now()
        val older = FileManifest(path = "file.txt", size = 100, modifiedTime = now.minusSeconds(60))
        val newer = FileManifest(path = "file.txt", size = 100, modifiedTime = now)
        val conflict = ConflictPair(source = newer, target = older, reason = ConflictReason.BOTH_MODIFIED)
        val resolver = ConflictResolver()

        val resolution = resolver.resolveConflict(conflict, ConflictStrategy.NEWER_WINS)
        assertTrue(resolution is ConflictResolution.UseSource)
    }

    @Test
    fun `newer wins selects newer file from target`() {
        val now = Instant.now()
        val older = FileManifest(path = "file.txt", size = 100, modifiedTime = now.minusSeconds(60))
        val newer = FileManifest(path = "file.txt", size = 100, modifiedTime = now)
        val conflict = ConflictPair(source = older, target = newer, reason = ConflictReason.BOTH_MODIFIED)
        val resolver = ConflictResolver()

        val resolution = resolver.resolveConflict(conflict, ConflictStrategy.NEWER_WINS)
        assertTrue(resolution is ConflictResolution.UseTarget)
    }

    @Test
    fun `newer wins selects source when timestamps are equal`() {
        val now = Instant.now()
        val file1 = FileManifest(path = "file.txt", size = 100, modifiedTime = now)
        val file2 = FileManifest(path = "file.txt", size = 200, modifiedTime = now)
        val conflict = ConflictPair(source = file1, target = file2, reason = ConflictReason.BOTH_MODIFIED)
        val resolver = ConflictResolver()

        val resolution = resolver.resolveConflict(conflict, ConflictStrategy.NEWER_WINS)
        assertTrue(resolution is ConflictResolution.UseSource)
    }

    @Test
    fun `larger wins selects larger file from source`() {
        val now = Instant.now()
        val smaller = FileManifest(path = "file.txt", size = 100, modifiedTime = now)
        val larger = FileManifest(path = "file.txt", size = 200, modifiedTime = now)
        val conflict = ConflictPair(source = larger, target = smaller, reason = ConflictReason.SIZE_MISMATCH)
        val resolver = ConflictResolver()

        val resolution = resolver.resolveConflict(conflict, ConflictStrategy.LARGER_WINS)
        assertTrue(resolution is ConflictResolution.UseSource)
    }

    @Test
    fun `larger wins selects larger file from target`() {
        val now = Instant.now()
        val smaller = FileManifest(path = "file.txt", size = 100, modifiedTime = now)
        val larger = FileManifest(path = "file.txt", size = 200, modifiedTime = now)
        val conflict = ConflictPair(source = smaller, target = larger, reason = ConflictReason.SIZE_MISMATCH)
        val resolver = ConflictResolver()

        val resolution = resolver.resolveConflict(conflict, ConflictStrategy.LARGER_WINS)
        assertTrue(resolution is ConflictResolution.UseTarget)
    }

    @Test
    fun `larger wins selects source when sizes are equal`() {
        val now = Instant.now()
        val file1 = FileManifest(path = "file.txt", size = 100, modifiedTime = now)
        val file2 = FileManifest(path = "file.txt", size = 100, modifiedTime = now.plusSeconds(10))
        val conflict = ConflictPair(source = file1, target = file2, reason = ConflictReason.BOTH_MODIFIED)
        val resolver = ConflictResolver()

        val resolution = resolver.resolveConflict(conflict, ConflictStrategy.LARGER_WINS)
        assertTrue(resolution is ConflictResolution.UseSource)
    }

    @Test
    fun `manual strategy requires manual review`() {
        val now = Instant.now()
        val file1 = FileManifest(path = "file.txt", size = 100, modifiedTime = now)
        val file2 = FileManifest(path = "file.txt", size = 100, modifiedTime = now)
        val conflict = ConflictPair(source = file1, target = file2, reason = ConflictReason.CHECKSUM_MISMATCH)
        val resolver = ConflictResolver()

        val resolution = resolver.resolveConflict(conflict, ConflictStrategy.MANUAL)
        assertTrue(resolution is ConflictResolution.RequiresManualReview)
    }

    @Test
    fun `keep both strategy returns keep both resolution`() {
        val now = Instant.now()
        val file1 = FileManifest(path = "file.txt", size = 100, modifiedTime = now)
        val file2 = FileManifest(path = "file.txt", size = 200, modifiedTime = now.plusSeconds(10))
        val conflict = ConflictPair(source = file1, target = file2, reason = ConflictReason.BOTH_MODIFIED)
        val resolver = ConflictResolver()

        val resolution = resolver.resolveConflict(conflict, ConflictStrategy.KEEP_BOTH)
        assertTrue(resolution is ConflictResolution.KeepBoth)
    }
}
