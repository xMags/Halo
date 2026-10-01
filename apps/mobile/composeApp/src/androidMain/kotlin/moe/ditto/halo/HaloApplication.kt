package moe.ditto.halo

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import moe.ditto.halo.auth.SystemEpochClock
import moe.ditto.halo.downloads.AndroidBackgroundDownloadPort
import moe.ditto.halo.downloads.AndroidDownloadRequestVault
import moe.ditto.halo.downloads.AndroidDownloadStorage
import moe.ditto.halo.downloads.DeviceDownloadRuntime
import moe.ditto.halo.downloads.DownloadIndex
import moe.ditto.halo.downloads.downloadFileSystem
import moe.ditto.halo.storage.AndroidPreferencesStore

/** Process-wide owner required by both WorkManager and the visible activity. */
class HaloApplication : Application() {
    internal lateinit var downloadRuntime: DeviceDownloadRuntime
        private set
    internal lateinit var backgroundDownloadPort: AndroidBackgroundDownloadPort
        private set
    internal lateinit var downloadStorage: AndroidDownloadStorage
        private set

    override fun onCreate() {
        super.onCreate()
        val store = AndroidPreferencesStore(this)
        val vault = AndroidDownloadRequestVault(this)
        downloadStorage = AndroidDownloadStorage(this)
        backgroundDownloadPort = AndroidBackgroundDownloadPort(this)
        downloadRuntime = DeviceDownloadRuntime(
            index = DownloadIndex(store, vault),
            storage = downloadStorage,
            port = backgroundDownloadPort,
            vault = vault,
            clock = SystemEpochClock,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            fileSystem = downloadFileSystem(),
        )
    }
}
