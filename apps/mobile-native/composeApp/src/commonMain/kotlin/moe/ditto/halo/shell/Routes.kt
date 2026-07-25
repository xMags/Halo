package moe.ditto.halo.shell

import kotlinx.serialization.Serializable

/**
 * The shell's destinations, declared as types rather than route strings so that
 * navigating to one is checked by the compiler and resolvable by the IDE. A
 * typo in a string route is a runtime crash; a typo here does not build.
 *
 * Screens that cover the tab bar entirely — item detail, the stream picker,
 * the player — belong beside these rather than inside a tab, matching how the
 * shipping client pushes them above its tab navigator.
 */
@Serializable
data object HomeRoute

@Serializable
data object LibraryRoute

@Serializable
data object DownloadsRoute

@Serializable
data object SettingsRoute

/**
 * Search is a destination rather than a tab: it raises a keyboard and owns the
 * whole screen, and it is entered from Home's search field.
 */
@Serializable
data object SearchRoute

/**
 * One title's own screen.
 *
 * Carries the pair that addresses a title rather than the whole record, because
 * the record can be large and the screen has to fetch it anyway — but it carries
 * both halves rather than a joined library id, so nothing has to re-split a
 * string whose meta half may itself contain a colon.
 */
@Serializable
data class DetailRoute(val type: String, val metaId: String)

/**
 * One video's playable sources.
 *
 * [title] is carried rather than re-derived because only the screen that pushed
 * this one knows whether the video is a film or an episode of something, and
 * asking the cache again would answer nothing after a process death.
 */
@Serializable
data class StreamsRoute(val type: String, val videoId: String, val title: String)

/**
 * Playback of one resolved source.
 *
 * The URL travels in the route the way it does in the shipping client: it is
 * already resolved by the time a source is chosen, and holding it anywhere else
 * would mean a second owner of the thing the player is for.
 */
@Serializable
data class PlayerRoute(val url: String, val title: String)

/**
 * Destinations that replace the shell's chrome instead of living under it.
 *
 * The tab bar floats over content, so a screen like this would otherwise have a
 * translucent bar sitting on top of its own controls. Being in this list is all
 * a screen needs to do: it drives both the bar's visibility and the directional
 * push, so neither is decided screen by screen.
 */
internal val ChromeCoveringRoutes =
    listOf(SearchRoute::class, DetailRoute::class, StreamsRoute::class, PlayerRoute::class)
