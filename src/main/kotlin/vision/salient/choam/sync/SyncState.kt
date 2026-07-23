package vision.salient.choam.sync

import java.time.Instant
import java.util.UUID

data class SyncSession(
    val id: String = UUID.randomUUID().toString(),
    val sourceMachine: String,
    val targetMachine: String,
    val repositories: List<String>,
    val startTime: Instant,
    val endTime: Instant? = null,
    val status: SyncStatus,
    val statistics: SyncStatistics = SyncStatistics()
)

enum class SyncStatus {
    PREPARING,
    CATALOGING,
    COMPARING,
    TRANSFERRING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class SyncStatistics(
    val filesScanned: Long = 0,
    val filesTransferred: Long = 0,
    val bytesTransferred: Long = 0,
    val filesSkipped: Long = 0,
    val conflicts: Int = 0,
    val errors: Int = 0
)

data class FileManifest(
    val path: String,
    val size: Long,
    val modifiedTime: Instant,
    val checksum: String? = null,
    val exists: Boolean = true
)
