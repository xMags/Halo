package moe.ditto.halo.api

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decoding is asserted against payloads captured from live addons. Every case
 * here failed, or would have failed, under a default-configured decoder — the
 * point is the gap between what the protocol documents and what addons send.
 */
class AddonDtoTest {
    @Test
    fun decodesCinemetaManifestDespiteUndocumentedFields() {
        val manifest = HaloJson.decodeFromString<Manifest>(RealAddonPayloads.CinemetaManifest)

        assertEquals("com.linvo.cinemeta", manifest.id)
        assertEquals("Cinemeta", manifest.name)
        // `addonCatalogs` (top level) and `genres` (per catalog) are real fields
        // Cinemeta emits that the protocol never defined.
        assertEquals(8, manifest.catalogs.size)
        assertEquals(listOf("movie", "series"), manifest.types)
    }

    @Test
    fun readsShorthandStringResources() {
        val manifest = HaloJson.decodeFromString<Manifest>(RealAddonPayloads.CinemetaManifest)

        assertEquals(listOf("catalog", "meta", "addon_catalog"), manifest.resources.map { it.name })
        // The shorthand form constrains nothing, which is what null must mean here.
        assertTrue(manifest.resources.all { it.types == null && it.idPrefixes == null })
    }

    @Test
    fun readsObjectResourcesWithTheirConstraints() {
        val manifest = HaloJson.decodeFromString<Manifest>(RealAddonPayloads.TorrentioManifest)

        val stream = manifest.resources.single()
        assertEquals("stream", stream.name)
        assertEquals(listOf("movie", "series", "anime"), stream.types)
        assertEquals(listOf("tt", "kitsu"), stream.idPrefixes)
    }

    @Test
    fun coercesExplicitNullCollectionsToEmpty() {
        // Torrentio sends "idPrefixes": null at the manifest level; Cinemeta
        // sends "director": null on some metas. Both are explicit nulls where
        // the protocol implies omission.
        val torrentio = HaloJson.decodeFromString<Manifest>(RealAddonPayloads.TorrentioManifest)
        assertNull(torrentio.idPrefixes)

        val meta = HaloJson.decodeFromString<MetaResponse>(RealAddonPayloads.CinemetaMeta).meta
        assertEquals("Game of Thrones", meta.name)
        // "director": null in the payload, against a non-nullable List field.
        assertTrue(meta.director.isEmpty())
        assertTrue(meta.cast.isNotEmpty())
    }

    @Test
    fun decodesMinimalManifestWithoutOptionalBlocks() {
        val manifest = HaloJson.decodeFromString<Manifest>(RealAddonPayloads.OpenSubtitlesManifest)

        assertEquals("org.stremio.opensubtitlesv3", manifest.id)
        assertEquals(listOf("subtitles"), manifest.resources.map { it.name })
        assertTrue(manifest.catalogs.isEmpty())
        assertNull(manifest.behaviorHints)
    }

    @Test
    fun detectsSearchCapabilityInBothSpellings() {
        val manifest = HaloJson.decodeFromString<Manifest>(RealAddonPayloads.CinemetaManifest)

        // Cinemeta advertises search through `extra` and `extraSupported` at
        // once; search rows exist only for catalogs this returns true for.
        val searchable = manifest.catalogs.filter { it.supportsSearch }
        assertTrue(searchable.isNotEmpty(), "Cinemeta must expose searchable catalogs")

        val legacyOnly = ManifestCatalog(type = "movie", id = "legacy", extraSupported = listOf("search"))
        assertTrue(legacyOnly.supportsSearch)
        assertTrue(ManifestCatalog(type = "movie", id = "none").supportsSearch.not())
    }

    @Test
    fun decodesCatalogResponseIgnoringCacheDirectives() {
        // hasMore / cacheMaxAge / staleRevalidate / staleError ride along on
        // every Cinemeta catalog response.
        val catalog = HaloJson.decodeFromString<CatalogResponse>(RealAddonPayloads.CinemetaCatalog)

        assertEquals(2, catalog.metas.size)
        val first = catalog.metas.first()
        assertEquals("movie", first.type)
        assertTrue(first.name.isNotBlank())
        assertNotNull(first.poster)
    }

    @Test
    fun readsEpisodeTitlesFromWhicheverFieldTheAddonUsed() {
        val meta = HaloJson.decodeFromString<MetaResponse>(RealAddonPayloads.CinemetaMeta).meta

        assertEquals(3, meta.videos.size)
        // Cinemeta populates `name` and never `title`; reading only `title`
        // would render a season of blank episode rows.
        val video = meta.videos.first()
        assertNull(video.title)
        assertNotNull(video.name)
        assertEquals(video.name, video.displayTitle)
        assertNotNull(video.season)
    }

    @Test
    fun acceptsNumbersWhereAddonsDeclareStrings() {
        // Ratings and release years arrive unquoted from some addons; a strict
        // decoder rejects the whole catalog over one of them.
        val json = """{"id":"tt1","type":"movie","name":"Numeric","imdbRating":8.4,"releaseInfo":2011}"""

        val meta = HaloJson.decodeFromString<MetaPreview>(json)

        assertEquals("8.4", meta.imdbRating)
        assertEquals("2011", meta.releaseInfo)
    }

    @Test
    fun keepsResourceFormWhenReEncoding() {
        val shorthand = HaloJson.encodeToString(ManifestResource(name = "catalog"))
        assertEquals("\"catalog\"", shorthand)

        val detailed = HaloJson.encodeToString(ManifestResource(name = "stream", types = listOf("movie")))
        assertContains(detailed, "\"name\":\"stream\"")
        assertContains(detailed, "\"types\":[\"movie\"]")
    }

    @Test
    fun treatsUnknownPosterShapeAsPlainValue() {
        // An unrecognised shape must degrade to the default card, not fail the
        // catalog the way a strict enum would.
        val json = """{"id":"tt1","type":"movie","name":"Odd","posterShape":"hexagon"}"""

        assertEquals("hexagon", HaloJson.decodeFromString<MetaPreview>(json).posterShape)
    }

    @Test
    fun marksOnlyDirectUrlStreamsPlayable() {
        val playable = Stream(url = "https://cdn.example/video.mkv")
        val torrent = Stream(infoHash = "abc123", fileIdx = 0)

        assertTrue(playable.isPlayable)
        assertTrue(torrent.isPlayable.not())
    }
}
