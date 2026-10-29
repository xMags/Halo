package moe.ditto.halo.device

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import moe.ditto.halo.api.HaloJson
import moe.ditto.halo.api.Manifest
import moe.ditto.halo.api.encodeUriComponent

/*
 * The Stremio addon protocol, spoken directly to addons by Halo without an
 * account. A Kotlin port of `packages/core/src/addon/client.ts`, which the API
 * server runs for signed-in users; keep the two in step. Addons are plain HTTP
 * servers and every resource is a GET returning JSON:
 *
 *   {transport}/manifest.json
 *   {transport}/{resource}/{type}/{id}.json
 *   {transport}/{resource}/{type}/{id}/{extra}.json
 *
 * where {transport} is the manifest URL minus the trailing /manifest.json and
 * {extra} is querystring-encoded (e.g. "skip=100&genre=Action").
 */

/** The addon answered with a non-success status. */
internal class AddonRequestException(val status: Int) : Exception("Addon request failed with $status")

/** The addon's answer is not JSON, or not the shape the resource requires. */
internal class InvalidAddonResponseException : Exception("Invalid addon response")

/** The addon did not answer within its budget. */
internal class AddonTimeoutException : Exception("Addon timed out")

/** Strips /manifest.json so both manifest URLs and bare base URLs are accepted. */
internal fun transportBase(transportUrl: String): String =
    transportUrl.removeSuffix("/manifest.json").removeSuffix("/")

internal fun manifestUrl(transportUrl: String): String = "${transportBase(transportUrl)}/manifest.json"

internal fun encodeExtra(extra: Map<String, String>): String =
    extra.entries.joinToString("&") { (key, value) -> "${key.encodeUriComponent()}=${value.encodeUriComponent()}" }

internal fun resourceUrl(
    transportUrl: String,
    resource: String,
    type: String,
    id: String,
    extra: Map<String, String> = emptyMap(),
): String {
    val base = "${transportBase(transportUrl)}/$resource/${type.encodeUriComponent()}/${id.encodeUriComponent()}"
    val extraPart = if (extra.isEmpty()) "" else "/${encodeExtra(extra)}"
    return "$base$extraPart.json"
}

/** One GET answered with JSON. Throws [AddonRequestException] or [InvalidAddonResponseException]. */
internal suspend fun HttpClient.getAddonJson(url: String): JsonElement {
    val response = get(url)
    if (!response.status.isSuccess()) throw AddonRequestException(response.status.value)
    val text = response.bodyAsText()
    return try {
        HaloJson.parseToJsonElement(text)
    } catch (_: SerializationException) {
        throw InvalidAddonResponseException()
    }
}

/**
 * Whether an addon serves [resource] for the given type and id. `resources`
 * mixes bare names (governed by the manifest-level `types` and `idPrefixes`)
 * and objects with their own constraints; the bare form arrives here with
 * null `types` and `idPrefixes`.
 */
internal fun Manifest.supportsResource(resource: String, type: String, id: String? = null): Boolean {
    fun matchesPrefixes(prefixes: List<String>?): Boolean =
        id.isNullOrEmpty() || prefixes == null || prefixes.any { id.startsWith(it) }

    return resources.any { entry ->
        when {
            entry.name != resource -> false
            entry.types == null && entry.idPrefixes == null -> type in types && matchesPrefixes(idPrefixes)
            else -> type in entry.types.orEmpty() && matchesPrefixes(entry.idPrefixes ?: idPrefixes)
        }
    }
}
