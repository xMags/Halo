package moe.ditto.halo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kotlinx.coroutines.flow.emptyFlow

/**
 * Android entry point. Assembles the exact same [PlatformDependencies] the iOS
 * `MainViewController` builds — only the concrete host implementations differ
 * (libmpv-on-SurfaceView here, MPVKit-on-UIView there). If the common shell
 * needed any Android-specific change to run, that would be a finding; it does
 * not.
 *
 * The host is created once and held across configuration changes (the manifest
 * declares configChanges so rotation does not recreate the activity), which is
 * how the mpv core survives rotation the way iOS's Swift-owned host does.
 */
class MainActivity : ComponentActivity() {

    private lateinit var playerHost: AndroidMpvPlayerHost
    private lateinit var authHost: AndroidStubAuthHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        authHost = AndroidStubAuthHost()
        playerHost = AndroidMpvPlayerHost(applicationContext)

        // Automation can override the prefilled server; auth is stubbed to local
        // so the value only seeds the login field.
        val serverUrl = intent?.getStringExtra("serverUrl") ?: "http://10.0.2.2:18787"

        val dependencies = PlatformDependencies(
            authConfigSource = authHost,
            nativeHostRequests = authHost,
            playerPort = AndroidPlayerPort(playerHost),
            playerEvents = playerHost.playerEvents,
            authEvents = emptyFlow(),
            nativePlayerSurface = AndroidNativePlayerSurface(playerHost),
            nativeHostDiagnostics = AndroidNativeHostDiagnostics(authHost, playerHost),
            initialServerUrl = serverUrl,
        )

        setContent {
            HaloGateApp(dependencies)
        }
    }
}
