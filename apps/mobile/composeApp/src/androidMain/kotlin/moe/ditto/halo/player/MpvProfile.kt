package moe.ditto.halo.player

/**
 * The engine options that differ between the phone and the TV. The phone's
 * values are what it has always used; the TV's are tuned for set-top boxes
 * and sticks, which have hardware decoders, weak GPUs and little memory.
 */
internal data class MpvProfile(
    /** mpv's `hwdec`: decoders to try in order, falling back to software. */
    val hwdec: String,
    /** mpv's `vo`, switched on once a surface attaches. */
    val vo: String = "gpu",
    /** mpv's `demuxer-max-bytes`, or null for mpv's own default. */
    val demuxerMaxBytes: String? = null,
    /** mpv's `demuxer-max-back-bytes`, or null for mpv's own default. */
    val demuxerMaxBackBytes: String? = null,
    /** A built-in mpv profile applied before the options below, or null for none. */
    val renderProfile: String? = null,
    /** mpv's `hdr-compute-peak`: measuring each HDR frame's brightness with compute shaders. */
    val hdrComputePeak: Boolean = true,
    /** mpv's `egl-output-format`: the pixel format of the surface it draws on, or null for mpv's choice. */
    val eglOutputFormat: String? = null,
) {
    companion object {
        val Phone = MpvProfile(hwdec = "mediacodec-copy")

        val Tv = MpvProfile(
            hwdec = "mediacodec,mediacodec-copy",
            // On a Fire TV Cube (Mali-G52) the `gpu` output draws nothing at
            // all: frames decode, sound plays, mpv reports every frame shown,
            // and the surface holds only its background. `gpu-next`, mpv's
            // libplacebo renderer, draws the same frames on the same surface.
            vo = "gpu-next",
            // mpv's defaults (150 MiB ahead, 50 MiB behind) are sized for a
            // computer; a 1 GB stick runs out of memory long before that.
            demuxerMaxBytes = "64MiB",
            demuxerMaxBackBytes = "16MiB",
            // Bilinear scaling and no dithering: measured on the Cube, a 4K
            // HDR film dropped half as many frames as with the default
            // high-quality scalers, and nothing else plays any worse.
            renderProfile = "fast",
            // A fixed tone curve rather than measuring each HDR frame, which
            // costs GPU time and memory these boxes do not have to spare.
            hdrComputePeak = false,
            // 8-bit like every other layer on these boxes. Left to itself mpv
            // picks a 10-bit surface where the GPU offers one, which an SDR
            // picture gains nothing from.
            eglOutputFormat = "rgba8",
        )

        /**
         * The TV with its video chip left out, for a device whose decoder
         * misbehaves: slower and hotter, but it plays.
         */
        val TvSoftware = Tv.copy(hwdec = "no")
    }
}
