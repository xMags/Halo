package moe.ditto.halo.player

/**
 * How decoded video reaches the screen on a profile that offers both ways.
 *
 * [Direct] is mpv's `mediacodec_embed` output: the hardware decoder renders
 * straight onto the surface, so the picture keeps its full resolution and its
 * HDR metadata, but mpv draws nothing over it (no subtitles, no OSD) and does
 * no scaling. [Composed] is the GPU output: mpv decodes, scales and draws
 * everything itself, which costs resolution and HDR on TV chips whose decoders
 * hand frames back to the GPU only at reduced size.
 */
internal enum class VideoPath { Direct, Composed }

/** A path and the plain-words reason for it, as one log line shows them. */
internal data class VideoPathDecision(val path: VideoPath, val reason: String) {
    /** For example `composed (picture subtitle hdmv_pgs_subtitle)`. */
    fun describe(): String = "${path.name.lowercase()} ($reason)"
}

/** Who has to draw the selected subtitle track. */
internal enum class SubtitleDrawing {
    /** No track is selected. */
    None,

    /** Plain text the app can draw from mpv's `sub-text`. */
    AppText,

    /** A picture, or styling the app cannot reproduce: only the engine can draw it. */
    Engine,
}

/** How long a subtitle-driven switch waits for its inputs to hold still. */
internal enum class SubtitleSettle {
    /** mpv's own pick for a fresh file, which the app usually replaces: the long wait. */
    Provisional,

    /** A choice by the app or the viewer, or a styling change: the short wait. */
    Deliberate,
}

/**
 * The pure rules behind the video path. No Android or mpv types, so every
 * row is unit tested.
 */
internal object VideoPathPolicy {

    /** What a successful direct path reports as mpv's `hwdec-current`. */
    const val DirectDecoder = "mediacodec"

    /**
     * Codecs mpv decodes to text (its `sd_ass` path), so `sub-text` carries
     * the whole cue. Names are ffmpeg's, as mpv reports them in `track-list`.
     */
    private val TextCodecs = setOf(
        "subrip", "srt", "ass", "ssa", "mov_text", "webvtt", "text",
        "microdvd", "subviewer", "subviewer1", "sami", "realtext", "jacosub",
        "mpl2", "pjs", "vplayer", "stl", "ttml", "eia_608", "hdmv_text_subtitle",
    )

    /** Scripts whose own styling only the engine can draw. */
    private val ScriptCodecs = setOf("ass", "ssa")

    /**
     * Who draws the track with [codec].
     *
     * A codec in neither list (a picture codec such as `hdmv_pgs_subtitle`,
     * `dvd_subtitle`, `dvb_subtitle`, `dvb_teletext` or `xsub`, or one this list
     * does not know, or none reported) goes to the engine. The two possible
     * mistakes are not equal: a text track wrongly sent to the engine still
     * shows, at the composed path's lower resolution, while a picture track
     * wrongly kept on the direct path shows nothing at all, because its
     * `sub-text` is empty and the viewer gets no sign of why.
     */
    fun subtitleDrawing(selected: Boolean, codec: String?, keepScriptStyling: Boolean): SubtitleDrawing {
        if (!selected) return SubtitleDrawing.None
        val name = codec?.lowercase() ?: return SubtitleDrawing.Engine
        if (name !in TextCodecs) return SubtitleDrawing.Engine
        if (keepScriptStyling && name in ScriptCodecs) return SubtitleDrawing.Engine
        return SubtitleDrawing.AppText
    }

    /**
     * The path for the current file.
     *
     * [directFailure] is the reason the direct path failed for this file, or
     * null when it has not; once set it holds the file on the composed path
     * whatever the subtitle does.
     */
    fun decide(
        directAllowed: Boolean,
        directFailure: String?,
        subtitleSelected: Boolean,
        subtitleCodec: String?,
        keepScriptStyling: Boolean,
    ): VideoPathDecision {
        if (!directAllowed) return VideoPathDecision(VideoPath.Composed, "direct video not offered")
        if (directFailure != null) return VideoPathDecision(VideoPath.Composed, directFailure)
        val codec = subtitleCodec?.lowercase() ?: "unknown"
        return when (subtitleDrawing(subtitleSelected, subtitleCodec, keepScriptStyling)) {
            SubtitleDrawing.None -> VideoPathDecision(VideoPath.Direct, "no subtitle")
            SubtitleDrawing.AppText -> VideoPathDecision(VideoPath.Direct, "text subtitle $codec")
            SubtitleDrawing.Engine ->
                if (codec in ScriptCodecs) {
                    VideoPathDecision(VideoPath.Composed, "styled subtitle $codec with its own styling kept")
                } else {
                    VideoPathDecision(VideoPath.Composed, "picture subtitle $codec")
                }
        }
    }

    /**
     * Why the direct path cannot show this file's picture, or null when it can
     * (or when nothing can be told yet).
     *
     * [hwdecCurrent] is mpv's `hwdec-current`, null while no video decoder
     * exists. Any value other than [DirectDecoder] (`no` for software) means
     * frames the direct output cannot display. With no decoder, a video track
     * that exists but is not selected ([videoSelected] false) means mpv gave
     * up on the video chain itself, which is what a failed output or format
     * conversion does: it deselects the track and plays on as sound.
     */
    fun directFailure(hwdecCurrent: String?, videoSelected: Boolean, hasVideoTrack: Boolean): String? {
        if (hwdecCurrent != null) {
            if (hwdecCurrent == DirectDecoder) return null
            return "hardware decoder not used, hwdec-current $hwdecCurrent"
        }
        if (!videoSelected && hasVideoTrack) return "video chain dropped the picture"
        return null
    }
}

/**
 * The inputs of [VideoPathPolicy.decide] for the current file, and the path
 * now in effect.
 *
 * Not thread-safe: the owning core touches it only on its single callback
 * executor. Holds no timer; the core debounces subtitle changes around it.
 */
internal class VideoPathTracker(private val directAllowed: Boolean) {

    /** The path whose output is set, or is set when the next surface attaches. */
    var path: VideoPath = if (directAllowed) VideoPath.Direct else VideoPath.Composed
        private set

    /**
     * True between the current file's FILE_LOADED and the next load or stop.
     * Before it, mpv has not selected tracks yet, so an unselected video track
     * says nothing.
     */
    var fileLoaded: Boolean = false
        private set

    private var directFailure: String? = null

    // Whether the app or the viewer has chosen this file's subtitle since the
    // last load. Until then the selection is mpv's own pick.
    private var subtitleChosen = false

    // Whether the app added a subtitle that track-list does not report as
    // selected yet. mpv selects an added track only once it has read it, which
    // can take over a second while the file is still opening, so until then
    // the selection is a leftover and must not switch on the short settle.
    private var awaitingAddedTrack = false

    // Whether the latest input change was the styling setting rather than a
    // selection; it settles short without making the selection deliberate.
    private var stylingChangedLast = false
    private var subtitleSelected = false
    private var subtitleCodec: String? = null

    // The player's own default, which mpv's starting `sub-ass-override=yes`
    // matches until the app applies the viewer's setting.
    private var keepScriptStyling = true

    fun decision(): VideoPathDecision = VideoPathPolicy.decide(
        directAllowed = directAllowed,
        directFailure = directFailure,
        subtitleSelected = subtitleSelected,
        subtitleCodec = subtitleCodec,
        keepScriptStyling = keepScriptStyling,
    )

    /** True when [decision] names a path other than the one in effect. */
    fun wantsSwitch(): Boolean = decision().path != path

    /**
     * The settle a pending subtitle-driven switch waits for. Long while the
     * selection is mpv's own pick or a leftover waiting for an added track;
     * if that track never arrives, the leftover drives the path after the long
     * wait, which is the right outcome for a subtitle that failed to load.
     */
    fun settle(): SubtitleSettle = when {
        awaitingAddedTrack -> SubtitleSettle.Provisional
        subtitleChosen || stylingChangedLast -> SubtitleSettle.Deliberate
        else -> SubtitleSettle.Provisional
    }

    /**
     * Forgets the last file. Every file starts on the direct path where the
     * profile offers it, so the returned decision is that, whatever the last
     * file ended on.
     */
    fun onLoadRequested(): VideoPathDecision {
        fileLoaded = false
        directFailure = null
        subtitleChosen = false
        awaitingAddedTrack = false
        stylingChangedLast = false
        subtitleSelected = false
        subtitleCodec = null
        if (!directAllowed) return decision()
        return VideoPathDecision(VideoPath.Direct, "new file")
    }

    fun onFileLoaded() {
        fileLoaded = true
    }

    fun onStopped() {
        fileLoaded = false
        subtitleChosen = false
        awaitingAddedTrack = false
        stylingChangedLast = false
    }

    /**
     * The app or the viewer selected a track, or turned subtitles off. mpv
     * applies that at once, so the next track report is the chosen selection
     * and the short settle applies. Ends any wait for an added track.
     */
    fun onSubtitleSelectedByApp() {
        subtitleChosen = true
        awaitingAddedTrack = false
    }

    /**
     * The app added a subtitle, which mpv selects only once it has read it.
     * The file's selection is chosen, but until track-list reports a new
     * selection the current one is a leftover and settles long.
     */
    fun onSubtitleAdded() {
        subtitleChosen = true
        awaitingAddedTrack = true
    }

    /**
     * Records that the direct path failed for this file. True only for the
     * first failure, so the switch happens once; the file then stays composed
     * until the next [onLoadRequested].
     */
    fun onDirectFailed(reason: String): Boolean {
        if (!directAllowed || directFailure != null) return false
        directFailure = reason
        return true
    }

    /** True when the selected subtitle changed, which restarts the debounce. */
    fun onSubtitleSelected(selected: Boolean, codec: String?): Boolean {
        val normalized = if (selected) codec else null
        if (selected == subtitleSelected && normalized == subtitleCodec) return false
        subtitleSelected = selected
        subtitleCodec = normalized
        awaitingAddedTrack = false
        stylingChangedLast = false
        return true
    }

    /** True when the viewer's styling setting changed. */
    fun onTrackStyling(keepScript: Boolean): Boolean {
        if (keepScript == keepScriptStyling) return false
        keepScriptStyling = keepScript
        stylingChangedLast = true
        return true
    }

    /** The core set [next]'s output, or will at the next surface attach. */
    fun adopt(next: VideoPath) {
        path = next
    }
}
