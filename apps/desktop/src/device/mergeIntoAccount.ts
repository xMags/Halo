import {
  DEFAULT_ADDON_URLS,
  HaloApiError,
  type AddonEntry,
  type HaloBackend,
  type HaloClient,
  type SettingsPayload,
} from '@halo/core'

/**
 * Moving Halo-without-an-account data into the account someone signs in to:
 * library and watch history merge with the account's (newest change wins, the
 * server's rule), the addons they added themselves join the account's list,
 * this PC's settings are used if the account has none yet, and downloads
 * change owner. Every step is safe to repeat, so a move that failed partway
 * simply runs again.
 */

/** Rows per request, so a long history is not one huge body. */
export const MOVE_CHUNK_SIZE = 500

export interface AddonMove {
  transportUrl: string
  name: string
  hideCatalogs: boolean
}

export interface MoveResult {
  /** Addons the account refused because their manifest did not load; named to the user. */
  failedAddons: string[]
}

/**
 * The addons to add to the account: the ones added on this PC, in their
 * order, minus Cinemeta and OpenSubtitles (every account installs those
 * itself) and minus any the account already has in its own list.
 */
export function addonsToMove(device: readonly AddonEntry[], accountOwn: readonly AddonEntry[]): AddonMove[] {
  const builtIn = new Set<string>(DEFAULT_ADDON_URLS)
  const present = new Set(accountOwn.flatMap((addon) => (addon.transportUrl ? [addon.transportUrl] : [])))
  return device.flatMap((addon) => {
    const url = addon.transportUrl
    if (!url || builtIn.has(url) || present.has(url)) return []
    return [{ transportUrl: url, name: addon.manifest.name, hideCatalogs: addon.hideCatalogs === true }]
  })
}

/**
 * Settings are one document, saved whole and newest-wins, so this PC's copy
 * would overwrite anything tuned on another device. It is used only when the
 * account has never saved any.
 */
export function shouldMoveSettings(device: SettingsPayload, account: SettingsPayload): boolean {
  return account.updatedAt === 0 && device.updatedAt > 0
}

export function chunked<T>(items: readonly T[], size: number = MOVE_CHUNK_SIZE): T[][] {
  const chunks: T[][] = []
  for (let start = 0; start < items.length; start += size) chunks.push(items.slice(start, start + size))
  return chunks
}

/**
 * Sends this PC's library, watch history, settings and addons to the
 * account. Throws when the account cannot be reached or refuses something
 * other than an addon; the caller keeps the device data and tries again. An
 * addon whose manifest the account cannot load is skipped and reported
 * instead, so one dead addon cannot hold everything else back.
 */
export async function moveRecordsIntoAccount(device: HaloBackend, account: HaloClient): Promise<MoveResult> {
  for (const chunk of chunked(await device.getLibrary())) await account.putLibrary(chunk)
  for (const chunk of chunked(await device.getWatchStates())) await account.putWatchStates(chunk)

  const [deviceSettings, accountSettings] = await Promise.all([device.getSettings(), account.getSettings()])
  if (shouldMoveSettings(deviceSettings, accountSettings)) {
    await account.putSettings(deviceSettings.value, deviceSettings.updatedAt)
  }

  const [deviceAddons, accountAddons] = await Promise.all([device.getAddons(), account.getAddons()])
  let own = accountAddons.user.flatMap((addon) => (addon.transportUrl ? [addon.transportUrl] : []))
  const failedAddons: string[] = []
  // One at a time: the account installs a list all-or-nothing, so adding
  // them together would let one dead addon refuse every other.
  for (const move of addonsToMove(deviceAddons.user, accountAddons.user)) {
    let installed: AddonEntry[]
    try {
      installed = await account.putAddons([...own, move.transportUrl])
    } catch (err) {
      if (err instanceof HaloApiError && err.status === 400) {
        failedAddons.push(move.name)
        continue
      }
      throw err
    }
    own = installed.flatMap((addon) => (addon.transportUrl ? [addon.transportUrl] : []))
    const entry = installed.find((addon) => addon.transportUrl === move.transportUrl)
    if (move.hideCatalogs && entry) await account.patchAddon(entry.id, { hideCatalogs: true })
  }
  return { failedAddons }
}
