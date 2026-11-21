import { DeviceBackend } from '@halo/core'
import { fetch as nativeFetch } from '@tauri-apps/plugin-http'
import { forgetDeviceProfile, getAccountClient, getDeviceProfile, getServerUrl } from '../api'
import { showDialog } from '../components/Dialog'
import { adoptDownloads, opaqueDownloadOwner } from '../downloads/downloadsStore'
import { clearDeviceData, clearMovedRecords, deviceDownloadOwner, nativeDeviceStore } from './deviceStore'
import { moveRecordsIntoAccount, type AddonMove } from './mergeIntoAccount'

let running: Promise<boolean> | null = null

/**
 * Addons already named to the user as not moved. Only spares a repeat of the
 * dialog on later launches; losing it costs one more dialog, nothing else.
 */
const REPORTED_KEY = 'halo.unmovedAddonsReported'

/**
 * Moves this PC's data (records, addons, downloads) into the signed-in
 * account `accountUserId`, then clears it from the PC. Resolves true once
 * the move ran through. Any failure keeps the data on the PC, untouched,
 * and resolves false: the signed-in shell tries again on the next launch,
 * and every step is safe to repeat. Only one move runs at a time.
 *
 * Addons the account refused stay on the PC with the profile, so the next
 * launch tries them again and signing out shows them; everything else that
 * moved is cleared.
 */
export function moveDeviceDataIntoAccount(accountUserId: string): Promise<boolean> {
  running ??= run(accountUserId).finally(() => {
    running = null
  })
  return running
}

async function run(accountUserId: string): Promise<boolean> {
  const profile = getDeviceProfile()
  if (!profile) return true
  const account = getAccountClient()
  if (!account) return false
  try {
    // The account the move started for: if someone signs out (or another
    // person signs in) meanwhile, the rest must not go to the next account,
    // so the move stops and the PC keeps its data.
    const stillCurrent = () => getAccountClient() === account
    const device = new DeviceBackend({ fetch: nativeFetch, store: nativeDeviceStore })
    const { failedAddons } = await moveRecordsIntoAccount(device, account)
    if (!stillCurrent()) return false
    await adoptDownloads(
      await deviceDownloadOwner(profile),
      await opaqueDownloadOwner(getServerUrl(), accountUserId),
    )
    // Only after every part has moved: until then the PC keeps the only copy.
    if (!stillCurrent()) return false
    if (failedAddons.length === 0) {
      await clearDeviceData()
      forgetDeviceProfile()
      forgetReported()
      return true
    }
    // The addon list shrinks to the ones not moved before anything else is
    // removed; a move cut short here simply runs again. The list holds no
    // built-ins now, and Halo without an account tops them up when it opens.
    await device.putAddons(failedAddons.map((move) => move.transportUrl))
    await clearMovedRecords()
    void reportFailedAddons(failedAddons)
    return true
  } catch {
    return false
  }
}

function readReported(): Set<string> {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(REPORTED_KEY) ?? '[]')
    return new Set(Array.isArray(parsed) ? parsed.filter((url): url is string => typeof url === 'string') : [])
  } catch {
    return new Set()
  }
}

function forgetReported(): void {
  try {
    localStorage.removeItem(REPORTED_KEY)
  } catch {
    // Only a convenience.
  }
}

/** Names the addons not moved, once each: the launch retry stays quiet about ones already named. */
async function reportFailedAddons(failed: readonly AddonMove[]): Promise<void> {
  const reported = readReported()
  const fresh = failed.filter((move) => !reported.has(move.transportUrl))
  try {
    // Only the ones still kept are remembered, so one that moved and comes back is named again.
    localStorage.setItem(REPORTED_KEY, JSON.stringify(failed.map((move) => move.transportUrl)))
  } catch {
    // Only a convenience.
  }
  if (fresh.length === 0) return
  const one = fresh.length === 1
  await showDialog({
    title: one ? 'An addon was not moved' : 'Some addons were not moved',
    body: `${fresh.map((move) => move.name).join(', ')} could not be added to your account because ${one ? 'it' : 'they'} did not load. Halo kept ${one ? 'it' : 'them'} on this PC and tries again each time it starts. Signing out shows ${one ? 'it' : 'them'} again.`,
    close: 'OK',
  })
}
