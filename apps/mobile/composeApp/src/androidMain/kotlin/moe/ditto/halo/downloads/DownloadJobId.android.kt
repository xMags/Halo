package moe.ditto.halo.downloads

import java.util.UUID

internal actual fun newDownloadJobId(): String = UUID.randomUUID().toString()
