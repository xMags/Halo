package moe.ditto.halo.tv.player

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ditto.halo.browse.VideoDecoderSupport
import moe.ditto.halo.player.DeviceVideoDecoders

/**
 * What decides which sources this TV can play.
 *
 * [hardwareDecodingAtStart] is the setting the running player was built with
 * ([TvPlaybackPreferences.hardwareDecodingAtStart]; the activity picks
 * `MpvProfile.Tv` or `MpvProfile.TvSoftware` from it). With the video chip left
 * out, its decoders say nothing about what plays, so every source counts.
 * The codec list is read off the main thread; it is cached after the first read.
 */
internal suspend fun tvVideoDecoders(hardwareDecodingAtStart: Boolean): VideoDecoderSupport {
    if (!hardwareDecodingAtStart) return VideoDecoderSupport.Unrestricted
    val decoders = withContext(Dispatchers.Default) { DeviceVideoDecoders.current }
    Log.i(LogTag, "hardware video decoders: ${decoders.hardware ?: "not read, every source listed"}")
    return decoders
}

private const val LogTag = "HALO_TV"
