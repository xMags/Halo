package moe.ditto.halo.people

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData

// The default session configuration writes cacheable answers to the shared
// NSURLCache on disk, URL included; this session has no URL cache at all.
internal actual fun tmdbHttpClient(): HttpClient = HttpClient(Darwin) {
    engine {
        configureSession {
            setURLCache(null)
            setRequestCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
        }
    }
}
