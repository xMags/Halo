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
 * Destinations that replace the shell's chrome instead of living under it.
 *
 * The tab bar floats over content, so a screen like this would otherwise have a
 * translucent bar sitting on top of its own controls. Item detail, the stream
 * picker and the player join this list as they land.
 */
internal val ChromeCoveringRoutes = listOf(SearchRoute::class)
