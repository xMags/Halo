package moe.ditto.halo.browse

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import moe.ditto.halo.api.MetaCard
import moe.ditto.halo.ui.metahubPosterUrl

/**
 * Finds which search results have no artwork that loads.
 *
 * An addon's answer does not say so: Cinemeta gives every title a poster
 * link, and for the obscure ones (fan films, announced sequels) that link is
 * simply dead. A card without art is the surest sign of a result nobody was
 * looking for, so a search orders those last or leaves them out
 * ([refineResults]), which needs the answer for every result and not only for
 * the cards scrolled into view. The desktop does the same in
 * `search/missingArt.ts`.
 *
 * Only headers are asked for, so a search costs a few dozen small requests
 * and no image is downloaded twice.
 */
internal class PosterProbe(private val http: HttpClient) {
    /** One answer per image link for the life of the session. */
    private val known = mutableMapOf<String, Boolean>()
    private val lock = Mutex()
    private val inFlight = Semaphore(MaxAtOnce)

    /** The keys ([titleKey]) of the titles in [metas] none of whose artwork loads. */
    suspend fun missing(metas: List<MetaCard>): Set<String> = coroutineScope {
        metas
            .map { meta -> async { if (hasArtwork(meta)) null else titleKey(meta) } }
            .awaitAll()
            .filterNotNull()
            .toSet()
    }

    /** The sources a card tries, in its order: the addon's poster, then Stremio's own for an IMDb title. */
    private suspend fun hasArtwork(meta: MetaCard): Boolean {
        val sources = listOfNotNull(meta.poster?.takeUnless { hasNoPoster(meta) }, metahubPosterUrl(meta.id)).distinct()
        return sources.any { loads(it) }
    }

    private suspend fun loads(url: String): Boolean {
        lock.withLock { known[url] }?.let { return it }
        val answer = inFlight.withPermit { ask(url) } ?: return true
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
