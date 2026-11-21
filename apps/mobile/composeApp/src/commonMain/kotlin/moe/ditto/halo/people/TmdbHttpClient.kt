package moe.ditto.halo.people

import io.ktor.client.HttpClient

/**
 * The HTTP client for TMDB alone. A TMDB API key travels in the URL query,
 * and TMDB marks its answers cacheable, so a client with a URL cache could
 * write the key to disk with them. This one keeps no response cache.
 */
internal expect fun tmdbHttpClient(): HttpClient
