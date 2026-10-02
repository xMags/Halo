package moe.ditto.halo.auth

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class AuthConfigTest {
    @Test
    fun parsesLocalUnionMember() {
        assertEquals(AuthConfig.Local, AuthConfigParser.parse("""{"mode":"local"}"""))
    }

    @Test
    fun parsesOidcUnionMember() {
        val config = AuthConfigParser.parse(
            """{"mode":"oidc","issuer":"https://auth.example/","clientId":"halo","scopes":["openid","groups"]}""",
        )

        val oidc = assertIs<AuthConfig.Oidc>(config)
        assertEquals("https://auth.example/", oidc.issuer)
        assertEquals("halo", oidc.clientId)
        assertEquals(listOf("openid", "groups"), oidc.scopes)
    }

    @Test
    fun aModeAnInstalledAddonKnowsCarriesTheAddonAndTheWholeConfig() {
        val addon = FakeSignInAddon(mode = "example")

        val config = AuthConfigParser.parse("""{"mode":"example","signInUrl":"https://sso.example"}""", listOf(addon))

        val parsed = assertIs<AuthConfig.Addon>(config)
        assertEquals(addon, parsed.addon)
        assertEquals("https://sso.example", parsed.fields["signInUrl"]?.jsonPrimitive?.content)
    }

    @Test
    fun aModeNoInstalledAddonKnowsIsRefusedByName() {
        val error = assertFailsWith<SerializationException> {
            AuthConfigParser.parse("""{"mode":"example"}""", listOf(FakeSignInAddon(mode = "other")))
        }
        assertEquals("This server signs in with \"example\", which this build does not include", error.message)
    }

    @Test
    fun rejectsUnknownUnionMember() {
        assertFailsWith<SerializationException> {
            AuthConfigParser.parse("""{"mode":"hybrid"}""")
        }
    }
}
