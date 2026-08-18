package moe.ditto.halo.screens.player

import moe.ditto.halo.player.VideoFingerprint
import moe.ditto.halo.sync.LibraryRepository

/**
 * What the player is playing, beyond the URL it plays.
 *
 * Everything here is resolved before playback is entered by the screen that
 * chose the source, from data it already had. The player never has to wait
 * on the network to say what it is showing. It arrives as one object rather than
 * a dozen parameters because the slices after this one hand the same identity to
 * watch-state reporting, the subtitle search and the next-episode lookup, and
 * those want the whole of it rather than a field each.
 *
 * The identity fields are the addon protocol's: [metaId] addresses the title and
 * [videoId] the video within it, which are the same string for a film and are
 * not for an episode.
 */
internal data class PlaybackContext(
    val url: String,
    /** `"movie"` or `"series"`, as the addon protocol spells it. */
    val type: String,
    val metaId: String,
    val videoId: String,
    val showTitle: String,
    /** Null for films: they are one video, and there is no episode to tag. */
    val episodeTag: String? = null,
    val episodeName: String? = null,
    /** The episode still used by the drawer and Up Next card. */
    val episodeThumbnail: String? = null,
    /** The title poster, retained for watch-state rows when no metadata fetch is needed. */
    val poster: String? = null,
    /** The addon that offered this source, for asking the same one what follows. */
    val addonId: String,
    val bingeGroup: String? = null,
    /** Behaviour hints the addon attached to the source, all optional to it. */
    val filename: String? = null,
    val videoSize: Long? = null,
    val videoHash: String? = null,
    /** Raw source naming, for badge parsing only. Never displayed as given. */
    val streamName: String? = null,
    val streamTitle: String? = null,
    /**
     * True when [url] is a file on this device. What it changes is not how the
     * file opens but what this screen may assume: there is no network worth
     * asking, so the source is not hashed and no addon is searched for
     * subtitles. Both of those would be requests made on a train.
     */
    val isDownload: Boolean = false,
    /** A subtitle stored beside a download, handed to the engine directly. */
    val localSubtitlePath: String? = null,
) {
    /** True when this is an episode of something rather than a film. */
    val isEpisode: Boolean
        get() = episodeTag != null

    /**
     * One line naming what is playing, for the places that have room for only
     * one: the engine's own media title, and anything reporting on the session.
     */
    val displayTitle: String
        get() = if (episodeTag == null) showTitle else "$showTitle · $episodeTag"

    /**
     * The library item this video belongs to.
     *
     * Not the same string as [metaId]: library ids are scoped by type, and
     * every reader of a watch state joins on this one. Writing a bare meta id
     * produces rows that match nothing, which is invisible until a row that
     * should be in "continue watching" simply is not.
     */
    val itemId: String get() = LibraryRepository.itemId(type, metaId)

    /**
     * What the addon already knew about the file, when it knew both halves.
     * Null means the player has to work it out itself; a hash without a size is
     * not a match a subtitle addon can use, so neither counts alone.
     */
    fun fingerprint(): VideoFingerprint? {
        val hash = videoHash?.takeIf { it.isNotBlank() } ?: return null
        val size = videoSize?.takeIf { it > 0 } ?: return null
        return VideoFingerprint(hash, size)
    }
}
