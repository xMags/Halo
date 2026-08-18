package moe.ditto.halo.downloads

/** A cryptographically random, opaque platform job identity. */
internal expect fun newDownloadJobId(): String
