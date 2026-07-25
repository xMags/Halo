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
