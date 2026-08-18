package moe.ditto.halo.downloads

import kotlinx.coroutines.CancellationException
import moe.ditto.halo.api.HaloClient
import moe.ditto.halo.player.StreamVideoHasher
import moe.ditto.halo.player.SubtitleFileCache
import moe.ditto.halo.player.VideoFingerprint
import moe.ditto.halo.sync.SettingsRepository
import moe.ditto.halo.ui.languageMatches
import okio.Path.Companion.toPath

/**
 * Finds the subtitle that belongs beside a download and puts it on the device.
 *
 * A downloaded video is watched with no network, so a subtitle fetched at play
 * time is a subtitle that will not be there. This runs once, when the download
 * is accepted, and stores the file next to the video.
 */
internal interface DownloadSubtitleSource {
    /**
     * The subtitle now stored beside [media]'s video, or null when there is
     * none to be had.
     *
     * Null covers every ordinary outcome: no language preference to match, no
     * addon result in that language, an unreachable server. None of them is a
     * reason to interfere with the download, which is why this never throws.
     */
    suspend fun fetch(media: DownloadMedia): DownloadSubtitle?
}

/** For tests, and for a graph with no server to ask. */
internal object NoDownloadSubtitles : DownloadSubtitleSource {
    override suspend fun fetch(media: DownloadMedia): DownloadSubtitle? = null
}

/**
 * The real one: the same hash-matched search the player does, narrowed to the
 * one language the account prefers.
 *
 * Only the preferred language is fetched. The alternative, keeping every result
 * for later choosing, would download a dozen files for a choice almost nobody
 * makes, and the player's own rail still offers the rest whenever there is a
 * network to fetch them with.
 *
 * The file goes through Halo's authenticated proxy like every other addon
 * subtitle, and lands in the downloads directory rather than the purgeable
 * subtitle cache: the system reclaiming it would leave a downloaded film with
 * no captions and no way to fetch them.
 */
internal class AddonDownloadSubtitles(
    private val client: HaloClient,
    private val hasher: StreamVideoHasher,
    private val settings: SettingsRepository,
    private val storage: DownloadStoragePort,
    /** Seam for tests; the real one streams through the proxy onto disk. */
    private val cacheFor: (String) -> SubtitleFileCache = { directory -> SubtitleFileCache(client, directory) },
) : DownloadSubtitleSource {

    override suspend fun fetch(media: DownloadMedia): DownloadSubtitle? = try {
        find(media)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        // A video without its subtitle beats no download at all.
        null
    }

    private suspend fun find(media: DownloadMedia): DownloadSubtitle? {
        val directory = storage.directory() ?: return null
        val preferred = settings.current().preferredSubtitleLang?.takeIf { it.isNotBlank() } ?: return null

        val fingerprint = media.fingerprint() ?: hasher.fingerprint(media.sourceUrl, media.videoSize)
        val results = client.getSubtitles(
            type = media.type,
            videoId = media.videoId,
            videoHash = fingerprint?.hash,
            videoSize = fingerprint?.sizeBytes,
            filename = media.filename,
        )
        val match = results.results
            .flatMap { it.subtitles }
            .firstOrNull { languageMatches(it.lang, preferred) }
            ?: return null

        val stored = cacheFor(directory).resolve(
            identity = "${media.videoId}\u0000${match.id}",
            sourceUrl = match.url,
        )
        return DownloadSubtitle(
            // Stored as a name, like the video's own: an absolute path goes
            // stale the next time the app container is renumbered.
            fileName = stored.toPath().name,
            lang = match.lang,
            subId = match.id,
        )
    }
}

/** What the addon already knew about the file, when it knew both halves. */
private fun DownloadMedia.fingerprint(): VideoFingerprint? {
    val hash = videoHash?.takeIf { it.isNotBlank() } ?: return null
    val size = videoSize?.takeIf { it > 0 } ?: return null
    return VideoFingerprint(hash, size)
}
