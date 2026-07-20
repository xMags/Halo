package moe.ditto.halo.ui

import kotlin.math.min

enum class DeviceClass {
    Phone,
    Tablet,
    LargeTablet,
}

data class ResponsiveInfo(
    val deviceClass: DeviceClass,
    val shortestEdgeDp: Float,
) {
    val isTablet: Boolean = deviceClass != DeviceClass.Phone
}

fun classifyWindow(widthDp: Float, heightDp: Float): ResponsiveInfo {
    require(widthDp >= 0f && heightDp >= 0f) { "Window dimensions must be non-negative" }
    val shortestEdge = min(widthDp, heightDp)
    val deviceClass = when {
        shortestEdge < 600f -> DeviceClass.Phone
        shortestEdge < 768f -> DeviceClass.Tablet
        else -> DeviceClass.LargeTablet
    }
    return ResponsiveInfo(deviceClass = deviceClass, shortestEdgeDp = shortestEdge)
}
