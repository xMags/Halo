package moe.ditto.halo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import okio.Path.Companion.toPath

/**
 * The app's image loader. Poster and backdrop art comes from addon-supplied
 * public CDN URLs, so this deliberately lets the Ktor fetcher build its own
 * HTTP client rather than sharing the authenticated Halo API client — a session
 * token must never ride along to a third-party CDN.
 *
 * Caches are sized explicitly instead of using a percentage of app memory: the
 * budget is easier to reason about when the payload is known. A poster at the
 * largest layout size renders around 500x750 px, i.e. ~1.5 MB decoded, so the
 * memory cache holds roughly forty of them and a catalog screen's worth of
 * scrollback survives a tab switch.
 */
internal fun haloImageLoader(context: PlatformContext, cacheDirectory: String): ImageLoader =
    ImageLoader.Builder(context)
        .components { add(KtorNetworkFetcherFactory()) }
        .memoryCache {
            MemoryCache.Builder()
                .maxSizeBytes(64L * 1024 * 1024)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDirectory.toPath())
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .build()

/**
 * Remote art with a neutral surface tile behind it, so a missing, loading, or
 * failed image reads as an empty poster slot rather than a hole in the layout.
 * Callers own the shape: clip in the passed [modifier] and the fill clips too.
 *
 * No crossfade, matching the shipping client — art pops in against the tile.
 */
@Composable
fun HaloAsyncImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    Box(modifier.background(HaloColors.Surface)) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
    }
}
