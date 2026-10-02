package moe.ditto.halo.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AddonLoginPresenterTest {

    private val config = AuthConfig.Addon(FakeSignInAddon(), JsonObject(mapOf("mode" to JsonPrimitive("example"))))

    @Test
    fun addonDiscoveryWaitsForTheUserToStartTheSignIn() = runTest {
        val authenticator = RecordingAddonAuthenticator()
        val presenter = discovered(authenticator)

        val ready = assertIs<LoginPhase.AddonReady>(presenter.state.phase)
        assertEquals("https://halo.example", ready.serverUrl)
        assertSame(config, ready.config)
        assertTrue(authenticator.calls.isEmpty())
    }

    @Test
    fun aFinishedSignInIsReported() = runTest {
        val authenticator = RecordingAddonAuthenticator()
        val presenter = discovered(authenticator)

        val attempt = presenter.beginAddon()!!
        assertTrue(presenter.state.isBusy)
        presenter.completeAddon(attempt)

        assertEquals(LoginPhase.AddonSignedIn("https://halo.example"), presenter.state.phase)
        assertEquals(listOf("https://halo.example" to config), authenticator.calls)
    }

    @Test
    fun aRefusedSignInReturnsToTheButtonWithTheProvidersReason() = runTest {
        val presenter = discovered(RecordingAddonAuthenticator(SignInAddonException("This account is disabled.")))

        presenter.completeAddon(presenter.beginAddon()!!)

        assertIs<LoginPhase.AddonReady>(presenter.state.phase)
        assertEquals("This account is disabled.", presenter.state.error)
    }

    @Test
    fun aNetworkFailureIsShownAndTheButtonComesBack() = runTest {
        val presenter = discovered(RecordingAddonAuthenticator(IllegalStateException("Unable to resolve host")))

        presenter.completeAddon(presenter.beginAddon()!!)

        assertIs<LoginPhase.AddonReady>(presenter.state.phase)
        assertEquals("Unable to resolve host", presenter.state.error)
    }

    @Test
    fun anOutcomeAfterCancelIsDropped() = runTest {
        val release = CompletableDeferred<Unit>()
        val authenticator = AddonAuthenticator { _, _ -> release.await() }
        val presenter = discovered(authenticator)

        val attempt = presenter.beginAddon()!!
        val flight = launch { presenter.completeAddon(attempt) }
        advanceUntilIdle()
        presenter.cancelAddon()
        release.complete(Unit)
        flight.join()

        assertIs<LoginPhase.AddonReady>(presenter.state.phase)
        assertNull(presenter.state.error)
    }

    @Test
    fun beginningOutsideTheAddonPhaseDoesNothing() = runTest {
        val presenter = LoginPresenter(FixedConfig(AuthConfig.Local), NoHost, { _, _, _ -> }, null, RecordingAddonAuthenticator())
        presenter.editServerUrl("https://halo.local")
        presenter.continueFromServer()

        assertNull(presenter.beginAddon())
        assertIs<LoginPhase.LocalCredentials>(presenter.state.phase)
    }

    private suspend fun discovered(authenticator: AddonAuthenticator): LoginPresenter {
        val presenter = LoginPresenter(
            authConfigSource = FixedConfig(config),
            nativeHostRequests = NoHost,
            localAuthenticator = { _, _, _ -> error("local sign-in is not offered here") },
            addonAuthenticator = authenticator,
        )
        presenter.editServerUrl(" https://halo.example/ ")
        presenter.continueFromServer()
        return presenter
    }

    private class RecordingAddonAuthenticator(
        private val failure: Throwable? = null,
    ) : AddonAuthenticator {
        val calls = mutableListOf<Pair<String, AuthConfig.Addon>>()

        override suspend fun signInWithAddon(serverUrl: String, config: AuthConfig.Addon) {
            failure?.let { throw it }
            calls += serverUrl to config
        }
    }

    private class FixedConfig(private val config: AuthConfig) : AuthConfigSource {
        override suspend fun fetch(serverUrl: String): AuthConfig = config
    }

    private object NoHost : NativeHostRequests {
        override fun requestOidc(request: OidcHostRequest) = error("OIDC is not offered here")
    }
}
