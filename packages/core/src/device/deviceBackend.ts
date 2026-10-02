import {
  catalogExtraProblem,
  fetchCatalog,
  fetchManifests,
  MAX_BINGE_GROUP_LENGTH,
  resolveMeta,
  resolveNextEpisode,
  resolveStreams,
  resolveSubtitles,
  RESOLVE_TIMEOUT_MS,
  subtitleQueryProblem,
  type ResolveContext,
  type SubtitleQuery,
} from '../addon/resolve'
import type { CatalogResponse, Manifest, MetaResponse } from '../addon/types'
import type { HaloBackend } from '../api/backend'
import { HaloApiError, type RequestOptions } from '../api/client'
import type {
  AddonEntry,
  AddonsResponse,
  LibraryItem,
  NextEpisodeResult,
  SettingsPayload,
  StreamsResult,
  SubtitlesResult,
  UserSettings,
  WatchState,
} from '../api/types'
import {
  addonUrlsProblem,
  libraryItemProblem,
  mergeLibrary,
  mergeWatchStates,
  settingsProblem,
  watchStateProblem,
} from './records'

/** The documents the on-device backend keeps, one per collection. */
export type DeviceCollection = 'addons' | 'library' | 'watchStates' | 'settings'

/**
 * Where the on-device backend keeps its documents. The app supplies it (the
 * desktop keeps them in files the native shell owns). A missing document reads
 * as null.
 */
export interface DeviceStore {
  read(collection: DeviceCollection): Promise<string | null>
  write(collection: DeviceCollection, contents: string): Promise<void>
}

export interface DeviceBackendOptions {
  /** Fetch for addon traffic. It must not be subject to browser CORS. */
  fetch: typeof fetch
  store: DeviceStore
  now?: () => number
  newId?: () => string
  /** Per-addon budget; defaults to the server's. */
  timeoutMs?: number
}

/** A stored document exists but cannot be read back; it is left untouched rather than overwritten. */
export class DeviceDataError extends Error {
  constructor(readonly collection: DeviceCollection) {
    super(`The ${collection} saved on this device could not be read.`)
    this.name = 'DeviceDataError'
  }
}

interface StoredAddon {
  id: string
  transportUrl: string
  manifest: Manifest
  hideCatalogs: boolean
  addedAt: number
}

const DOCUMENT_VERSION = 1

/**
 * Halo's backend for someone using it without an account: the same contract
 * `HaloClient` offers, answered on the device. Addons are asked directly
 * through the shared core resolvers (the rules the API server runs), and the
 * addon list, library, watch history and settings are kept in `DeviceStore`
 * with the server's validation and last-write-wins rules. Errors are
 * `HaloApiError`s with the statuses the server would answer, so screens
 * cannot tell the two apart.
 *
 * There are no global addons on a device: `getAddons` answers `global: []`.
 *
 * Writes to one collection run one at a time, each re-reading the stored
 * document first: the player reports progress on a timer and again on pause,
 * so two reports can overlap, and neither may overwrite the other.
 */
export class DeviceBackend implements HaloBackend {
  private readonly doFetch: typeof fetch
  private readonly store: DeviceStore
  private readonly now: () => number
  private readonly newId: () => string
  private readonly timeoutMs: number
  private readonly queues = new Map<DeviceCollection, Promise<unknown>>()

  constructor(opts: DeviceBackendOptions) {
    this.doFetch = opts.fetch
    this.store = opts.store
    this.now = opts.now ?? Date.now
    this.newId = opts.newId ?? (() => globalThis.crypto.randomUUID())
    this.timeoutMs = opts.timeoutMs ?? RESOLVE_TIMEOUT_MS
  }

  /* ── Addons ─────────────────────────────────────────────────────────────── */

  async getAddons(): Promise<AddonsResponse> {
    return { global: [], user: (await this.readAddons()).map(toAddonEntry) }
  }

  /**
   * Declares the addon list (array order = priority), with the server's diff
   * contract: kept URLs keep their id and manifest untouched, new URLs have
   * their manifest fetched and validated all-or-nothing, and URLs left out are
   * removed.
   */
  async putAddons(transportUrls: string[]): Promise<AddonEntry[]> {
    const problem = addonUrlsProblem(transportUrls)
    if (problem) throw apiError(400, problem)
    return this.exclusive('addons', async () => {
      const current = new Map((await this.readAddons()).map((addon) => [addon.transportUrl, addon]))
      const resolved = await fetchManifests(
        transportUrls.filter((url) => !current.has(url)),
        { fetch: this.doFetch, timeoutMs: this.timeoutMs },
      )
      if ('error' in resolved) throw apiError(400, resolved.error)
      const fetched = new Map(resolved.entries.map((entry) => [entry.transportUrl, entry.manifest]))
      const addedAt = this.now()
      const next = transportUrls.map(
        (transportUrl): StoredAddon =>
          current.get(transportUrl) ?? { id: this.newId(), transportUrl, manifest: fetched.get(transportUrl)!, hideCatalogs: false, addedAt },
      )
      await this.writeDocument('addons', 'addons', next)
      return next.map(toAddonEntry)
    })
  }

  async patchAddon(addonId: string, patch: { hideCatalogs: boolean }): Promise<void> {
    if (typeof patch?.hideCatalogs !== 'boolean') throw apiError(400, 'hideCatalogs must be a boolean')
    await this.exclusive('addons', async () => {
      const addons = await this.readAddons()
      const target = addons.find((addon) => addon.id === addonId)
      if (!target) throw apiError(404, 'addon not found')
      target.hideCatalogs = patch.hideCatalogs
      await this.writeDocument('addons', 'addons', addons)
    })
  }

  /* ── Resolution ─────────────────────────────────────────────────────────── */

  async getCatalog(
    addonId: string,
    type: string,
    id: string,
    extra?: Record<string, string>,
    opts?: RequestOptions,
  ): Promise<CatalogResponse> {
    if (!addonId || !type || !id) throw apiError(400, 'addon, type and id are required')
    const extraProblem = catalogExtraProblem(extra ?? {})
    if (extraProblem) throw apiError(400, extraProblem)
    const entry = (await this.readAddons()).find((addon) => addon.id === addonId)
    if (!entry) throw apiError(403, 'addon not installed')
    try {
      return await fetchCatalog(entry, type, id, extra ?? {}, this.context(opts))
    } catch (err) {
      throwIfCancelled(opts)
      throw apiError(502, 'catalog fetch failed', err)
    }
  }

  async getMeta(type: string, id: string, opts?: RequestOptions): Promise<MetaResponse> {
    if (!type || !id) throw apiError(400, 'type and id are required')
    const meta = await resolveMeta(await this.readAddons(), type, id, this.context(opts))
    throwIfCancelled(opts)
    if (!meta) throw apiError(404, 'no metadata found')
    return meta
  }

  async getStreams(type: string, videoId: string, opts?: RequestOptions): Promise<StreamsResult> {
    if (!type || !videoId) throw apiError(400, 'type and videoId are required')
    const result = await resolveStreams(await this.readAddons(), type, videoId, this.context(opts))
    throwIfCancelled(opts)
    return result
  }

  async getNextEpisode(
    params: { type: string; metaId: string; videoId: string; addonId?: string; bingeGroup?: string },
    opts?: RequestOptions,
  ): Promise<NextEpisodeResult> {
    if (!params.type || !params.metaId || !params.videoId) throw apiError(400, 'type, metaId and videoId are required')
    // HaloClient leaves empty values out of the query, so the server never sees them.
    const addonId = params.addonId || undefined
    const bingeGroup = params.bingeGroup || undefined
    if (bingeGroup !== undefined && bingeGroup.length > MAX_BINGE_GROUP_LENGTH) throw apiError(400, 'bingeGroup too long')
    const result = await resolveNextEpisode(
      await this.readAddons(),
      { type: params.type, metaId: params.metaId, videoId: params.videoId, addonId, bingeGroup },
      this.context(opts),
    )
    throwIfCancelled(opts)
    if (!result) throw apiError(404, 'no metadata found')
    return result
  }

  async getSubtitles(
    type: string,
    videoId: string,
    extra?: { videoHash?: string; videoSize?: number; filename?: string },
    opts?: RequestOptions,
  ): Promise<SubtitlesResult> {
    if (!type || !videoId) throw apiError(400, 'type and videoId are required')
    // Same omissions as HaloClient's query string: empty values never reach the resolver.
    const query: SubtitleQuery = {
      ...(extra?.videoHash ? { videoHash: extra.videoHash } : {}),
      ...(extra?.videoSize !== undefined ? { videoSize: extra.videoSize } : {}),
      ...(extra?.filename ? { filename: extra.filename } : {}),
    }
    const problem = subtitleQueryProblem(query)
    if (problem) throw apiError(400, problem)
    const result = await resolveSubtitles(await this.readAddons(), type, videoId, query, this.context(opts))
    throwIfCancelled(opts)
    return result
  }

  /* ── Library, watch history, settings ───────────────────────────────────── */

  /** Everything stored, tombstones included, as the server answers. */
  getLibrary(): Promise<LibraryItem[]> {
    return this.readList<LibraryItem>('library', 'items')
  }

  async putLibrary(items: LibraryItem[]): Promise<LibraryItem[]> {
    if (!Array.isArray(items)) throw apiError(400, 'expected a list of library items')
    const problem = items.map(libraryItemProblem).find((p) => p !== null)
    if (problem) throw apiError(400, problem)
    return this.exclusive('library', async () => {
      const merged = mergeLibrary(await this.readList<LibraryItem>('library', 'items'), items)
      await this.writeDocument('library', 'items', merged)
      return merged
    })
  }

  getWatchStates(): Promise<WatchState[]> {
    return this.readList<WatchState>('watchStates', 'states')
  }

  async putWatchStates(states: WatchState[]): Promise<WatchState[]> {
    if (!Array.isArray(states)) throw apiError(400, 'expected a list of watch states')
    const problem = states.map(watchStateProblem).find((p) => p !== null)
    if (problem) throw apiError(400, problem)
    return this.exclusive('watchStates', async () => {
      const merged = mergeWatchStates(await this.readList<WatchState>('watchStates', 'states'), states)
      await this.writeDocument('watchStates', 'states', merged)
      return merged
    })
  }

  async getSettings(): Promise<SettingsPayload> {
    return (await this.readSettings()) ?? { value: {}, updatedAt: 0 }
  }

  /** Stored only when strictly newer than what is there; answers whatever is stored afterwards. */
  async putSettings(value: UserSettings, updatedAt: number): Promise<SettingsPayload> {
    const problem = settingsProblem(value, updatedAt)
    if (problem) throw apiError(400, problem)
    return this.exclusive('settings', async () => {
      const stored = await this.readSettings()
      if (stored && updatedAt <= stored.updatedAt) return stored
      // A JSON round trip, so the answer matches what a later read returns.
      const next = JSON.parse(JSON.stringify({ value, updatedAt })) as SettingsPayload
      await this.store.write('settings', JSON.stringify({ version: DOCUMENT_VERSION, ...next }))
      return next
    })
  }

  /* ── Internals ──────────────────────────────────────────────────────────── */

  private context(opts: RequestOptions | undefined): ResolveContext {
    return { fetch: this.doFetch, timeoutMs: this.timeoutMs, ...(opts?.signal ? { signal: opts.signal } : {}) }
  }

  private exclusive<T>(collection: DeviceCollection, task: () => Promise<T>): Promise<T> {
    const previous = this.queues.get(collection) ?? Promise.resolve()
    const run = previous.then(task)
    // The queue only orders writes; a failed write must not fail the next one.
    this.queues.set(collection, run.catch(() => undefined))
    return run
  }

  private readAddons(): Promise<StoredAddon[]> {
    return this.readList<StoredAddon>('addons', 'addons')
  }

  private async readList<T>(collection: DeviceCollection, field: string): Promise<T[]> {
    const document = await this.readDocument(collection)
    if (document === null) return []
    const list = document[field]
    if (!Array.isArray(list)) throw new DeviceDataError(collection)
    return list as T[]
  }

  private async readSettings(): Promise<SettingsPayload | null> {
    const document = await this.readDocument('settings')
    if (document === null) return null
    if (!isRecord(document.value) || typeof document.updatedAt !== 'number') throw new DeviceDataError('settings')
    return { value: document.value as UserSettings, updatedAt: document.updatedAt }
  }

  private async readDocument(collection: DeviceCollection): Promise<Record<string, unknown> | null> {
    const raw = await this.store.read(collection)
    if (raw === null) return null
    let document: unknown
    try {
      document = JSON.parse(raw)
    } catch {
      throw new DeviceDataError(collection)
    }
    if (!isRecord(document) || document.version !== DOCUMENT_VERSION) throw new DeviceDataError(collection)
    return document
  }

  private writeDocument(collection: DeviceCollection, field: string, list: readonly unknown[]): Promise<void> {
    return this.store.write(collection, JSON.stringify({ version: DOCUMENT_VERSION, [field]: list }))
  }
}

/** Wire shape, with hidden catalogs stripped as the server strips them. */
function toAddonEntry(addon: StoredAddon, position: number): AddonEntry {
  return {
    id: addon.id,
    transportUrl: addon.transportUrl,
    manifest: addon.hideCatalogs ? { ...addon.manifest, catalogs: [] } : addon.manifest,
    position,
    ...(addon.hideCatalogs ? { hideCatalogs: true } : {}),
  }
}

/** The error the server would answer with, body included, so callers handle both alike. */
function apiError(status: number, error: string, cause?: unknown): HaloApiError {
  const err = new HaloApiError(status, JSON.stringify({ error }))
  if (cause !== undefined) Object.defineProperty(err, 'cause', { value: cause })
  return err
}

/** A caller that cancelled gets its own abort reason, as a cancelled fetch to the server would. */
function throwIfCancelled(opts: RequestOptions | undefined): void {
  if (opts?.signal?.aborted) throw opts.signal.reason
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
