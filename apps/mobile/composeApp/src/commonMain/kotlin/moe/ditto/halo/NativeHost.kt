package moe.ditto.halo

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

data class NativeHostSnapshot(
    val authHostId: String,
    val playerHostId: String,
    val playerInstanceId: String,
    val playerViewInstanceId: String,
    val coreCreationCount: Long,
    val coreDestructionCount: Long,
    val playerViewCreationCount: Long,
    val attachCount: Long,
    val resizeCount: Long,
    val detachCount: Long,
    val loadCount: Long,
    val teardownCount: Long,
    val oidcRequestCount: Long,
)

internal interface NativePlayerSurface {
    @Composable
    fun Content(modifier: Modifier)
}

internal interface NativeHostDiagnostics {
    fun snapshot(): NativeHostSnapshot
    fun destroyAndRecreatePlayerCore()
}
