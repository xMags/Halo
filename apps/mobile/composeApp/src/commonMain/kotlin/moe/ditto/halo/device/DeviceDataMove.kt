package moe.ditto.halo.device

import moe.ditto.halo.api.AddonEntry
import moe.ditto.halo.api.HaloApiException
import moe.ditto.halo.api.HaloBackend
import moe.ditto.halo.api.SettingsPayload

/*
 * Moving Halo-without-an-account data into the account someone signs in to:
 * the library and watch history merge with the account's (newest change wins,
 * the server's rule), the addons added on the phone join the account's own
 * list, and the phone's settings are used if the account has none yet. A
 * port of the desktop's `src/device/mergeIntoAccount.ts`. Downloads need no
 * move: on the phone they are not kept per account.
 *
 * Every step is safe to repeat, so a move that failed partway simply runs
 * again on the next launch, and the phone keeps its copy until one succeeds.
 */

/** Rows per request, so a long history is not one huge body. */
internal const val MoveChunkSize = 500

/**
 * Addons preinstalled for Halo without an account so it is not empty on first
 * use: Cinemeta for catalogs and metadata, OpenSubtitles for subtitles, and
 * The Movie Database addon for TMDB's catalogs, as an unconfigured public
 * instance (a configured URL carries its owner's keys). The same list as
 * `packages/core/src/addon/constants.ts`.
 */
internal val DefaultAddonUrls = listOf(
    "https://v3-cinemeta.strem.io/manifest.json",
    "https://opensubtitles-v3.strem.io/manifest.json",
    "https://tmdb.elfhosted.com/manifest.json",
)

internal data class AddonMove(val transportUrl: String, val name: String, val hideCatalogs: Boolean)

/**
 * The addons to add to the account: the ones added on the phone, in their
 * order, minus the preinstalled ones (every account has its own) and minus any
 * the account already has in its own list.
 */
internal fun addonsToMove(device: List<AddonEntry>, accountOwn: List<AddonEntry>): List<AddonMove> {
    val present = accountOwn.mapNotNullTo(HashSet()) { it.transportUrl }
    return device.mapNotNull { addon ->
        val url = addon.transportUrl ?: return@mapNotNull null
        if (url in DefaultAddonUrls || url in present) return@mapNotNull null
        AddonMove(url, addon.manifest.name, addon.hideCatalogs)
    }
}

/**
 * Settings are one document, saved whole and newest-wins, so the phone's copy
 * would overwrite anything tuned on another device. It is used only when the
 * account has never saved any.
 */
internal fun shouldMoveSettings(device: SettingsPayload, account: SettingsPayload): Boolean =
    account.updatedAt == 0L && device.updatedAt > 0L

/**
 * Sends the phone's records and addons to [account] and returns the names of
 * addons the account refused because their manifest did not load, so one dead
 * addon cannot hold everything else back. Throws when the account cannot be
 * reached or refuses anything else; the caller keeps the phone's data.
 */
internal suspend fun moveRecordsIntoAccount(device: HaloBackend, account: HaloBackend): List<String> {
    device.getLibrary().chunked(MoveChunkSize).forEach { account.putLibrary(it) }
    device.getWatchStates().chunked(MoveChunkSize).forEach { account.putWatchStates(it) }

    val deviceSettings = device.getSettings()
    if (shouldMoveSettings(deviceSettings, account.getSettings())) {
        account.putSettings(deviceSettings.value, deviceSettings.updatedAt)
    }

    val accountAddons = account.getAddons()
    var own = accountAddons.user.mapNotNull { it.transportUrl }
    val failed = mutableListOf<String>()
    // One at a time: the account installs a list all or nothing, so adding
    // them together would let one dead addon refuse every other.
    for (move in addonsToMove(device.getAddons().user, accountAddons.user)) {
        val installed = try {
            account.putAddons(own + move.transportUrl)
        } catch (refused: HaloApiException) {
            if (refused.status != 400) throw refused
            failed += move.name
            continue
        }
        own = installed.mapNotNull { it.transportUrl }
        val entry = installed.firstOrNull { it.transportUrl == move.transportUrl }
        if (move.hideCatalogs && entry != null) account.patchAddon(entry.id, hideCatalogs = true)
    }
    return failed
}
