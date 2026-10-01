package moe.ditto.halo.downloads

import okio.FileSystem

internal actual fun downloadFileSystem(): FileSystem = FileSystem.SYSTEM
