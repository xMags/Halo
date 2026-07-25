package moe.ditto.halo.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decoding against the debrid addon this app is actually used with. Cinemeta
 * exercises catalogs and metadata; this exercises the stream payload, which is
 * where the values that drive playback, autoplay, and subtitle matching live.
 */
class TorrentioDecodeTest {
    private val manifest = HaloJson.decodeFromString<Manifest>(RealAddonPayloads.TorrentioTorBoxManifest)
    private val result = HaloJson.decodeFromString<StreamsResult>(RealAddonPayloads.TorrentioTorBoxStreams)
    private val streams = result.results.single().streams

    @Test
    fun decodesMultipleResourceDeclarations() {
        assertEquals("com.stremio.torrentio.addon", manifest.id)

        val byName = manifest.resources.associateBy { it.name }
        assertEquals(listOf("movie", "series", "anime"), byName.getValue("stream").types)
        // A second resource constrained to a different type and id prefix — the
        // object form carrying real constraints rather than a bare name.
        assertEquals(listOf("other"), byName.getValue("meta").types)
        assertEquals(listOf("torbox"), byName.getValue("meta").idPrefixes)
    }

    @Test
    fun decodesProviderCatalogs() {
        assertEquals(3, manifest.catalogs.size)
        assertTrue(manifest.catalogs.all { it.type == "other" })
        // None of these answer search, so none may become a search row.
        assertTrue(manifest.catalogs.none { it.supportsSearch })
    }

    @Test
    fun decodesFileSizesBeyondThirtyTwoBits() {
        // A 4K remux is comfortably past Int.MAX_VALUE; typing this as Int
        // would throw on ordinary real-world results rather than an edge case.
        val sizes = streams.mapNotNull { it.behaviorHints?.videoSize }

        assertTrue(sizes.any { it > Int.MAX_VALUE.toLong() }, "expected a size past 32 bits: $sizes")
        assertEquals(34249807367L, sizes.max())
    }

    @Test
    fun readsAddonSuppliedSubtitleHashes() {
        // The addon already publishes the OpenSubtitles hash for most results.
        // Preferring it costs nothing and avoids two range requests against a
        // host that may refuse them.
        val hashed = streams.filter { it.behaviorHints?.videoHash != null }

        assertTrue(hashed.isNotEmpty())
        val hash = hashed.first().behaviorHints?.videoHash
        assertNotNull(hash)
        assertEquals(16, hash.length, "OpenSubtitles hashes are 16 hex characters")
        assertTrue(hash.all { it in "0123456789abcdef" })
    }

    @Test
    fun toleratesStreamsWithoutHintsOfTheirOwn() {
        val unhashed = streams.filter { it.behaviorHints?.videoHash == null }

        assertTrue(unhashed.isNotEmpty(), "fixture must cover the hash-absent path")
        assertNull(unhashed.first().behaviorHints?.videoSize)
    }

    @Test
    fun readsPerTorrentBingeGroups() {
        // Groups are scoped to the torrent, not the quality tier, so binge
        // continuation only holds while consecutive episodes resolve to the
        // same release — otherwise the caller falls back to the picker.
        val groups = streams.mapNotNull { it.behaviorHints?.bingeGroup }

        assertTrue(groups.isNotEmpty())
        assertTrue(groups.all { it.startsWith("torrentio|") })
        assertEquals(groups.size, groups.toSet().size, "each release is its own group")
    }

    @Test
    fun keepsMultilineAndNonAsciiLabelsIntact() {
        // Torrentio packs quality, seeders, and flags into newline-separated
        // labels with emoji; they decode verbatim and the UI must expect them.
        val stream = streams.first()

        assertTrue(stream.name.orEmpty().contains("\n"))
        assertTrue(stream.title.orEmpty().contains("\n"))
        assertTrue(stream.title.orEmpty().any { it.code > 127 })
    }

    @Test
    fun everyResultIsDirectlyPlayable() {
        // Debrid resolution means real URLs rather than infoHashes, which is
        // the only stream kind this app plays.
        assertTrue(streams.all { it.isPlayable })
        assertTrue(streams.none { it.infoHash != null })
    }

    @Test
    fun decodesTheServerEnvelopeAroundTheResults() {
        // The app never sees a bare addon response: the server groups streams
        // by the addon that produced them and reports per-addon failures
        // alongside, rather than failing the whole request.
        assertEquals("Torrentio TB", result.results.single().addon.name)
        assertTrue(result.errors.isEmpty())
        assertEquals(3, streams.size)
    }
}
