package moe.ditto.halo.auth

/**
 * The Halo server this build signs in to by default: none. The sign-in screen
 * then falls back to the last server used, else the public default. A local
 * build swaps this source folder for the git-ignored `local/mobile` folder,
 * which names its own server under the same name, so the address of a private
 * deployment never enters the repository (see build.gradle.kts).
 */
internal val builtInServerUrl: String? = null
