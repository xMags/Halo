package moe.ditto.halo

/**
 * Launch values that automation supplies as extras on the intent starting
 * [MainActivity]: the server to prefill, the media route, and a request to wipe
 * the persisted session first.
 *
 * [MainActivity] is the launcher, so it must be exported, and any app on the
 * device can start it with any extras. Each override is therefore honoured only
 * in a debuggable build. A release build always uses the normal defaults, so
 * another app cannot sign the user out (a reset also revokes the refresh token)
 * or prefill the login form with a server of its choosing.
 */
internal data class LaunchOverrides(
    val serverUrl: String,
    val mediaHttpBase: String,
    val resetSession: Boolean,
) {
    companion object {
        fun resolve(
            diagnosticsEnabled: Boolean,
            serverUrl: String?,
            mediaHttpBase: String?,
            resetSession: Boolean,
        ): LaunchOverrides {
            if (!diagnosticsEnabled) return Defaults
            return LaunchOverrides(
                // With no override, the shared default preserves the
                // last-successful-server prefill rule in HaloApp.
                serverUrl = serverUrl ?: PlatformDependencies.DefaultServerUrl,
                mediaHttpBase = mediaHttpBase
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: PlatformDependencies.DefaultMediaHttpBase,
                resetSession = resetSession,
            )
        }

        private val Defaults = LaunchOverrides(
            serverUrl = PlatformDependencies.DefaultServerUrl,
            mediaHttpBase = PlatformDependencies.DefaultMediaHttpBase,
            resetSession = false,
        )
    }
}
