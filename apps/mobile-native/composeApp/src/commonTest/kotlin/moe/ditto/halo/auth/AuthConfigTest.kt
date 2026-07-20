package moe.ditto.halo.auth

import kotlinx.serialization.SerializationException
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
    fun rejectsUnknownUnionMember() {
        assertFailsWith<SerializationException> {
            AuthConfigParser.parse("""{"mode":"hybrid"}""")
        }
    }
}
