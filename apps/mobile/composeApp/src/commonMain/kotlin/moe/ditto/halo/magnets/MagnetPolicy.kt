package moe.ditto.halo.magnets

import kotlin.math.floor

/*
 * What Search's action card says about a pasted magnet, and which file of a
 * resolved magnet stands for a video of a title page. A magnet's files are
 * never listed: a ready, identified magnet only opens its title page, and is
 * offered there as one source (its largest file for a film, the largest file
 * of that season and episode for an episode). A port of `@halo/core`'s
 * `magnets/policy.ts`, held to `packages/core/test/vectors/magnets.json`.
 */

/** Where the app stands with the pasted magnet. */
enum class MagnetPhase {
    /** The server has no magnets for this account, or Halo has no account. */
    Unavailable,
    /** Asked, or waiting to learn whether the server has magnets. */
    Checking,
    /** The server answered; the card follows its request. */
    Answered,
    /** The server could not be reached or answered badly. */
    Failed,
    /** The viewer cancelled a download from the card. */
    Stopped,
}

enum class MagnetCardTone { Accent, Warn, Bad, Mute }

/** The card's one action: Open title is the primary one; the rest are by tap only. */
enum class MagnetCardAction { None, OpenTitle, Cancel, PlayUnidentified, Retry }

data class MagnetCard(
    val tone: MagnetCardTone,
    /** The matched title's poster leads instead of the magnet glyph. */
    val showsPoster: Boolean = false,
    val title: String,
    /** The meta row: a value in mono, a plain value, a second value in mono. */
    val mono: String = "",
    val plain: String = "",
    val tail: String = "",
    val secondary: String = "",
    /** 0 to 1 while a download is under way. */
    val progress: Double? = null,
    val action: MagnetCardAction = MagnetCardAction.None,
    val actionLabel: String = "",
    val identity: MagnetIdentity? = null,
    /** The file Play as unidentified video plays. */
    val unidentifiedFile: MagnetFile? = null,
)

object MagnetCopy {
    const val NotAvailableTitle = "Magnet links aren't available"
    const val NotAvailableText = "This server doesn't open magnet links for your account."
    const val CheckingTitle = "Checking this torrent…"
    const val CheckingText = "Looking for a video file."
    const val ErrorTitle = "Couldn't check this torrent"
    const val ErrorText = "The server did not answer. Try again in a moment."
    const val RetryLabel = "Try again"
    const val NotCachedTitle = "This torrent isn't ready"
    const val NotCachedText = "The server's debrid account doesn't have it yet. Try another magnet for the same title."
    /** When the server may fetch uncached torrents, "not cached" means its debrid account could not start this one. */
    const val NotCachedFetchText = "The server's debrid account couldn't start it. Try another magnet for the same title."
    const val DownloadingTitle = "Getting this torrent ready…"
    const val DownloadingText = "You can leave this page; paste the magnet again to check on it."
    const val CancelLabel = "Cancel"
    const val NoVideoTitle = "No video in this torrent"
    const val NoVideoText = "Halo only plays video files. Nothing was added."
    const val TooLargeTitle = "This torrent is too large"
    const val TooLargeText = "It is bigger than the 100 GB the server accepts."
    const val UnidentifiedTitle = "Unidentified video"
    const val UnidentifiedText = "Halo could not tell which film or series this is. It won't be added to your history."
    const val PlayUnidentifiedLabel = "Play as unidentified video"
    const val RefusedTitle = "Can't open this magnet"
    const val RefusedText = "Halo could not tell which film or series this is."
    const val SeriesText = "Open the title to pick an episode."
    const val OpenTitleLabel = "Open title"
    const val StoppedTitle = "Stopped getting this torrent ready"
    const val StoppedText = "Nothing more is fetched for it."
    const val StartAgainLabel = "Start again"
    const val SourceName = "Your magnet"
}

private fun note(tone: MagnetCardTone, title: String, secondary: String) = MagnetCard(tone = tone, title = title, secondary = secondary)

private fun errorCard() =
    note(MagnetCardTone.Bad, MagnetCopy.ErrorTitle, MagnetCopy.ErrorText).copy(action = MagnetCardAction.Retry, actionLabel = MagnetCopy.RetryLabel)

/** The biggest file, the first of equals; null for an empty list. */
fun largestMagnetFile(files: List<MagnetFile>): MagnetFile? {
    var best: MagnetFile? = null
    for (file in files) if (best == null || file.sizeBytes > best.sizeBytes) best = file
    return best
}

private fun readyCard(request: MagnetRequest, formatSize: (Long?) -> String): MagnetCard {
    val file = largestMagnetFile(request.files) ?: return errorCard()
    val size = { bytes: Long -> formatSize(if (bytes > 0) bytes else null) }
    val identity = request.identified
        ?: return note(MagnetCardTone.Warn, MagnetCopy.UnidentifiedTitle, MagnetCopy.UnidentifiedText).copy(
            mono = file.name,
            tail = size(file.sizeBytes),
            action = MagnetCardAction.PlayUnidentified,
            actionLabel = MagnetCopy.PlayUnidentifiedLabel,
            unidentifiedFile = file,
        )
    val base = MagnetCard(
        tone = MagnetCardTone.Accent,
        showsPoster = true,
        title = identity.name,
        identity = identity,
        action = MagnetCardAction.OpenTitle,
        actionLabel = MagnetCopy.OpenTitleLabel,
    )
    if (identity.type != "series") {
        return base.copy(mono = "MOVIE", plain = identity.year.orEmpty(), tail = size(file.sizeBytes), secondary = file.name)
    }
    // Only files with a season and an episode can be offered, so only those are counted.
    val counted = request.files.filter { it.season != null && it.episode != null }
    val seasons = counted.mapNotNull { it.season }.toSet()
    val bytes = counted.sumOf { it.sizeBytes }
    val plain = when {
        seasons.size == 1 -> "Season ${seasons.first()}"
        seasons.size > 1 -> "${seasons.size} seasons"
        else -> ""
    }
    val episodes = counted.size
    return base.copy(
        mono = "SERIES",
        plain = plain,
        tail = "$episodes ${if (episodes == 1) "episode" else "episodes"} · ${size(if (episodes > 0) bytes else request.sizeBytes)}",
        secondary = MagnetCopy.SeriesText,
    )
}

/** The card for where the app stands with a pasted magnet. [formatSize] reads null as unknown. */
fun buildMagnetCard(
    phase: MagnetPhase,
    linkName: String,
    request: MagnetRequest?,
    cachedOnly: Boolean,
    formatSize: (Long?) -> String,
): MagnetCard = when (phase) {
    MagnetPhase.Unavailable -> note(MagnetCardTone.Mute, MagnetCopy.NotAvailableTitle, MagnetCopy.NotAvailableText)
    MagnetPhase.Checking -> note(MagnetCardTone.Accent, MagnetCopy.CheckingTitle, MagnetCopy.CheckingText).copy(mono = linkName)
    MagnetPhase.Failed -> errorCard()
    MagnetPhase.Stopped ->
        note(MagnetCardTone.Mute, MagnetCopy.StoppedTitle, MagnetCopy.StoppedText).copy(action = MagnetCardAction.Retry, actionLabel = MagnetCopy.StartAgainLabel)
    MagnetPhase.Answered -> if (request == null) errorCard() else answeredCard(request, linkName, cachedOnly, formatSize)
}

private fun answeredCard(request: MagnetRequest, linkName: String, cachedOnly: Boolean, formatSize: (Long?) -> String): MagnetCard =
    when (request.state) {
        MagnetState.Ready -> readyCard(request, formatSize)
        MagnetState.NotCached ->
            note(MagnetCardTone.Warn, MagnetCopy.NotCachedTitle, if (cachedOnly) MagnetCopy.NotCachedText else MagnetCopy.NotCachedFetchText)
        MagnetState.NoVideo -> note(MagnetCardTone.Bad, MagnetCopy.NoVideoTitle, MagnetCopy.NoVideoText)
        MagnetState.TooLarge -> note(MagnetCardTone.Bad, MagnetCopy.TooLargeTitle, MagnetCopy.TooLargeText)
        MagnetState.Downloading -> {
            val fraction = if (request.progress.isFinite()) request.progress.coerceIn(0.0, 1.0) else 0.0
            note(MagnetCardTone.Accent, MagnetCopy.DownloadingTitle, MagnetCopy.DownloadingText).copy(
                mono = request.name.ifEmpty { linkName },
                progress = fraction,
                tail = "${floor(fraction * 100).toInt()}%",
                action = MagnetCardAction.Cancel,
                actionLabel = MagnetCopy.CancelLabel,
            )
        }
        // Another failure (the debrid service refused, the torrent went away) is tried again by asking again.
        MagnetState.Failed ->
            if (request.reason == "unidentified") note(MagnetCardTone.Bad, MagnetCopy.RefusedTitle, MagnetCopy.RefusedText) else errorCard()
    }

/** `<metaId>:<season>:<episode>`, exactly. */
private fun episodeOf(videoId: String, metaId: String): Pair<Int, Int>? {
    if (metaId.isEmpty() || videoId.length <= metaId.length + 1 || !videoId.startsWith(metaId) || videoId[metaId.length] != ':') return null
    val rest = videoId.substring(metaId.length + 1)
    val colon = rest.indexOf(':')
    if (colon < 0) return null
    val whole = Regex("""^\d{1,9}$""")
    val season = rest.substring(0, colon).takeIf(whole::matches)?.toInt() ?: return null
    val episode = rest.substring(colon + 1).takeIf(whole::matches)?.toInt() ?: return null
    return season to episode
}

/** The file of a ready, identified magnet that stands for this title page's video, or null. */
fun magnetFileFor(magnet: MagnetRequest, type: String, videoId: String): MagnetFile? {
    val identity = magnet.identified ?: return null
    if (magnet.state != MagnetState.Ready || magnet.files.isEmpty() || type != identity.type) return null
    if (type == "movie") return if (videoId == identity.id) largestMagnetFile(magnet.files) else null
    if (type != "series") return null
    val (season, episode) = episodeOf(videoId, identity.id) ?: return null
    return largestMagnetFile(magnet.files.filter { it.season == season && it.episode == episode })
}
