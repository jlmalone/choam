package vision.salient.choam.catalog

import vision.salient.choam.sync.FileManifest
import vision.salient.choam.sync.SyncDiff

class DiffCalculator {
    fun calculateDiff(
        source: RepositoryCatalog,
        target: RepositoryCatalog
    ): SyncDiff {
        val sourceMap = source.manifests.associateBy { it.path }
        val targetMap = target.manifests.associateBy { it.path }

        val newFiles = mutableListOf<FileManifest>()
        val deletedFiles = mutableListOf<FileManifest>()
        val modifiedFiles = mutableListOf<Pair<FileManifest, FileManifest>>()

        for ((path, src) in sourceMap) {
            val tgt = targetMap[path]
            if (tgt == null) {
                newFiles.add(src)
            } else if (src.size != tgt.size || src.modifiedTime != tgt.modifiedTime) {
                modifiedFiles.add(src to tgt)
            }
        }

        for ((path, tgt) in targetMap) {
            if (path !in sourceMap) {
                deletedFiles.add(tgt)
            }
        }

        return SyncDiff(
            newFiles = newFiles,
            modifiedFiles = modifiedFiles,
            deletedFiles = deletedFiles
        )
    }
}

