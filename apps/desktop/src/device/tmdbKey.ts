import { parseTmdbCredential, type TmdbCredential } from '@halo/core'
import { invoke } from '@tauri-apps/api/core'
import { fetch as nativeFetch } from '@tauri-apps/plugin-http'
import { useSyncExternalStore } from 'react'

/*
 * The viewer's own TMDB key, for people search and IMDb/TVDB links without an
 * account. It lives DPAPI-protected through the native shell (device_store.rs,
 * `tmdbKey`), stays on this PC (it never moves into an account) and is never
 * shown again once saved: the webview keeps it in memory only to hand it to
 * the on-device backend, which sends it to TMDB and nowhere else.
 */

interface StoredKey {
  version: 1
  credential: TmdbCredential
  /** TMDB answered 401 for it since it was saved: the features stay off until it is replaced. */
  refused: boolean
}

/**
 * What screens may know about the key: whether one is saved and whether TMDB
 * still takes it. `loading` until the protected read first answers, so no
 * screen mistakes a saved key for none while it is read.
 */
export type TmdbKeyStatus = 'loading' | 'none' | 'working' | 'refused'

let loaded: Promise<StoredKey | null> | null = null
let status: TmdbKeyStatus = 'loading'
const listeners = new Set<() => void>()
/**
 * Every change to the stored key, one after another. A change reads what is
 * stored when its turn comes, so a refusal that lands while the key is being
 * removed or replaced cannot write the old key back.
 */
let changes: Promise<unknown> = Promise.resolve()

function serialized<T>(change: () => Promise<T>): Promise<T> {
  const run = changes.then(change, change)
  changes = run.catch(() => undefined)
  return run
}

function publish(next: StoredKey | null): void {
  status = !next ? 'none' : next.refused ? 'refused' : 'working'
  for (const listener of listeners) listener()
}

function isStoredKey(value: unknown): value is StoredKey {
  if (typeof value !== 'object' || value === null) return false
  const candidate = value as Partial<StoredKey>
  const credential = candidate.credential
  if (candidate.version !== 1 || typeof candidate.refused !== 'boolean' || !credential) return false
  // Read back through the same shape check it was saved with.
  const parsed = parseTmdbCredential(credential.value)
  return parsed !== null && parsed.kind === credential.kind
}

function load(): Promise<StoredKey | null> {
  loaded ??= invoke<string | null>('device_store_read', { collection: 'tmdbKey' })
    .then((raw) => {
      if (raw === null) return null
      try {
        const parsed: unknown = JSON.parse(raw)
        return isStoredKey(parsed) ? parsed : null
      } catch {
        return null
      }
    })
    .catch(() => null)
    .then((stored) => {
      publish(stored)
      return stored
    })
  return loaded
}

async function store(next: StoredKey): Promise<void> {
  await invoke<void>('device_store_write', { collection: 'tmdbKey', contents: JSON.stringify(next) })
  loaded = Promise.resolve(next)
  publish(next)
}

/** The saved key for the on-device backend, or null when there is none or TMDB refused it. */
export async function usableTmdbCredential(): Promise<TmdbCredential | null> {
  const stored = await load()
  return stored && !stored.refused ? stored.credential : null
}

/** TMDB refused a key the backend sent: remembered, so the features stay off across launches. */
export function markTmdbKeyRefused(credential: TmdbCredential): void {
  void serialized(async () => {
    const stored = await load()
    if (!stored || stored.credential.value !== credential.value || stored.refused) return
    await store({ ...stored, refused: true })
  }).catch(() => undefined)
}

export type TmdbKeySaveResult = 'saved' | 'notAKey' | 'refused' | 'unreachable' | 'notStored'

/** How long TMDB gets to say whether it takes a key. */
const CHECK_TIMEOUT_MS = 10_000

/**
 * Checks a pasted key's shape, then asks TMDB whether it takes it (its
 * cheapest call, `/3/configuration`), and saves it only when it does.
 */
export async function saveTmdbKey(pasted: string): Promise<TmdbKeySaveResult> {
  const credential = parseTmdbCredential(pasted)
  if (!credential) return 'notAKey'
  const headers: Record<string, string> = { accept: 'application/json' }
  let url = 'https://api.themoviedb.org/3/configuration'
  if (credential.kind === 'readToken') headers.authorization = `Bearer ${credential.value}`
  else url += `?api_key=${encodeURIComponent(credential.value)}`
  let response: Response
  try {
    response = await nativeFetch(url, { headers, signal: AbortSignal.timeout(CHECK_TIMEOUT_MS) })
  } catch {
    return 'unreachable'
  }
  await response.body?.cancel().catch(() => undefined)
  if (response.status === 401) return 'refused'
  if (!response.ok) return 'unreachable'
  try {
    await serialized(() => store({ version: 1, credential, refused: false }))
  } catch {
    return 'notStored'
  }
  return 'saved'
}

export function removeTmdbKey(): Promise<void> {
  return serialized(async () => {
    await invoke<void>('device_store_remove', { collection: 'tmdbKey' })
    loaded = Promise.resolve(null)
    publish(null)
  })
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  void load()
  return () => listeners.delete(listener)
}

/** Whether a key is saved, and whether TMDB still takes it. */
export function useTmdbKeyStatus(): TmdbKeyStatus {
  return useSyncExternalStore(subscribe, () => status, () => status)
}
