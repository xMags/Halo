package moe.ditto.halo.screens.signin

/**
 * The art behind the sign-in card: five posters fanned on the halo ring and
 * three backdrops that crossfade behind everything.
 *
 * Fetched at runtime from Metahub, the image host Cinemeta's own metadata
 * points at, like every poster the app shows; nothing is bundled. Signed out,
 * there is no addon list to ask for a catalog yet, so the titles are fixed.
 * A load that fails leaves the dark placeholder, which the card still reads on.
 */
internal object SignInArtwork {
    /** A poster's badge, as the app's own cards print them. */
    enum class Badge { None, Offline, Quality }

    data class Poster(val imdbId: String, val title: String, val badge: Badge = Badge.None) {
        val url: String get() = "https://images.metahub.space/poster/medium/$imdbId/img"
    }

    /** Left to right across the fan; the third is the large one in front. */
    val posters: List<Poster> = listOf(
        Poster("tt11280740", "Severance"),
        Poster("tt3581920", "The Last of Us", Badge.Offline),
        Poster("tt15398776", "Oppenheimer", Badge.Quality),
        Poster("tt4574334", "Stranger Things"),
        Poster("tt1375666", "Inception"),
    )

    val backdrops: List<String> = listOf("tt15239678", "tt2543164", "tt1877830")
        .map { "https://images.metahub.space/background/medium/$it/img" }
}
