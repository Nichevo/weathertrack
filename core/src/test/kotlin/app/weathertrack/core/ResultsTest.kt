package app.weathertrack.core

import app.weathertrack.core.util.runCatchingCancellable
import java.util.concurrent.CancellationException
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class ResultsTest {
    @Test fun successPassesThrough() {
        assertEquals(3, runCatchingCancellable { 1 + 2 }.getOrThrow())
    }

    @Test fun ordinaryErrorsBecomeFailures() {
        val r = runCatchingCancellable { error("boom") }
        assertTrue(r.isFailure)
        assertEquals("boom", r.exceptionOrNull()?.message)
    }

    @Test fun cancellationIsRethrown() {
        val thrown = try {
            runCatchingCancellable { throw CancellationException("StandaloneCoroutine was cancelled") }
            null
        } catch (e: CancellationException) {
            e
        }
        assertTrue(thrown != null)
    }
}
