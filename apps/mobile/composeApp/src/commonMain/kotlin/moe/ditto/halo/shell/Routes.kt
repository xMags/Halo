package moe.ditto.halo.shell

import kotlinx.serialization.Serializable
import moe.ditto.halo.api.AddonSource
import moe.ditto.halo.api.Stream
import moe.ditto.halo.downloads.DownloadEntry
import moe.ditto.halo.downloads.DownloadFiles
import moe.ditto.halo.downloads.DownloadMedia
import moe.ditto.halo.downloads.sourceFingerprint
import moe.ditto.halo.screens.player.EpisodeChoice
import moe.ditto.halo.screens.player.PlaybackContext

/**
 * The shell's destinations, declared as types rather than route strings so that
 * navigating to one is checked by the compiler and resolvable by the IDE. A
 * typo in a string route is a runtime crash; a typo here does not build.
 *
 * Screens that cover the tab bar entirely, including item detail, the stream
 * picker and the player, belong beside these rather than inside a tab, matching how the
 * shipping client pushes them above its tab navigator.
 */
@Serializable
data object HomeRoute

@Serializable
data object LibraryRoute

@Serializable
data object DownloadsRoute

@Serializable
data object SettingsRoute

/**
 * Search is a destination rather than a tab: it raises a keyboard and owns the
 * whole screen, and it is entered from Home's search field.
 */
@Serializable
data object SearchRoute

/**
 * One title's own screen.
 *
 * Carries the pair that addresses a title rather than the whole record, because
 * the record can be large and the screen has to fetch it anyway, but it carries
 * both halves rather than a joined library id, so nothing has to re-split a
 * string whose meta half may itself contain a colon.
 */
@Serializable
data class DetailRoute(val type: String, val metaId: String)

/**
 * One video's playable sources.
 *
 * The naming is carried rather than re-derived because only the screen that
 * pushed this one knows whether the video is a film or an episode of something,
 * and asking the cache again would answer nothing after a process death. It
 * travels in parts rather than as one display string because the player's title
 * block sets each part in its own style, and re-splitting a joined title would
 * mean guessing at a separator that a show's own name may contain.
 *
 * [metaId] is carried alongside [videoId] for the same reason [DetailRoute]
 * carries both halves: a film's video id is its meta id, an episode's is not,
 * and splitting one out of the other is a guess about a format addons do not
 * promise.
 */
@Serializable
data class StreamsRoute(
    val type: String,
    val metaId: String,
    val videoId: String,
    val showTitle: String,
    /** Null for films, which have no second line anywhere they are named. */
    val episodeTag: String? = null,
    val episodeName: String? = null,
    val episodeThumbnail: String? = null,
    /**
     * The title's own art. Carried rather than looked up because a download
     * started here is shown by the Downloads tab under this poster, and that
     * tab has to render with no network at all.
     */
    val poster: String? = null,
) {
    /** One line naming the video, for a header that has room for only one. */
    val displayTitle: String
        get() = if (episodeTag == null) showTitle else "$showTitle · $episodeTag"
}

/**
 * Playback of one resolved source.
 *
 * The URL travels in the route the way it does in the shipping client: it is
 * already resolved by the time a source is chosen, and holding it anywhere else
 * would mean a second owner of the thing the player is for.
 *
 * The rest is the context the player needs to be about something rather than
 * about a URL: what is being watched ([type]/[metaId]/[videoId]), what to call
 * it, which addon vouched for the source, and the hints that addon attached to
 * it. It is wide, and deliberately so. The shipping client resolves exactly
 * this much before entering playback, and a serializable route is the module's
 * navigation contract, so what is here is also what survives process death.
 * Re-fetching any of it on the way in would mean a player that cannot name what
 * it is playing until the network answers.
 */
@Serializable
data class PlayerRoute(
    val url: String,
    val type: String,
    val metaId: String,
    val videoId: String,
    val showTitle: String,
    val episodeTag: String? = null,
    val episodeName: String? = null,
    val episodeThumbnail: String? = null,
    /** The title's own art, carried into watch-state reporting. */
    val poster: String? = null,
    /** Which addon offered this source; the next episode is asked of the same one. */
    val addonId: String,
    val bingeGroup: String? = null,
    val filename: String? = null,
    /**
     * Zero when the addon reported no size. A sentinel rather than a null
     * because navigation's type-safe routes have no `NavType` for a nullable
     * primitive; it is converted back to null the moment the route is read, so
     * nothing past this file sees it.
     */
    val videoSize: Long = 0,
    val videoHash: String? = null,
    /**
     * The source's own name and title, verbatim, for badge parsing. Nothing
     * displays them as they are: they routinely carry the debrid provider and
     * the account it belongs to, which is exactly what must not reach the
     * player's top bar.
     */
    val streamName: String? = null,
    val streamTitle: String? = null,
    /**
     * True when [url] is a file on this device rather than a source to stream.
     *
     * Carried rather than sniffed from the URL: what changes is not how the
     * file opens but what the player may assume, namely that there is no
     * network worth asking. It skips hashing the source and searching addons
     * for subtitles, both of which would be requests made on a train.
     */
    val isDownload: Boolean = false,
    /** A subtitle file stored beside a download, added to the engine directly. */
    val localSubtitlePath: String? = null,
)

/**
 * The player's route, as the screen consumes it.
 *
 * Kept beside [PlayerRoute] so a field added to one is visibly missing from the
 * other. The route is a flat, serializable, primitives-only navigation
 * contract. This is the object the screen and the slices after it pass around.
 */
internal fun PlayerRoute.playbackContext(): PlaybackContext = PlaybackContext(
    url = url,
    type = type,
    metaId = metaId,
    videoId = videoId,
    showTitle = showTitle,
    episodeTag = episodeTag,
    episodeName = episodeName,
    episodeThumbnail = episodeThumbnail,
    poster = poster,
    addonId = addonId,
    bingeGroup = bingeGroup,
    filename = filename,
    videoSize = videoSize.takeIf { it > 0 },
    videoHash = videoHash,
    streamName = streamName,
    streamTitle = streamTitle,
    isDownload = isDownload,
    localSubtitlePath = localSubtitlePath,
)

/**
 * The route into playback, built from the source that was chosen for the video
 * this picker is showing.
 *
 * [url] is passed separately because a stream without one is not playable and
 * the caller has already had to decide what to do about that.
 */
internal fun StreamsRoute.playerRoute(addon: AddonSource, stream: Stream, url: String): PlayerRoute {
    val hints = stream.behaviorHints
    return PlayerRoute(
        url = url,
        type = type,
        metaId = metaId,
        videoId = videoId,
        showTitle = showTitle,
        episodeTag = episodeTag,
        episodeName = episodeName,
        episodeThumbnail = episodeThumbnail,
        poster = poster,
        addonId = addon.id,
        bingeGroup = hints?.bingeGroup,
        filename = hints?.filename,
        videoSize = hints?.videoSize ?: 0,
        videoHash = hints?.videoHash,
        streamName = stream.name,
        streamTitle = stream.title ?: stream.description,
    )
}

/**
 * Playback of a finished download, straight off the device.
 *
 * Everything the player needs was resolved when the download was started, which
 * is what makes this route buildable with no network at all.
 */
internal fun DownloadEntry.playerRoute(files: DownloadFiles): PlayerRoute = PlayerRoute(
    url = files.videoPath,
    type = media.type,
    metaId = media.metaId,
    videoId = media.videoId,
    showTitle = media.showTitle,
    episodeTag = media.episodeTag,
    episodeName = media.episodeName,
    episodeThumbnail = media.episodeThumbnail,
    poster = media.poster,
    addonId = media.addonId,
    bingeGroup = media.bingeGroup,
    filename = media.filename,
    videoSize = media.videoSize ?: 0,
    videoHash = media.videoHash,
    streamName = media.streamName,
    streamTitle = media.streamTitle,
    isDownload = true,
    localSubtitlePath = files.subtitlePath,
)

/**
 * The download this picker would start, from the source that was chosen for the
 * video it is showing.
 *
 * Everything the Downloads tab and the offline player need is resolved here,
 * from what the picker already has, for the same reason [playerRoute] resolves
 * playback context: once the file is on the device there may be no network left
 * to ask anything with.
 */
internal fun StreamsRoute.downloadMedia(addon: AddonSource, stream: Stream, url: String): DownloadMedia {
    val hints = stream.behaviorHints
    return DownloadMedia(
        videoId = videoId,
        type = type,
        metaId = metaId,
        showTitle = showTitle,
        episodeTag = episodeTag,
        episodeName = episodeName,
        episodeThumbnail = episodeThumbnail,
        poster = poster,
        sourceFingerprint = sourceFingerprint(url),
        sourceUrl = url,
        addonId = addon.id,
        bingeGroup = hints?.bingeGroup,
        filename = hints?.filename,
        videoSize = hints?.videoSize,
        videoHash = hints?.videoHash,
        streamName = stream.name,
        streamTitle = stream.title ?: stream.description,
    )
}

/**
 * Destinations that replace the shell's chrome instead of living under it.
 *
 * The tab bar floats over content, so a screen like this would otherwise have a
 * translucent bar sitting on top of its own controls. Being in this list is all
 * a screen needs to do: it drives both the bar's visibility and the directional
 * push, so neither is decided screen by screen.
 */
internal val ChromeCoveringRoutes =
    listOf(SearchRoute::class, DetailRoute::class, StreamsRoute::class, PlayerRoute::class)

/**
 * The same player, playing something else. Used when another episode resolved
 * to a source without asking, so nothing about the journey changes except what
 * is playing.
 */
internal fun PlayerRoute.withContext(context: PlaybackContext): PlayerRoute = PlayerRoute(
    url = context.url,
    type = context.type,
    metaId = context.metaId,
    videoId = context.videoId,
    showTitle = context.showTitle,
    episodeTag = context.episodeTag,
    episodeName = context.episodeName,
    episodeThumbnail = context.episodeThumbnail,
    poster = context.poster,
    addonId = context.addonId,
    bingeGroup = context.bingeGroup,
    filename = context.filename,
    videoSize = context.videoSize ?: 0,
    videoHash = context.videoHash,
    streamName = context.streamName,
    streamTitle = context.streamTitle,
)

/** The source picker for another episode of the title already playing. */
internal fun PlayerRoute.episodeSources(choice: EpisodeChoice.NeedsSource): StreamsRoute = StreamsRoute(
    type = type,
    metaId = metaId,
    videoId = choice.videoId,
    showTitle = showTitle,
    episodeTag = choice.episodeTag,
    episodeName = choice.episodeName,
    episodeThumbnail = choice.episodeThumbnail,
    poster = poster,
)

/** The source picker for the video this player route is already showing. */
internal fun PlayerRoute.sourcesRoute(): StreamsRoute = StreamsRoute(
    type = type,
    metaId = metaId,
    videoId = videoId,
    showTitle = showTitle,
    episodeTag = episodeTag,
    episodeName = episodeName,
    episodeThumbnail = episodeThumbnail,
    poster = poster,
)
