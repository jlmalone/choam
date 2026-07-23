package vision.salient.choam.sync

import vision.salient.choam.config.ConflictStrategy

class ConflictResolver {
    fun resolveConflict(
        conflict: ConflictPair,
        strategy: ConflictStrategy
    ): ConflictResolution {
        return when (strategy) {
            ConflictStrategy.NEWER_WINS -> resolveByTimestamp(conflict)
            ConflictStrategy.LARGER_WINS -> resolveBySize(conflict)
            ConflictStrategy.MANUAL -> ConflictResolution.RequiresManualReview(conflict)
            ConflictStrategy.KEEP_BOTH -> ConflictResolution.KeepBoth(conflict)
        }
    }

    private fun resolveByTimestamp(conflict: ConflictPair): ConflictResolution {
        return if (conflict.source.modifiedTime >= conflict.target.modifiedTime) {
            ConflictResolution.UseSource(conflict.source)
        } else {
            ConflictResolution.UseTarget(conflict.target)
        }
    }

    private fun resolveBySize(conflict: ConflictPair): ConflictResolution {
        return if (conflict.source.size >= conflict.target.size) {
            ConflictResolution.UseSource(conflict.source)
        } else {
            ConflictResolution.UseTarget(conflict.target)
        }
    }
}

sealed class ConflictResolution {
    data class UseSource(val file: FileManifest) : ConflictResolution()
    data class UseTarget(val file: FileManifest) : ConflictResolution()
    data class KeepBoth(val conflict: ConflictPair) : ConflictResolution()
    data class RequiresManualReview(val conflict: ConflictPair) : ConflictResolution()
}

