package vision.salient.choam

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessUtilTest {

    @Test
    @Timeout(20)
    fun `runBounded captures output and exit code for a fast command`() {
        val res = runBounded(listOf("sh", "-c", "echo hello"), timeoutSeconds = 10)
        assertFalse(res.timedOut)
        assertEquals(0, res.exitCode)
        assertTrue(res.output.contains("hello"))
        assertTrue(res.ok)
    }

    @Test
    @Timeout(20)
    fun `runBounded reports a non-zero exit code without timing out`() {
        val res = runBounded(listOf("sh", "-c", "exit 7"), timeoutSeconds = 10)
        assertFalse(res.timedOut)
        assertEquals(7, res.exitCode)
        assertFalse(res.ok)
    }

    @Test
    @Timeout(20)
    fun `runBounded force-kills a command that exceeds its timeout and returns promptly`() {
        // This is the whole point: a child that connects/starts but never finishes (a stalled
        // SSH session over a saturated relay) must NOT block the caller forever.
        val start = System.currentTimeMillis()
        val res = runBounded(listOf("sh", "-c", "sleep 30"), timeoutSeconds = 1)
        val elapsedMs = System.currentTimeMillis() - start
        assertTrue(res.timedOut, "expected timedOut=true for an over-budget command")
        assertFalse(res.ok)
        assertTrue(elapsedMs < 15_000, "runBounded should return shortly after the timeout, took ${elapsedMs}ms")
    }

    @Test
    @Timeout(20)
    fun `runBounded feeds stdin to the child`() {
        val res = runBounded(listOf("cat"), stdin = "piped-input", timeoutSeconds = 10)
        assertFalse(res.timedOut)
        assertEquals(0, res.exitCode)
        assertTrue(res.output.contains("piped-input"))
    }
}
