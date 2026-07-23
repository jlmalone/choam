package vision.salient.choam.catalog

import vision.salient.choam.sync.FileManifest

data class RepositoryCatalog(
    val machineName: String,
    val repositoryName: String,
    val manifests: List<FileManifest>
)
