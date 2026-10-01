package moe.ditto.halo.auth

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

internal actual fun systemEpochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
