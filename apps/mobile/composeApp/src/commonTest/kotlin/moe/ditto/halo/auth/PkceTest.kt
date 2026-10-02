package moe.ditto.halo.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PkceTest {

    @Test
    fun pkceChallengeMatchesTheRfc7636Example() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun randomTokensAreUnpaddedBase64UrlAndNeverRepeat() {
        val tokens = List(20) { Pkce.randomToken() }
        tokens.forEach { assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(it), it) }
        assertEquals(tokens.size, tokens.toSet().size)
    }
}
