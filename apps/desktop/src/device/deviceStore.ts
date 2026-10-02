import type { DeviceStore } from '@halo/core'
import { invoke } from '@tauri-apps/api/core'
import { opaqueDownloadOwner } from '../downloads/downloadsLogic'

/**
 * Halo without an account keeps its documents through the native shell
 * (device_store.rs), in the app's local data folder rather than the WebView2
 * profile: on this device they are the only copy.
 */
export const nativeDeviceStore: DeviceStore = {
  read: (collection) => invoke<string | null>('device_store_read', { collection }),
  write: (collection, contents) => invoke<void>('device_store_write', { collection, contents }),
}

/**
 * Marks that this PC is used without an account. Its id partitions the
 * downloads made in this mode; it exists until the data moves into an
 * account on sign-in.
 */
export interface DeviceProfile {
  version: 1
  id: string
  createdAt: number
}

/** The saved profile, or null when Halo has not been used here without an account. */
export async function readDeviceProfile(): Promise<DeviceProfile | null> {
  const raw = await invoke<string | null>('device_store_read', { collection: 'profile' })
  if (raw === null) return null
  try {
    const parsed: unknown = JSON.parse(raw)
    return isDeviceProfile(parsed) ? parsed : null
  } catch {
    return null
  }
}

export async function createDeviceProfile(): Promise<DeviceProfile> {
  const profile: DeviceProfile = { version: 1, id: crypto.randomUUID(), createdAt: Date.now() }
  await invoke<void>('device_store_write', { collection: 'profile', contents: JSON.stringify(profile) })
  return profile
}

/**
 * The downloads partition for Halo without an account. "device" can never be
 * an account's server URL, so it cannot collide with an account's partition.
 */
export function deviceDownloadOwner(profile: DeviceProfile): Promise<string> {
  return opaqueDownloadOwner('device', profile.id)
}

/** Removes the profile and every document, once their contents live in an account. */
export function clearDeviceData(): Promise<void> {
  return invoke<void>('device_store_clear')
}

function isDeviceProfile(value: unknown): value is DeviceProfile {
  if (typeof value !== 'object' || value === null) return false
  const candidate = value as Record<string, unknown>
  return (
    candidate.version === 1 &&
    typeof candidate.id === 'string' &&
    candidate.id.length > 0 &&
    typeof candidate.createdAt === 'number'
  )
}
