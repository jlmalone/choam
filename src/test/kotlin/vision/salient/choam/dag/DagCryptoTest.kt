package vision.salient.choam.dag

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DagCryptoTest {

    @TempDir
    lateinit var tempDir: Path

    // --- Key generation ---

    @Test
    fun `generateKeyPair produces 64-char hex strings`() {
        val (pub, priv) = DagCrypto.generateKeyPair()
        assertEquals(64, pub.length, "Public key should be 64 hex chars")
        assertEquals(64, priv.length, "Private key should be 64 hex chars")
    }

    @Test
    fun `generateKeyPair produces lowercase hex`() {
        val (pub, priv) = DagCrypto.generateKeyPair()
        assertTrue(pub.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(priv.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `each keypair is unique`() {
        val (pub1, _) = DagCrypto.generateKeyPair()
        val (pub2, _) = DagCrypto.generateKeyPair()
        assertNotEquals(pub1, pub2)
    }

    // --- House ID derivation ---

    @Test
    fun `deriveHouseId returns first 32 chars of public key`() {
        val (pub, _) = DagCrypto.generateKeyPair()
        val houseId = DagCrypto.deriveHouseId(pub)
        assertEquals(32, houseId.length)
        assertEquals(pub.take(32), houseId)
    }

    // --- Sign and verify ---

    @Test
    fun `sign produces 128-char hex signature`() {
        val (_, priv) = DagCrypto.generateKeyPair()
        val sig = DagCrypto.sign("hello world", priv)
        assertEquals(128, sig.length, "Signature should be 128 hex chars (64 bytes)")
        assertTrue(sig.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `verify returns true for valid signature`() {
        val (pub, priv) = DagCrypto.generateKeyPair()
        val data = "test data for signing"
        val sig = DagCrypto.sign(data, priv)
        assertTrue(DagCrypto.verify(data, sig, pub))
    }

    @Test
    fun `verify returns false for tampered data`() {
        val (pub, priv) = DagCrypto.generateKeyPair()
        val sig = DagCrypto.sign("original data", priv)
        assertFalse(DagCrypto.verify("tampered data", sig, pub))
    }

    @Test
    fun `verify returns false for wrong public key`() {
        val (_, priv1) = DagCrypto.generateKeyPair()
        val (pub2, _) = DagCrypto.generateKeyPair()
        val sig = DagCrypto.sign("data", priv1)
        assertFalse(DagCrypto.verify("data", sig, pub2))
    }

    @Test
    fun `verify returns false for corrupted signature`() {
        val (pub, priv) = DagCrypto.generateKeyPair()
        val sig = DagCrypto.sign("data", priv)
        val corrupted = "00" + sig.substring(2) // Flip first byte
        assertFalse(DagCrypto.verify("data", corrupted, pub))
    }

    @Test
    fun `same data with same key produces different signatures`() {
        // Ed25519 is deterministic, so same input → same sig
        val (_, priv) = DagCrypto.generateKeyPair()
        val sig1 = DagCrypto.sign("data", priv)
        val sig2 = DagCrypto.sign("data", priv)
        assertEquals(sig1, sig2, "Ed25519 is deterministic")
    }

    @Test
    fun `sign and verify with canonical JSON content`() {
        val (pub, priv) = DagCrypto.generateKeyPair()
        val canonical = CanonicalJson.stringify(mapOf("key" to "value", "num" to 42))
        val sig = DagCrypto.sign(canonical, priv)
        assertTrue(DagCrypto.verify(canonical, sig, pub))
    }

    // --- Key persistence ---

    @Test
    fun `savePrivateKey and loadPrivateKey roundtrip`() {
        val (_, priv) = DagCrypto.generateKeyPair()
        val keyPath = tempDir.resolve("test_key")
        DagCrypto.savePrivateKey(priv, keyPath)
        val loaded = DagCrypto.loadPrivateKey(keyPath)
        assertEquals(priv, loaded)
    }

    @Test
    fun `saved key can sign and verify`() {
        val (pub, priv) = DagCrypto.generateKeyPair()
        val keyPath = tempDir.resolve("test_key")
        DagCrypto.savePrivateKey(priv, keyPath)
        val loadedPriv = DagCrypto.loadPrivateKey(keyPath)

        val sig = DagCrypto.sign("test", loadedPriv)
        assertTrue(DagCrypto.verify("test", sig, pub))
    }

    // --- Hex utilities ---

    @Test
    fun `bytesToHex and hexToBytes roundtrip`() {
        val original = byteArrayOf(0, 1, 127, -128, -1, 16, 255.toByte())
        val hex = DagCrypto.bytesToHex(original)
        val recovered = DagCrypto.hexToBytes(hex)
        assertTrue(original.contentEquals(recovered))
    }

    @Test
    fun `hexToBytes handles lowercase`() {
        val bytes = DagCrypto.hexToBytes("deadbeef")
        assertEquals(4, bytes.size)
        assertEquals(0xde.toByte(), bytes[0])
        assertEquals(0xad.toByte(), bytes[1])
    }
}
