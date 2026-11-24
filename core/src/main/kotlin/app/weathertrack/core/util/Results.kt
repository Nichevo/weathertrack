package app.weathertrack.core.util

import java.util.concurrent.CancellationException

/**
 * Like [runCatching], but lets coroutine cancellation through instead of turning it into a failure.
 *
 * A cancelled job (for example, an older map download replaced when the view moves) must stop quietly.
 * Plain [runCatching] catches the CancellationException and the screen then shows
 * "StandaloneCoroutine was cancelled" as if it were an error.
 */
inline fun <R> runCatchingCancellable(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
