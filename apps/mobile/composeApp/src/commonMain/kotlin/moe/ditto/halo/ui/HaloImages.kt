package moe.ditto.halo.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
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
 * Remote art that loads behind a skeleton and crossfades in.
 *
 * The image sits underneath at full opacity and the skeleton fades out over it,
 * rather than the image fading in over the skeleton. Fading both at once would
 * leave a window where neither is opaque and the page background shows through
 * the middle of a poster.
 *
 * The fade is driven here rather than by Coil's own crossfade transition,
 * because the two would compose into that same muddy double-fade. Loading from
 * the memory cache resolves before the first frame, so a warm poster settles at
 * a fully faded placeholder and never flashes a skeleton.
 *
 * A failed load settles the same way a successful one does, landing on the bare
 * surface tile: a missing poster reads as an empty slot in the grid rather than
 * a frame that shimmers forever.
 *
 * Callers own the shape — clip in the passed [modifier] and the fill clips too.
 */
@Composable
fun HaloAsyncImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    /** Which part of the art a crop keeps; a title's backdrop favours its upper part. */
    alignment: Alignment = Alignment.Center,
    placeholder: (@Composable BoxScope.() -> Unit)? = null,
    /**
     * Loaded when [url] is missing or fails, before the placeholder settles in.
     * Addons hand out poster links that are dead (a stale Amazon image) or
     * mangled at the source, and a title they name by IMDb id has a poster of
     * its own to fall back on.
     */
    fallbackUrl: String? = null,
) {
    val primary = url?.takeIf { it.isNotBlank() }
    val secondary = fallbackUrl?.takeIf { it.isNotBlank() && it != primary }
    var onFallback by remember(primary, secondary) { mutableStateOf(primary == null) }
    val model = if (onFallback) secondary else primary
    if (model == null) {
        Box(modifier.background(HaloColors.Surface)) {
            placeholder?.invoke(this)
        }
        return
    }

    val painter = rememberAsyncImagePainter(model = model, contentScale = contentScale)
    val state by painter.state.collectAsState()
    val failed = state is AsyncImagePainter.State.Error
    // A failure with a fallback still to try is not the end of the load: the
    // skeleton stays up while the second source is asked for.
    LaunchedEffect(failed) {
        if (failed && !onFallback && secondary != null) onFallback = true
    }
    val settled = state is AsyncImagePainter.State.Success || (failed && (onFallback || secondary == null))
    val placeholderAlpha by animateFloatAsState(
        targetValue = if (settled) 0f else 1f,
        animationSpec = tween(durationMillis = 240),
        label = "art-crossfade",
    )

    Box(modifier.background(HaloColors.Surface)) {
        Image(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
            alignment = alignment,
        )
        // Kept composed through the whole fade: dropping it the moment the load
        // succeeds would swap the skeleton out in one frame, with nothing left
        // to animate.
        if (placeholderAlpha > 0f) {
            Box(Modifier.matchParentSize().alpha(placeholderAlpha)) {
                if (placeholder == null) {
                    HaloSkeleton(Modifier.matchParentSize())
                } else {
                    placeholder()
                }
            }
        }
    }
}
