package vision.salient.choam.dag

import java.security.MessageDigest

/**
 * Canonical JSON serialization for deterministic hashing.
 *
 * Follows REDO protocol rules:
 * - All object keys sorted alphabetically (recursive)
 * - No whitespace (compact)
 * - Null values omitted
 * - Empty strings preserved
 * - Numbers without trailing zeros or scientific notation
 * - Standard JSON string escaping
 *
 * Critical: identical content on any machine must produce identical JSON.
 */
object CanonicalJson {

    /**
     * Serialize any value to canonical JSON string.
     */
    fun stringify(value: Any?): String {
        return when (value) {
            null -> return "" // Nulls omitted (caller should filter)
            is String -> escapeString(value)
            is Boolean -> if (value) "true" else "false"
            is Int -> value.toString()
            is Long -> value.toString()
            is Double -> formatNumber(value)
            is Float -> formatNumber(value.toDouble())
            is List<*> -> {
                val elements = value.mapNotNull { item ->
                    if (item == null) "null" else stringify(item)
                }
                "[${elements.joinToString(",")}]"
            }
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = value as Map<String, Any?>
                val entries = map.entries
                    .filter { it.value != null } // Omit null values
                    .sortedBy { it.key }         // Sort keys alphabetically
                    .map { (k, v) -> "${escapeString(k)}:${stringify(v)}" }
                "{${entries.joinToString(",")}}"
            }
            else -> escapeString(value.toString())
        }
    }

    /**
     * Compute SHA-256 hash of a DagEvent's canonical JSON.
     * Excludes `id` and `signature` fields (they're derived values).
     */
    fun hashEvent(event: DagEvent): String {
        val forHashing = buildHashableMap(event)
        val canonicalJson = stringify(forHashing)
        return sha256(canonicalJson)
    }

    /**
     * SHA-256 of a string, returned as "sha256:<64 lowercase hex chars>".
     */
    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        val hex = hash.joinToString("") { "%02x".format(it) }
        return "sha256:$hex"
    }

    /**
     * Build the map used for hashing — excludes id and signature.
     */
    internal fun buildHashableMap(event: DagEvent): Map<String, Any?> {
        return mapOf(
            "author" to mapOf(
                "houseId" to event.author.houseId,
                "machineId" to event.author.machineId,
                "publicKey" to event.author.publicKey
            ),
            "parents" to event.parents,
            "payload" to event.payload,
            "timestamp" to mapOf(
                "lamport" to event.timestamp.lamport,
                "wall" to event.timestamp.wall
            ),
            "type" to event.type,
            "version" to event.version
        )
    }

    private fun escapeString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u%04x".format(c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    private fun formatNumber(d: Double): String {
        if (d == d.toLong().toDouble()) {
            return d.toLong().toString() // No trailing .0
        }
        return d.toBigDecimal().toPlainString() // No scientific notation
    }
}
