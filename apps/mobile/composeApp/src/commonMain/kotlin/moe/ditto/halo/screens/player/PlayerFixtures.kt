package moe.ditto.halo.screens.player

import moe.ditto.halo.player.PlaybackStatus
import moe.ditto.halo.player.PlayerState
import moe.ditto.halo.player.PlayerTrack
import moe.ditto.halo.player.PlayerTracks

/**
 * Stand-in content for the parts of the player that have no real source yet.
 *
 * These exist so the screen can be built and reviewed in full before the data
 * behind it is wired: the show and episode names arrive with the playback
 * context, the stream badges are parsed from the chosen source, and the episode
 * list comes from the title's meta. Each one is replaced in its own slice, and
 * this file should be empty by the end of it.
 *
 * The values match the approved prototype so the built screen can be compared
 * against it side by side.
 */
internal object PlayerFixtures {
    const val ShowTitle = "Pale Blue Dot"
    const val EpisodeName = "Carrier Lost · Season 2"

    /**
     * Resolution and codec only. The source's provider is deliberately absent:
     * a debrid account name on screen is an account identifier, and it is not
     * information the viewer needs while watching.
     */
    val StreamBadges = listOf("1080p", "HEVC 10-bit")

    /**
     * Stands in for the platform's real answer, so the harness shows the
     * unbundled-font notice on the chips that would trigger it.
     */
    val BundledSubtitleFonts = setOf("Inter", "Source Serif 4", "JetBrains Mono")

    /**
     * How far ahead of the playhead the scene harness draws the transport's
     * buffered fill. The real screen reads the engine's demuxer cache instead;
     * this exists so the harness, which has no engine, still shows the fill.
     */
    const val BufferedLeadFraction = 0.09f

    /**
     * Stand-in addon results for the harness, which fetches nothing. The real
     * screen reads them from the subtitles endpoint; these keep the section's
     * layout reviewable, including two results in the same language.
     */
    val AddonSubtitles = listOf(
        addonSubtitle("opensubtitles", "OpenSubtitles", "eng", "os-en-6821194", "srt"),
        addonSubtitle("opensubtitles", "OpenSubtitles", "eng", "os-en-6821507", "srt"),
        addonSubtitle("kitsunekko", "Kitsunekko", "jpn", "ktx-4471", "ass"),
    )

    private fun addonSubtitle(
        addonId: String,
        addonName: String,
        lang: String,
        subId: String,
        extension: String,
    ) = AddonSubtitleOption(
        id = "$addonId:$subId",
        addonId = addonId,
        addonName = addonName,
        lang = lang,
        subId = subId,
        url = "https://subs.fixture.test/$subId.$extension",
    )

    const val SeasonTitle = "Season 2"

    val Episodes = listOf(
        episode("S02E01", "Ground Truth", progress = 1f, downloaded = true),
        episode("S02E02", "Ninety Seconds", progress = 1f, downloaded = true),
        episode("S02E03", "Carrier Lost", progress = 1f, downloaded = true),
        episode("S02E04", "Pale Blue Dot", progress = 0.42f, downloaded = false),
        episode("S02E05", "The Long Signal", progress = 0f, downloaded = false),
        episode("S02E06", "Dust Season", progress = 0f, downloaded = false),
    )

    private fun episode(tag: String, name: String, progress: Float, downloaded: Boolean) =
        PlayerEpisode(
            videoId = "tt-fixture:2:${tag.substringAfter('E').trimStart('0')}",
            tag = tag,
            name = name,
            thumbnail = "https://episodes.metahub.space/tt0903747/2/${tag.substringAfter('E').toInt()}/w780.jpg",
            progress = progress,
            downloaded = downloaded,
        )

    /** The episode the fixture session is playing. */
    val CurrentEpisode = Episodes[3]

    /** What the up-next card offers. */
    val NextEpisode = Episodes[4]

    /**
     * Sample line for the subtitle appearance preview. Long enough to wrap at
     * the largest scale, which is when wrapping is the thing being judged.
     */
    const val CaptionSample = "They kept the antenna pointed at nothing for eleven years."

    /**
     * A playback state for the scene harness, which has no engine behind it.
     * Roughly forty-three per cent through a forty-seven minute episode, which
     * puts the transport somewhere it has to lay out both timecodes and a
     * partly filled bar.
     */
    val State = PlayerState(
        status = PlaybackStatus.Playing,
        positionSeconds = 1_230.0,
        durationSeconds = 2_852.0,
        tracks = PlayerTracks(
            audio = listOf(
                PlayerTrack(id = "1", label = "English", language = "eng", codec = "eac3", channels = 6, sampleRateHz = 48000),
                PlayerTrack(id = "2", label = "Japanese", language = "jpn", codec = "aac", channels = 2, sampleRateHz = 48000),
                PlayerTrack(id = "3", label = "Commentary", language = "eng", codec = "aac", channels = 2, sampleRateHz = 48000),
            ),
            subtitles = listOf(
                PlayerTrack(id = "4", label = "English, Signs & Songs", language = "eng", codec = "ass"),
                PlayerTrack(id = "5", label = "English (SDH)", language = "eng", codec = "subrip"),
                PlayerTrack(id = "6", label = "Japanese", language = "jpn", codec = "hdmv_pgs_subtitle"),
            ),
            selectedAudioId = "1",
            selectedSubtitleId = "4",
        ),
    )
}
