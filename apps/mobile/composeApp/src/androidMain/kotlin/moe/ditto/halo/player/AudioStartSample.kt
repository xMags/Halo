package moe.ditto.halo.player

/**
 * One file as the engine played it: the core that played it and the load on
 * that core. A new load, a stop or a replaced core makes a different value,
 * so an action meant for one file can check it is still that file.
 */
internal data class PlaybackFile(val core: Long, val load: Long)

/**
 * What the engine says about whether a file's sound has started, read in one
 * go on the core's executor. Every value is mpv's own; null where mpv has none.
 */
internal data class AudioStartReading(
    /** The viewer's (or the app's) pause. */
    val paused: Boolean,
    /** mpv holding playback while the cache refills. */
    val pausedForCache: Boolean,
    /**
     * The picture is up: the video output is configured, which happens with
     * the first decoded frame, or the file plays no video at all.
     */
    val pictureReady: Boolean,
    /** mpv's `audio-pts`: only available once sound is playing, and it moves while it plays. */
    val audioPts: Double?,
    /** mpv's `time-pos`. */
    val positionSeconds: Double?,
    /** mpv's `demuxer-cache-duration`: how much of the file is read ahead of playback. */
    val cachedAheadSeconds: Double?,
    /** mpv's `current-ao`: the open audio output, null while none is open. For the log. */
    val audioOutput: String?,
)

internal data class AudioStartSample(val file: PlaybackFile, val reading: AudioStartReading)
