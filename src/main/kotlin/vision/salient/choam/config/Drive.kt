package vision.salient.choam.config

import kotlinx.serialization.Serializable

@Serializable
enum class StorageClass {
    HOT,   // Always-on, fast access (NAS, internal SSD)
    WARM,  // Attached JBOD, swapped periodically
    COLD   // Disconnected archive, manual access (Seagate 6TB)
}

@Serializable
data class Drive(
    val uuid: String,
    val label: String,
    val repositories: Map<String, String> = emptyMap(),
    val storageClass: StorageClass = StorageClass.WARM
)

data class MountedDrive(
    val uuid: String,
    val label: String,
    val mountPoint: String,
    val totalSpace: Long = 0,
    val freeSpace: Long = 0,
    val storageClass: StorageClass = StorageClass.WARM
)
