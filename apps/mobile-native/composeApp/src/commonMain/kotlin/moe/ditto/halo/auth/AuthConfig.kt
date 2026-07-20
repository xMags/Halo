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
}

object AuthConfigParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(payload: String): AuthConfig {
        val value = json.parseToJsonElement(payload) as? JsonObject
            ?: throw SerializationException("Auth config must be a JSON object")
        return when (value.requiredString("mode")) {
            "local" -> AuthConfig.Local
            "oidc" -> AuthConfig.Oidc(
                issuer = value.requiredString("issuer"),
                clientId = value.requiredString("clientId"),
                scopes = value["scopes"]?.jsonArray?.map { it.jsonPrimitive.content }
                    ?: throw SerializationException("OIDC auth config is missing scopes"),
            )
            else -> throw SerializationException("Unsupported auth mode")
        }
    }

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: throw SerializationException("Auth config is missing $key")
}
