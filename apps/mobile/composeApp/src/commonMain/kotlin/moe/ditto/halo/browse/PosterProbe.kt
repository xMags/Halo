package moe.ditto.halo.browse

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Finds which search results' posters load.
 *
 * An addon's answer does not say so: Cinemeta gives every title a poster
 * link, and for the obscure ones (fan films, announced sequels) that link is
 * simply dead. A card without art is the surest sign of a result nobody was
 * looking for, so Search asks about the first results before it reveals them
 * and ranks those without art lower (SearchBoard.kt). The desktop does the
 * same in `search/posterChecks.ts`.
 *
 * Only headers are asked for, so a search costs a few dozen small requests
 * and no image is downloaded twice.
 */
internal class PosterProbe(private val http: HttpClient) {
    /** One answer per image link for the life of the session. */
    private val known = mutableMapOf<String, Boolean>()
    private val lock = Mutex()
    private val inFlight = Semaphore(MaxAtOnce)

    /**
     * Whether one poster link leads to an image: true or false once asked,
     * null while that could not be found out (which the search treats as a
     * poster that loads). Answers are remembered for the session.
     */
    suspend fun linkLoads(url: String): Boolean? {
        lock.withLock { known[url] }?.let { return it }
        val answer = inFlight.withPermit { ask(url) } ?: return null
        lock.withLock { known[url] = answer }
        return answer
    }

    /**
     * Whether [url] serves an image, or null when that could not be found out.
     * Unknown counts as loading and is not remembered: a dropped connection
     * must not empty a search.
     */
    private suspend fun ask(url: String): Boolean? = try {
        val headers = http.head(url)
        // Some image hosts refuse HEAD; the answer then comes from a GET's headers.
        val response = if (headers.status in HeadRefused) http.get(url) else headers
        response.isImage()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }

    private fun HttpResponse.isImage(): Boolean =
        status.isSuccess() && headers[HttpHeaders.ContentType]?.startsWith("image/") != false

    private companion object {
        const val MaxAtOnce = 8
        val HeadRefused = setOf(HttpStatusCode.MethodNotAllowed, HttpStatusCode.NotImplemented, HttpStatusCode.Forbidden)
    }
}
