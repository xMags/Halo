package moe.ditto.halo.people

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

// OkHttp keeps no response cache unless one is configured, and none is.
internal actual fun tmdbHttpClient(): HttpClient = HttpClient(OkHttp)
