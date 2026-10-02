import { DeviceBackend } from '@halo/core'
import { fetch as nativeFetch } from '@tauri-apps/plugin-http'
import { forgetDeviceProfile, getAccountClient, getDeviceProfile, getServerUrl } from '../api'
import { showDialog } from '../components/Dialog'
import { adoptDownloads, opaqueDownloadOwner } from '../downloads/downloadsStore'
import { clearDeviceData, deviceDownloadOwner, nativeDeviceStore } from './deviceStore'
import { moveRecordsIntoAccount } from './mergeIntoAccount'

let running: Promise<boolean> | null = null

/**
 * Moves this PC's data (records, addons, downloads) into the signed-in
 * account `accountUserId`, then clears it from the PC. Resolves true once
 * nothing is left to move. Any failure keeps the data on the PC, untouched,
 * and resolves false: the signed-in shell tries again on the next launch,
 * and every step is safe to repeat. Only one move runs at a time.
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
    const device = new DeviceBackend({ fetch: nativeFetch, store: nativeDeviceStore })
    const { failedAddons } = await moveRecordsIntoAccount(device, account)
    await adoptDownloads(
      await deviceDownloadOwner(profile),
      await opaqueDownloadOwner(getServerUrl(), accountUserId),
    )
    // Only after every part has moved: until then the PC keeps the only copy.
    await clearDeviceData()
    forgetDeviceProfile()
    if (failedAddons.length > 0) void reportFailedAddons(failedAddons)
    return true
  } catch {
    return false
  }
}

function reportFailedAddons(names: readonly string[]): Promise<boolean> {
  const list = names.join(', ')
  return showDialog({
    title: names.length === 1 ? 'An addon was not moved' : 'Some addons were not moved',
    body: `${list} could not be added to your account because ${names.length === 1 ? 'it' : 'they'} did not load. You can add ${names.length === 1 ? 'it' : 'them'} again in Settings.`,
    close: 'OK',
  })
}
