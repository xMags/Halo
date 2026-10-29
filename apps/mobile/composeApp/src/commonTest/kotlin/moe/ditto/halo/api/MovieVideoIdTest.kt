package moe.ditto.halo.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MovieVideoIdTest {

    @Test
    fun aTmdbListedFilmPlaysAsTheImdbIdItsMetaNames() {
        val meta = HaloJson.decodeFromString(
            MetaResponse.serializer(),
            """{"meta":{"id":"tmdb:969681","type":"movie","name":"Brand New Day",""" +
                """"behaviorHints":{"defaultVideoId":"tt22084616","hasScheduledVideos":false}}}""",
        ).meta
        assertEquals("tt22084616", meta.movieVideoId)
        assertTrue(meta.playableFromPreview)
    }

    @Test
    fun withoutAHintAFilmPlaysAsItsOwnId() {
        // Cinemeta sends an explicit null.
        val cinemeta = HaloJson.decodeFromString(
            MetaResponse.serializer(),
            """{"meta":{"id":"tt0111161","type":"movie","name":"Shawshank","behaviorHints":{"defaultVideoId":null}}}""",
        ).meta
        assertEquals("tt0111161", cinemeta.movieVideoId)
        assertTrue(cinemeta.playableFromPreview)
        assertEquals("tt1", MetaPreview(id = "tt1", type = "movie", name = "x", behaviorHints = MetaBehaviorHints("  ")).movieVideoId)
    }

    @Test
    fun aTmdbCardWithoutItsHintIsOpenedRatherThanPlayed() {
        assertFalse(MetaPreview(id = "tmdb:969681", type = "movie", name = "x").playableFromPreview)
    }
}
