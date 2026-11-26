package moe.ditto.halo.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Media3's own constants are compile-time values, so these pin the mapping to the library's spelling. */
class Media3MappingTest {

    @Test
    fun parsedCuesAreReadAsTheirOriginalFormat() {
        assertEquals(MimeTypes.APPLICATION_MEDIA3_CUES, Media3Mapping.ParsedCuesMime)
        assertEquals(MimeTypes.APPLICATION_SUBRIP, Media3Mapping.subtitleMime(MimeTypes.APPLICATION_MEDIA3_CUES, MimeTypes.APPLICATION_SUBRIP))
        assertEquals(MimeTypes.TEXT_VTT, Media3Mapping.subtitleMime(MimeTypes.TEXT_VTT, null))
    }

    @Test
    fun textSubtitlesAreShownAndNamedAsMpvNamesThem() {
        assertEquals("subrip", Media3Mapping.subtitleCodec(MimeTypes.APPLICATION_SUBRIP))
        assertEquals("ass", Media3Mapping.subtitleCodec(MimeTypes.TEXT_SSA))
        assertEquals("webvtt", Media3Mapping.subtitleCodec(MimeTypes.TEXT_VTT))
        assertEquals("webvtt", Media3Mapping.subtitleCodec(MimeTypes.APPLICATION_MP4VTT))
        assertEquals("ttml", Media3Mapping.subtitleCodec(MimeTypes.APPLICATION_TTML))
        assertEquals("mov_text", Media3Mapping.subtitleCodec(MimeTypes.APPLICATION_TX3G))
        assertEquals("eia_608", Media3Mapping.subtitleCodec(MimeTypes.APPLICATION_CEA608))
        for (mime in listOf(
            MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_SSA, MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_MP4VTT,
            MimeTypes.APPLICATION_TTML, MimeTypes.APPLICATION_TX3G, MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_CEA708,
        )) {
            assertTrue(Media3Mapping.subtitleShowable(mime), mime)
        }
    }

    @Test
    fun pictureAndUnknownSubtitlesAreNotOffered() {
        assertEquals("hdmv_pgs_subtitle", Media3Mapping.subtitleCodec(MimeTypes.APPLICATION_PGS))
        assertEquals("dvd_subtitle", Media3Mapping.subtitleCodec(MimeTypes.APPLICATION_VOBSUB))
        assertEquals("dvb_subtitle", Media3Mapping.subtitleCodec(MimeTypes.APPLICATION_DVBSUBS))
        for (mime in listOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_VOBSUB, MimeTypes.APPLICATION_DVBSUBS, "text/x-unknown", null)) {
            assertFalse(Media3Mapping.subtitleShowable(mime), "$mime")
        }
    }

    @Test
    fun audioIsNamedAsMpvNamesIt() {
        assertEquals("eac3", Media3Mapping.audioCodec(MimeTypes.AUDIO_E_AC3))
        assertEquals("eac3", Media3Mapping.audioCodec(MimeTypes.AUDIO_E_AC3_JOC))
        assertEquals("ac3", Media3Mapping.audioCodec(MimeTypes.AUDIO_AC3))
        assertEquals("truehd", Media3Mapping.audioCodec(MimeTypes.AUDIO_TRUEHD))
        assertEquals("dts", Media3Mapping.audioCodec(MimeTypes.AUDIO_DTS))
        assertEquals("dts", Media3Mapping.audioCodec(MimeTypes.AUDIO_DTS_HD))
        assertEquals("aac", Media3Mapping.audioCodec(MimeTypes.AUDIO_AAC))
        assertEquals("opus", Media3Mapping.audioCodec(MimeTypes.AUDIO_OPUS))
        assertEquals("flac", Media3Mapping.audioCodec(MimeTypes.AUDIO_FLAC))
        assertEquals("audio/x-new", Media3Mapping.audioCodec("audio/x-new"))
        assertNull(Media3Mapping.audioCodec(null))
    }

    @Test
    fun addedSubtitleFilesAreTypedByTheirName() {
        assertEquals(MimeTypes.APPLICATION_SUBRIP, Media3Mapping.sideloadedSubtitleMime("/cache/abc.srt"))
        assertEquals(MimeTypes.TEXT_VTT, Media3Mapping.sideloadedSubtitleMime("/cache/abc.VTT"))
        assertEquals(MimeTypes.TEXT_SSA, Media3Mapping.sideloadedSubtitleMime("/cache/abc.ass"))
        assertEquals(MimeTypes.APPLICATION_TTML, Media3Mapping.sideloadedSubtitleMime("/cache/abc.ttml"))
        assertNull(Media3Mapping.sideloadedSubtitleMime("/cache/abc.sub"))
        assertNull(Media3Mapping.sideloadedSubtitleMime("/cache/abc"))
    }

    @Test
    fun theTransferIsNamedAsMpvNamesIt() {
        assertEquals(C.COLOR_TRANSFER_ST2084, Media3Mapping.TransferSt2084)
        assertEquals(C.COLOR_TRANSFER_HLG, Media3Mapping.TransferHlg)
        assertEquals(C.COLOR_TRANSFER_SDR, Media3Mapping.TransferSdr)
        assertEquals("pq", Media3Mapping.gamma(C.COLOR_TRANSFER_ST2084))
        assertEquals("hlg", Media3Mapping.gamma(C.COLOR_TRANSFER_HLG))
        assertEquals("bt.1886", Media3Mapping.gamma(C.COLOR_TRANSFER_SDR))
        assertNull(Media3Mapping.gamma(null))
    }

    @Test
    fun theDolbyVisionProfileIsReadFromTheCodecsString() {
        assertEquals(5, Media3Mapping.dolbyVisionProfile("dvhe.05.06"))
        assertEquals(8, Media3Mapping.dolbyVisionProfile("dvh1.08.03"))
        assertEquals(9, Media3Mapping.dolbyVisionProfile("dvav.09.05"))
        assertNull(Media3Mapping.dolbyVisionProfile("hvc1.2.4.L153.B0"))
        assertNull(Media3Mapping.dolbyVisionProfile("dvhe"))
        assertNull(Media3Mapping.dolbyVisionProfile(null))
    }

    @Test
    fun aVideoDecoderFailureIsAPictureThisDeviceCannotPlay() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
        )) {
            assertEquals(PlaybackFailure.UnsupportedVideo, Media3Mapping.failure(code, videoRenderer = true), "$code")
            // The sound's decoder failing is the source's problem, not the picture's.
            assertEquals(PlaybackFailure.Source, Media3Mapping.failure(code, videoRenderer = false), "$code")
        }
        assertEquals(PlaybackFailure.Source, Media3Mapping.failure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, videoRenderer = true))
        assertEquals(PlaybackFailure.Source, Media3Mapping.failure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, videoRenderer = true))
    }

    @Test
    fun theShownWidthFollowsThePixelAspect() {
        assertEquals(3840, Media3Mapping.displayWidth(3840, 1f))
        assertEquals(1920, Media3Mapping.displayWidth(1440, 4f / 3f))
        assertEquals(1920, Media3Mapping.displayWidth(1920, Float.NaN))
        assertEquals(0, Media3Mapping.displayWidth(0, 1f))
    }
}
