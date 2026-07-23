package vision.salient.choam.dag

import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.EdECPrivateKeySpec
import java.security.spec.EdECPublicKeySpec
import java.security.spec.NamedParameterSpec

private val logger = KotlinLogging.logger {}

/**
 * Ed25519 cryptographic operations for the CHOAM DAG.
 *
 * Uses JDK 21+ built-in EdDSA (JEP 339). No external dependencies.
 * All keys and signatures represented as lowercase hex strings.
 */
object DagCrypto {

    private const val ALGORITHM = "Ed25519"

    /**
     * Generate a new Ed25519 keypair.
     * @return Pair(publicKeyHex, privateKeyHex) — both 64 lowercase hex chars (32 bytes)
     */
    fun generateKeyPair(): Pair<String, String> {
        val kpg = KeyPairGenerator.getInstance(ALGORITHM)
        val keyPair = kpg.generateKeyPair()

        val pubBytes = keyPair.public.encoded
        val privBytes = keyPair.private.encoded

        // JDK EdDSA keys use ASN.1 encoding — extract raw 32-byte keys
        val rawPub = extractRawPublicKey(pubBytes)
        val rawPriv = extractRawPrivateKey(privBytes)

        return Pair(bytesToHex(rawPub), bytesToHex(rawPriv))
    }

    /**
     * Derive House ID from public key — first 32 chars of the hex public key.
     */
    fun deriveHouseId(publicKeyHex: String): String {
        return publicKeyHex.take(32)
    }

    /**
     * Sign data with Ed25519 private key.
     * @param data The canonical JSON string to sign
     * @param privateKeyHex 64 lowercase hex chars (32 bytes)
     * @return Signature as 128 lowercase hex chars (64 bytes)
     */
    fun sign(data: String, privateKeyHex: String): String {
        val privBytes = hexToBytes(privateKeyHex)
        val spec = EdECPrivateKeySpec(NamedParameterSpec.ED25519, privBytes)
        val keyFactory = KeyFactory.getInstance(ALGORITHM)
        val privateKey = keyFactory.generatePrivate(spec)

        val signer = Signature.getInstance(ALGORITHM)
        signer.initSign(privateKey)
        signer.update(data.toByteArray(Charsets.UTF_8))
        val sigBytes = signer.sign()
        return bytesToHex(sigBytes)
    }

    /**
     * Verify an Ed25519 signature.
     * @return true if signature is valid
     */
    fun verify(data: String, signature: String, publicKeyHex: String): Boolean {
        return try {
            val pubBytes = hexToBytes(publicKeyHex)

            // Reconstruct EdECPublicKeySpec from raw bytes
            val point = rawBytesToEdECPoint(pubBytes)
            val spec = EdECPublicKeySpec(NamedParameterSpec.ED25519, point)
            val keyFactory = KeyFactory.getInstance(ALGORITHM)
            val publicKey = keyFactory.generatePublic(spec)

            val verifier = Signature.getInstance(ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(data.toByteArray(Charsets.UTF_8))
            verifier.verify(hexToBytes(signature))
        } catch (e: Exception) {
            logger.debug { "Signature verification failed: ${e.message}" }
            false
        }
    }

    /**
     * Save private key to file with owner-only permissions.
     */
    fun savePrivateKey(privateKeyHex: String, path: Path) {
        Files.createDirectories(path.parent)
        Files.writeString(path, privateKeyHex)
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
            // Windows — best effort
            path.toFile().setReadable(false, false)
            path.toFile().setReadable(true, true)
            path.toFile().setWritable(false, false)
            path.toFile().setWritable(true, true)
        }
    }

    /**
     * Load private key from file.
     */
    fun loadPrivateKey(path: Path): String {
        return Files.readString(path).trim()
    }

    // --- Internal helpers ---

    internal fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    internal fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.lowercase().trim()
        return ByteArray(cleanHex.length / 2) { i ->
            cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * Extract raw 32-byte public key from JDK's ASN.1-encoded public key.
     * JDK Ed25519 public key encoding: 12 bytes ASN.1 header + 32 bytes raw key.
     */
    private fun extractRawPublicKey(encoded: ByteArray): ByteArray {
        // Ed25519 public key in X.509 encoding is 44 bytes: 12 header + 32 key
        return if (encoded.size == 44) {
            encoded.copyOfRange(12, 44)
        } else {
            encoded // Already raw
        }
    }

    /**
     * Extract raw 32-byte private key from JDK's PKCS#8-encoded private key.
     * JDK Ed25519 private key encoding: 16 bytes ASN.1 header + 32 bytes raw key.
     */
    private fun extractRawPrivateKey(encoded: ByteArray): ByteArray {
        // Ed25519 private key in PKCS#8 is 48 bytes: 16 header + 32 key
        return if (encoded.size == 48) {
            encoded.copyOfRange(16, 48)
        } else if (encoded.size > 32) {
            // Some JDK versions use different header sizes — find the last 32 bytes
            encoded.copyOfRange(encoded.size - 32, encoded.size)
        } else {
            encoded
        }
    }

    /**
     * Convert raw 32-byte public key to EdECPoint for JDK API.
     */
    private fun rawBytesToEdECPoint(rawPubKey: ByteArray): java.security.spec.EdECPoint {
        // Ed25519 uses little-endian encoding. The high bit of the last byte is the sign.
        val lastByte = rawPubKey[31]
        val isOdd = (lastByte.toInt() and 0x80) != 0

        // Clear the sign bit to get the y-coordinate
        val yBytes = rawPubKey.copyOf()
        yBytes[31] = (yBytes[31].toInt() and 0x7f).toByte()

        // Reverse to big-endian for BigInteger
        val yBigEndian = yBytes.reversedArray()
        val y = java.math.BigInteger(1, yBigEndian)

        return java.security.spec.EdECPoint(isOdd, y)
    }
}
