package vision.salient.choam.sync

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

@Serializable
data class SyncHistoryEntry(
    val id: String,
    val repositories: List<String>,
    val sourceMachine: String,
    val targetMachine: String,
    val startTime: String, // ISO-8601
    val endTime: String, // ISO-8601
    val status: String,
    val filesTransferred: Long,
    val bytesTransferred: Long,
    val errors: Int
)

class SyncHistoryStore(
    private val historyPath: Path = Paths.get(
        System.getProperty("user.home"), ".choam", "sync_history.jsonl"
    )
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun record(session: SyncSession) {
        val entry = SyncHistoryEntry(
            id = session.id,
            repositories = session.repositories,
            sourceMachine = session.sourceMachine,
            targetMachine = session.targetMachine,
            startTime = session.startTime.toString(),
            endTime = (session.endTime ?: Instant.now()).toString(),
            status = session.status.name,
            filesTransferred = session.statistics.filesTransferred,
            bytesTransferred = session.statistics.bytesTransferred,
            errors = session.statistics.errors
        )

        try {
            Files.createDirectories(historyPath.parent)
            val line = json.encodeToString(SyncHistoryEntry.serializer(), entry) + "\n"
            Files.writeString(
                historyPath, line,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND
            )
            logger.info { "Recorded sync history entry ${entry.id}" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to write sync history: ${e.message}" }
        }
    }

    fun loadAll(): List<SyncHistoryEntry> {
        if (!Files.exists(historyPath)) return emptyList()

        return try {
            Files.readAllLines(historyPath)
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try {
                        json.decodeFromString(SyncHistoryEntry.serializer(), line)
                    } catch (e: Exception) {
                        logger.warn { "Skipping malformed history line: ${e.message}" }
                        null
                    }
                }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load sync history: ${e.message}" }
            emptyList()
        }
    }

    fun query(repo: String? = null, last: Int? = null): List<SyncHistoryEntry> {
        var entries = loadAll()
        if (repo != null) {
            entries = entries.filter { it.repositories.contains(repo) }
        }
        if (last != null && last > 0) {
            entries = entries.takeLast(last)
        }
        return entries
    }

    fun lastSyncFor(repo: String, source: String, target: String): SyncHistoryEntry? {
        return loadAll()
            .filter { it.repositories.contains(repo) }
            .filter { it.sourceMachine == source && it.targetMachine == target }
            .lastOrNull()
    }

    fun lastSyncForRepo(repo: String): SyncHistoryEntry? {
        return loadAll()
            .filter { it.repositories.contains(repo) }
            .lastOrNull()
    }
}
