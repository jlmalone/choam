package vision.salient.choam.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.json.Json

object ChoamConfigLoader {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun defaultPath(): Path =
        Paths.get(System.getProperty("user.home"), ".choam", "config.json")

    fun load(path: Path = defaultPath()): ChoamConfig {
        if (!Files.exists(path)) {
            throw IllegalStateException("CHOAM config not found at $path")
        }
        Files.newBufferedReader(path).use { reader ->
            return json.decodeFromString(ChoamConfig.serializer(), reader.readText())
        }
    }

    fun save(config: ChoamConfig, path: Path = defaultPath()) {
        Files.createDirectories(path.parent)
        val serialized = json.encodeToString(ChoamConfig.serializer(), config)
        Files.newBufferedWriter(path).use { writer ->
            writer.write(serialized)
        }
    }
}

