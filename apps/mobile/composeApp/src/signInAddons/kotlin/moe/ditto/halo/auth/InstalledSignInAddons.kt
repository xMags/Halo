package moe.ditto.halo.auth

/**
 * The sign-in add-ons this build carries: none. A local build swaps this
 * source folder for the git-ignored `local/mobile` folder, which declares its
 * own list under the same name (see [SignInAddon] and build.gradle.kts).
 */
internal val installedSignInAddons: List<SignInAddon> = emptyList()
