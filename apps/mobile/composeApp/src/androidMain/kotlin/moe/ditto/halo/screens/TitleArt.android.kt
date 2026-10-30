package moe.ditto.halo.screens

import coil3.request.ImageRequest
import coil3.request.allowHardware

internal actual fun ImageRequest.Builder.keepPixelsReadable(): ImageRequest.Builder = allowHardware(false)
