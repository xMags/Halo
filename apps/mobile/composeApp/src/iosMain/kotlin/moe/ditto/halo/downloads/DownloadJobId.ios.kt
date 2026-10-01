package moe.ditto.halo.downloads

import platform.Foundation.NSUUID

internal actual fun newDownloadJobId(): String = NSUUID().UUIDString()
