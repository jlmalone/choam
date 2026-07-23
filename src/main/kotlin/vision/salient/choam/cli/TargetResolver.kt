package vision.salient.choam.cli

import mu.KotlinLogging
import vision.salient.choam.config.ChoamConfig
import vision.salient.choam.config.MachineProfile

private val logger = KotlinLogging.logger {}

enum class Direction { PUSH, PULL }

data class ResolvedTarget(
    val repos: List<String>,
    val localMachine: MachineProfile,
    val remoteMachine: MachineProfile
)

class TargetResolver(private val config: ChoamConfig) {

    fun findLocalMachine(): MachineProfile? {
        // If a machine named "local" exists in config, it IS this machine by definition.
        // This avoids fragile hostname matching that breaks when the network changes.
        config.machines["local"]?.let { return it }

        // Fallback 1: match by hostname
        val hostname = try {
            java.net.InetAddress.getLocalHost().hostName
        } catch (e: Exception) {
            logger.warn { "Cannot determine hostname: ${e.message}" }
            null
        }

        if (hostname != null) {
            val byHostname = config.machines.entries
                .find { it.value.hostname == hostname || it.value.hostname.startsWith(hostname) }
            if (byHostname != null) return byHostname.value

            // Fallback 1b: match by aliases (handles renames, "local" alias, etc.)
            val byAlias = config.machines.entries
                .find { it.value.aliases?.any { alias -> alias.equals(hostname, ignoreCase = true) } == true }
            if (byAlias != null) return byAlias.value
        }

        // Fallback 2: match by Tailscale IP (stable across network changes)
        val localIps = try {
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .map { it.hostAddress }
                .toSet()
        } catch (e: Exception) {
            logger.warn { "Cannot enumerate network interfaces: ${e.message}" }
            emptySet()
        }

        if (localIps.isNotEmpty()) {
            val byTailscale = config.machines.entries
                .find { it.value.tailscaleIp != null && it.value.tailscaleIp in localIps }
            if (byTailscale != null) return byTailscale.value
        }

        return null
    }

    fun resolveRepositories(target: String): List<String> {
        if (target == "all") {
            return config.repositories.keys.toList()
        }

        // Exact repo name match
        if (config.repositories.containsKey(target)) {
            return listOf(target)
        }

        // Directory path prefix match against local machine's repos
        val localMachine = findLocalMachine()
        if (localMachine != null) {
            val matchingRepo = localMachine.repositories.entries
                .find { (_, path) ->
                    target.startsWith(path) || path.startsWith(target)
                }
            if (matchingRepo != null) {
                return listOf(matchingRepo.key)
            }
        }

        return emptyList()
    }

    fun findRemoteMachines(repos: List<String>, localMachine: MachineProfile): List<MachineProfile> {
        return config.machines.values
            .filter { it.name != localMachine.name }
            .filter { machine ->
                repos.any { repo -> machine.repositories.containsKey(repo) }
            }
    }

    fun resolve(
        target: String,
        explicitMachine: String?,
        direction: Direction
    ): Pair<ResolvedTarget?, String?> {
        val localMachine = findLocalMachine()
            ?: return null to "Cannot determine local machine. Check config hostname matches this machine."

        val repos = resolveRepositories(target)
        if (repos.isEmpty()) {
            return null to "Unknown target '$target'. Not a repository name or matching path."
        }

        // Verify local machine has these repos
        val localHasRepos = repos.all { localMachine.repositories.containsKey(it) }
        if (!localHasRepos) {
            val missing = repos.filter { !localMachine.repositories.containsKey(it) }
            return null to "Local machine '${localMachine.name}' doesn't have repositories: ${missing.joinToString()}"
        }

        val remoteMachine = if (explicitMachine != null) {
            config.machines[explicitMachine]
                ?: return null to "Unknown machine '$explicitMachine'. Available: ${config.machines.keys.joinToString()}"
        } else {
            val remotes = findRemoteMachines(repos, localMachine)
            when {
                remotes.isEmpty() -> return null to "No remote machines have ${repos.joinToString()}."
                remotes.size == 1 -> remotes.first()
                else -> return null to "Multiple machines have ${repos.joinToString()}: " +
                    "${remotes.map { it.name }.joinToString()}. Use --${if (direction == Direction.PUSH) "to" else "from"} to specify."
            }
        }

        // Verify remote has repos
        val remoteHasRepos = repos.all { remoteMachine.repositories.containsKey(it) }
        if (!remoteHasRepos) {
            val missing = repos.filter { !remoteMachine.repositories.containsKey(it) }
            return null to "Remote machine '${remoteMachine.name}' doesn't have repositories: ${missing.joinToString()}"
        }

        return ResolvedTarget(
            repos = repos,
            localMachine = localMachine,
            remoteMachine = remoteMachine
        ) to null
    }
}
