package vision.salient.choam.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

@Serializable
data class DriveState(
    val label: String,
    val lastScanTimestamp: String, // ISO-8601 instant
    val lastScanFileCount: Long = 0,
    val lastScanNewFiles: Long = 0
)

@Serializable
data class CatalogState(
    val drives: MutableMap<String, DriveState> = mutableMapOf()
) {
    companion object {
        val DEFAULT_PATH: File = File(System.getProperty("user.home"), ".choam/catalog_state.json")

        fun load(file: File = DEFAULT_PATH): CatalogState {
            if (!file.exists()) return CatalogState()
            return try {
                json.decodeFromString(serializer(), file.readText())
            } catch (e: Exception) {
                System.err.println("Warning: could not parse ${file.absolutePath}: ${e.message}")
                CatalogState()
            }
        }

        fun save(state: CatalogState, file: File = DEFAULT_PATH) {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(serializer(), state))
        }
    }

    fun getLastScanInstant(driveKey: String): Instant? {
        val ts = drives[driveKey]?.lastScanTimestamp ?: return null
        return try {
            Instant.parse(ts)
        } catch (e: Exception) {
            null
        }
    }

    fun updateDrive(driveKey: String, label: String, fileCount: Long, newFiles: Long) {
        drives[driveKey] = DriveState(
            label = label,
            lastScanTimestamp = Instant.now().toString(),
            lastScanFileCount = fileCount,
            lastScanNewFiles = newFiles
        )
    }
}
