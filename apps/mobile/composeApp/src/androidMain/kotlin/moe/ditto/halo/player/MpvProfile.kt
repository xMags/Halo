package moe.ditto.halo.player

/**
 * The engine options that differ between the phone and the TV. The phone's
 * values are what it has always used; the TV's are tuned for set-top boxes
 * and sticks, which have hardware decoders and little memory.
 */
internal data class MpvProfile(
    /** mpv's `hwdec`: decoders to try in order, falling back to software. */
    val hwdec: String,
    /** mpv's `demuxer-max-bytes`, or null for mpv's own default. */
    val demuxerMaxBytes: String? = null,
    /** mpv's `demuxer-max-back-bytes`, or null for mpv's own default. */
    val demuxerMaxBackBytes: String? = null,
) {
    companion object {
        val Phone = MpvProfile(hwdec = "mediacodec-copy")

        val Tv = MpvProfile(
            hwdec = "mediacodec,mediacodec-copy",
            // mpv's defaults (150 MiB ahead, 50 MiB behind) are sized for a
            // computer; a 1 GB stick runs out of memory long before that.
            demuxerMaxBytes = "64MiB",
            demuxerMaxBackBytes = "16MiB",
        )

        /**
         * The TV with its video chip left out, for a device whose decoder
         * misbehaves: slower and hotter, but it plays.
         */
        val TvSoftware = Tv.copy(hwdec = "no")
    }
}
