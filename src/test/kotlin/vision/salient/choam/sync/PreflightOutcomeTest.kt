package vision.salient.choam.sync

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for the PreflightOutcome sealed class (Phase 9.7 Step 0).
 *
 * Validates:
 * - Resolved wraps SendPreflightResult correctly
 * - FallThrough is a singleton
 * - UnsafeAbort carries a reason string
 * - Pattern matching covers all cases
 */
class PreflightOutcomeTest {

    @Test
    fun `Resolved wraps SendPreflightResult with entries`() {
        val result = SendPreflightResult(
            entries = listOf(
                SendManifestEntry(
                    localPath = "/tmp/test.txt",
                    remotePath = "/dest/test.txt",
                    localSize = 1024,
                    remoteSize = 0,
                    localChecksum = null,
                    remoteChecksum = null,
                    status = SendFileStatus.NEW,
                    isDatabase = false
                )
            )
        )
        val outcome: PreflightOutcome = PreflightOutcome.Resolved(result)
        assertIs<PreflightOutcome.Resolved>(outcome)
        assertEquals(1, outcome.result.newFiles.size)
        assertTrue(outcome.result.safeToSend)
    }

    @Test
    fun `FallThrough is a singleton object`() {
        val outcome: PreflightOutcome = PreflightOutcome.FallThrough
        assertIs<PreflightOutcome.FallThrough>(outcome)
    }

    @Test
    fun `UnsafeAbort carries reason string`() {
        val outcome: PreflightOutcome = PreflightOutcome.UnsafeAbort("Stale manifest: file count mismatch")
        assertIs<PreflightOutcome.UnsafeAbort>(outcome)
        assertEquals("Stale manifest: file count mismatch", outcome.reason)
    }

    @Test
    fun `when expression covers all three cases`() {
        val outcomes = listOf(
            PreflightOutcome.Resolved(SendPreflightResult(emptyList())),
            PreflightOutcome.FallThrough,
            PreflightOutcome.UnsafeAbort("test reason")
        )

        val labels = outcomes.map { outcome ->
            when (outcome) {
                is PreflightOutcome.Resolved -> "resolved"
                is PreflightOutcome.FallThrough -> "fallthrough"
                is PreflightOutcome.UnsafeAbort -> "abort:${outcome.reason}"
            }
        }

        assertEquals(listOf("resolved", "fallthrough", "abort:test reason"), labels)
    }

    @Test
    fun `Resolved with conflicts has hasConflicts true`() {
        val result = SendPreflightResult(
            entries = listOf(
                SendManifestEntry("/tmp/a.txt", "/dest/a.txt", 100, 200, null, null, SendFileStatus.CONFLICT, false)
            )
        )
        val outcome = PreflightOutcome.Resolved(result)
        assertIs<PreflightOutcome.Resolved>(outcome)
        assertTrue(outcome.result.hasConflicts)
    }

    @Test
    fun `Resolved with all identical is safe to send`() {
        val result = SendPreflightResult(
            entries = listOf(
                SendManifestEntry("/tmp/a.txt", "/dest/a.txt", 100, 100, "abc", "abc", SendFileStatus.IDENTICAL, false)
            )
        )
        val outcome = PreflightOutcome.Resolved(result)
        assertIs<PreflightOutcome.Resolved>(outcome)
        assertTrue(outcome.result.safeToSend)
        assertEquals(0, outcome.result.newFiles.size)
        assertEquals(1, outcome.result.identicalFiles.size)
    }

    @Test
    fun `UnsafeAbort with different reasons are not equal`() {
        val abort1 = PreflightOutcome.UnsafeAbort("reason A")
        val abort2 = PreflightOutcome.UnsafeAbort("reason B")
        assertTrue(abort1 != abort2)
    }

    @Test
    fun `UnsafeAbort with same reason are equal`() {
        val abort1 = PreflightOutcome.UnsafeAbort("same reason")
        val abort2 = PreflightOutcome.UnsafeAbort("same reason")
        assertEquals(abort1, abort2)
    }
}
