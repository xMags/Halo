package moe.ditto.halo.device

import moe.ditto.halo.api.LibraryItem
import moe.ditto.halo.api.UserSettings
import moe.ditto.halo.api.WatchState
import moe.ditto.halo.auth.InMemorySecureStorage
import moe.ditto.halo.sync.FakeStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The account side is a second [DeviceBackend]: it keeps the server's rules
 * (newest wins, a list installed all or nothing, a 400 for a manifest that
 * does not load), which is everything the move relies on.
 */
class DeviceDataMoveTest {

    @Test
    fun recordsMergeWithTheAccountsNewestWinning() = realTimeTest {
        val phone = Fixture().backend
        val account = Fixture().backend
        phone.putLibrary(listOf(item("movie:tt1", 20, "Phone"), item("movie:tt2", 5, "Only on phone")))
        account.putLibrary(listOf(item("movie:tt1", 30, "Account")))
        phone.putWatchStates(listOf(watch("tt1", 40, 55.0)))
        account.putWatchStates(listOf(watch("tt1", 10, 5.0)))

        moveRecordsIntoAccount(phone, account)

        assertEquals(
            mapOf("movie:tt1" to "Account", "movie:tt2" to "Only on phone"),
            account.getLibrary().associate { it.id to it.name },
        )
        assertEquals(55.0, account.getWatchStates().single().positionSec)
    }

    @Test
    fun aRemovalMadeOnThePhoneOutlastsTheAccountsOlderCopy() = realTimeTest {
        val phone = Fixture().backend
        val account = Fixture().backend
        account.putWatchStates(listOf(watch("tt1", 10, 30.0)))
        phone.putWatchStates(listOf(watch("tt1", 20, 40.0)))
        phone.putWatchStates(listOf(watch("tt1", 25, 40.0).copy(removedAt = 25)))

        moveRecordsIntoAccount(phone, account)

        assertTrue(account.getWatchStates().isEmpty())
    }

    @Test
    fun settingsMoveOnlyIntoAnAccountThatHasNone() = realTimeTest {
        val phone = Fixture().backend
        phone.putSettings(UserSettings.Empty.withPreferredSubtitleLang("eng"), 10)

        val fresh = Fixture().backend
        moveRecordsIntoAccount(phone, fresh)
        assertEquals("eng", fresh.getSettings().value.preferredSubtitleLang)

        val tuned = Fixture().backend
        tuned.putSettings(UserSettings.Empty.withPreferredSubtitleLang("fre"), 5)
        moveRecordsIntoAccount(phone, tuned)
        assertEquals("fre", tuned.getSettings().value.preferredSubtitleLang)
    }

    @Test
    fun addedAddonsJoinTheAccountOneAtATimeAndADeadOneIsReportedNotFatal() = realTimeTest {
        val dead = "https://dead.test/manifest.json"
        val phone = Fixture(
            mapOf(
                DefaultAddonUrls[0] to manifestJson(manifest("cinemeta")),
                dead to manifestJson(manifest("Dead addon")),
            ),
        ).backend
        val installed = phone.putAddons(listOf(DefaultAddonUrls[0], "https://stream-a.test/manifest.json", dead))
        phone.patchAddon(installed[1].id, hideCatalogs = true)
        // The account's copy of the dead addon no longer loads.
        val account = Fixture(mapOf(DefaultAddonUrls[0] to manifestJson(manifest("cinemeta")))).backend
        account.putAddons(listOf(DefaultAddonUrls[0], "https://stream-b.test/manifest.json"))

        val notMoved = moveRecordsIntoAccount(phone, account)

        assertEquals(listOf("Dead addon"), notMoved)
        val own = account.getAddons().user
        assertEquals(
            listOf(DefaultAddonUrls[0], "https://stream-b.test/manifest.json", "https://stream-a.test/manifest.json"),
            own.map { it.transportUrl },
        )
        assertTrue(own.last().hideCatalogs)
        assertFalse(own.first().hideCatalogs)
    }

    @Test
    fun theStoreKeepsAddonsSecretAndClearsEverythingWithTheProfileLast() {
        val secure = InMemorySecureStorage()
        val plain = FakeStore()
        val store = PlatformDeviceStore(secure, plain)
        assertFalse(store.hasProfile())

        store.createProfile()
        store.write(DeviceCollection.Addons, "addons")
        store.write(DeviceCollection.Library, "library")

        assertTrue(store.hasProfile())
        assertEquals("addons", store.read(DeviceCollection.Addons))
        assertTrue(plain.values.values.none { it == "addons" })

        store.clear()
        assertFalse(store.hasProfile())
        assertNull(store.read(DeviceCollection.Addons))
        assertNull(store.read(DeviceCollection.Library))
        assertTrue(plain.values.isEmpty())
    }

    private fun item(id: String, updatedAt: Long, name: String) =
        LibraryItem(id = id, type = "movie", name = name, addedAt = 1, updatedAt = updatedAt)

    private fun watch(videoId: String, updatedAt: Long, positionSec: Double) = WatchState(
        videoId = videoId,
        itemId = "movie:$videoId",
        positionSec = positionSec,
        durationSec = 100.0,
        watched = false,
        updatedAt = updatedAt,
    )
}
