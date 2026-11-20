import { magnetFileFor, type HaloClient, type MagnetFile, type MagnetRequest } from '@halo/core'
import { useSyncExternalStore } from 'react'
import { getAccountClient } from '../api'

/*
 * The last pasted magnet that came back ready, so a title page's Sources sheet
 * can offer its file as "Your magnet". Kept until another magnet is pasted or
 * the app closes, and only for the account it was asked through: a different
 * sign-in (another client) sees none. Its files are never listed anywhere.
 */

let resolved: { client: HaloClient; request: MagnetRequest } | null = null
const listeners = new Set<() => void>()

function publish(): void {
  for (const listener of listeners) listener()
}

export function rememberReadyMagnet(client: HaloClient, request: MagnetRequest): void {
  if (request.state !== 'ready') return
  resolved = { client, request }
  publish()
}

/** Another magnet was pasted: the previous one is no longer offered. */
export function forgetReadyMagnet(): void {
  if (!resolved) return
  resolved = null
  publish()
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** The file of the last ready magnet that stands for this video, for the signed-in account only. */
export function useMagnetFileFor(type: string, videoId: string): MagnetFile | null {
  const current = useSyncExternalStore(subscribe, () => resolved, () => resolved)
  if (!current || current.client !== getAccountClient()) return null
  return magnetFileFor(current.request, type, videoId)
}
