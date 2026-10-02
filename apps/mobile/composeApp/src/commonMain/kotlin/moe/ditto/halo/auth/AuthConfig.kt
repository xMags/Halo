package moe.ditto.halo.auth

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

sealed interface AuthConfig {
    data object Local : AuthConfig

    data class Oidc(
        val issuer: String,
        val clientId: String,
        val scopes: List<String>,
    ) : AuthConfig

    /**
     * A mode only a [SignInAddon] knows. [fields] is the server's whole
     * `/auth/config` object, untrusted until the add-on has read it.
     */
    data class Addon(
        val addon: SignInAddon,
        val fields: JsonObject,
    ) : AuthConfig
}

object AuthConfigParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** [addons] are the modes beyond local and OIDC this build can sign in with. */
    fun parse(payload: String, addons: List<SignInAddon> = installedSignInAddons): AuthConfig {
        val value = json.parseToJsonElement(payload) as? JsonObject
            ?: throw SerializationException("Auth config must be a JSON object")
        return when (val mode = value.requiredString("mode")) {
            "local" -> AuthConfig.Local
            "oidc" -> AuthConfig.Oidc(
                issuer = value.requiredString("issuer"),
                clientId = value.requiredString("clientId"),
                scopes = value["scopes"]?.jsonArray?.map { it.jsonPrimitive.content }
                    ?: throw SerializationException("OIDC auth config is missing scopes"),
            )
            else -> addons.firstOrNull { it.mode == mode }?.let { AuthConfig.Addon(it, value) }
                ?: throw SerializationException("This server signs in with \"$mode\", which this build does not include")
        }
    }

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: throw SerializationException("Auth config is missing $key")
}
