package moe.ditto.halo.auth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PasswordSignInTest {
    private class Config(private val result: () -> AuthConfig) : AuthConfigSource {
        override suspend fun fetch(serverUrl: String): AuthConfig = result()
    }

    private object NoHost : NativeHostRequests {
        override fun requestOidc(request: OidcHostRequest) = error("a password sign-in never opens a browser")
    }

    private fun presenter(config: () -> AuthConfig, authenticator: LocalAuthenticator) =
        LoginPresenter(Config(config), NoHost, authenticator)

    @Test
    fun aPasswordServerSignsInWithTheTrimmedAddress() = runTest {
        val calls = mutableListOf<Triple<String, String, String>>()
        val presenter = presenter({ AuthConfig.Local() }) { server, user, pass -> calls += Triple(server, user, pass) }

        val outcome = presenter.signInWithPassword(" https://halo.local/ ", " admin ", "secret")

        assertEquals(PasswordSignInOutcome.SignedIn, outcome)
        assertEquals(listOf(Triple("https://halo.local", "admin", "secret")), calls)
    }

    @Test
    fun aRejectionKeepsTheServersMessage() = runTest {
        val presenter = presenter({ AuthConfig.Local() }) { _, _, _ -> throw LocalAuthException(401, "invalid credentials") }

        assertEquals(
            PasswordSignInOutcome.Failed("invalid credentials"),
            presenter.signInWithPassword("https://halo.local", "admin", "wrong"),
        )
    }

    @Test
    fun anUnreachableServerSaysSo() = runTest {
        val presenter = presenter({ throw IllegalStateException("Could not connect") }) { _, _, _ -> }

        assertEquals(
            PasswordSignInOutcome.Failed("Could not connect"),
            presenter.signInWithPassword("https://halo.local", "admin", "secret"),
        )
    }

    @Test
    fun aServerThatSignsInThroughAWebPageIsNotAPasswordServer() = runTest {
        val presenter = presenter({ AuthConfig.Oidc(issuer = "https://id.example", clientId = "halo", scopes = listOf("openid")) }) { _, _, _ ->
            error("must not submit a password")
        }

        assertEquals(
            PasswordSignInOutcome.NotPasswordServer,
            presenter.signInWithPassword("https://halo.example", "admin", "secret"),
        )
    }

    @Test
    fun aSecondTryAfterAFailureStartsFromTheServerAgain() = runTest {
        var reachable = false
        val presenter = presenter({ if (reachable) AuthConfig.Local() else throw IllegalStateException("down") }) { _, _, _ -> }

        presenter.signInWithPassword("https://halo.local", "admin", "secret")
        reachable = true

        assertEquals(PasswordSignInOutcome.SignedIn, presenter.signInWithPassword("https://halo.local", "admin", "secret"))
    }
}
