import {
  computeVideoHash,
  languageMatches,
  type AddonEntry,
  type LibraryItem,
  type ManifestCatalog,
  type MetaDetail,
  type MetaPreview,
  type Stream,
  type Subtitle,
  type WatchState,
} from '@halo/core'
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetch as nativeFetch } from '@tauri-apps/plugin-http'
import { useMemo } from 'react'
import { getAccountClient, getClient } from './api'
import { useSession } from './auth/session'
import { isHomeCatalogType } from './home/homeRows'
import {
  searchProgress,
  uniqueMetas,
  type SearchProgress,
  type SearchTarget,
  type TargetState,
} from './search/searchResults'

/**
 * Data layer, ported from the former Expo client's queries.ts (same query keys and
 * cache semantics so behavior stays recognizable across clients).
 */

/** Stable sort: preferred-language subtitles first, original order otherwise. */
export function sortSubtitlesByPreference(subs: Subtitle[], preferredLang?: string): Subtitle[] {
  if (!preferredLang) return subs
  return [...subs].sort(
    (a, b) =>
      Number(languageMatches(b.lang, preferredLang)) - Number(languageMatches(a.lang, preferredLang)),
  )
}

/** Account-only calls; screens offer them only when signed in. */
function accountClient() {
  const client = getAccountClient()
  if (!client) throw new Error('Sign in to an account for this.')
  return client
}

/** Raw addon split: `{ global, user }`. Use in the settings screen. */
export function useAddons() {
  return useQuery({
    queryKey: ['addons'],
    queryFn: () => getClient().getAddons(),
    staleTime: 5 * 60_000,
  })
}

/** The effective resolution order: global addons first, then the user's own. */
export function useEffectiveAddons() {
  return useQuery({
    queryKey: ['addons'],
    queryFn: () => getClient().getAddons(),
    staleTime: 5 * 60_000,
    select: (data) => [...data.global, ...data.user.filter((a) => !a.providedGlobally)],
  })
}

/** Declares the caller's own addons as transport URLs in priority order (server diffs + fetches new manifests). */
export function useSetAddons() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (transportUrls: string[]) => getClient().putAddons(transportUrls),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['addons'] }),
  })
}

/**
 * Declares the global (admin-managed) addon list shown to every user. The
 * server rejects this with 403 for non-admins, so only surface it behind
 * `useMe().isAdmin`. Shares the `['addons']` cache key with the user list.
 */
export function useSetGlobalAddons() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (transportUrls: string[]) => accountClient().putGlobalAddons(transportUrls),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['addons'] }),
  })
}

/** Toggles catalog visibility on one of the caller's own addons. */
export function usePatchAddon() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ addonId, hideCatalogs }: { addonId: string; hideCatalogs: boolean }) =>
      getClient().patchAddon(addonId, { hideCatalogs }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['addons'] }),
  })
}

/** Admin-only: toggles catalog visibility on a global addon, for every user. */
export function usePatchGlobalAddon() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ addonId, hideCatalogs }: { addonId: string; hideCatalogs: boolean }) =>
      accountClient().patchGlobalAddon(addonId, { hideCatalogs }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['addons'] }),
  })
}

/** The current user incl. admin status (server-computed); gates admin-only UI. Without an account there is none. */
export function useMe() {
  const { state } = useSession()
  return useQuery({
    queryKey: ['me'],
    queryFn: () => accountClient().getMe(),
    enabled: state === 'authenticated',
    staleTime: Infinity,
  })
}

export interface BrowsableCatalog {
  addonId: string
  addonName: string
  catalog: ManifestCatalog
  title: string
}

/**
 * Catalogs the Home screen can fetch bare: no required extras (genre pickers,
 * search), since those need input the row UI doesn't collect, and only films
 * and series (`isHomeCatalogType`). Hidden catalogs never appear here; the server
 * strips them from the wire manifest.
 */
export function browsableCatalogs(addons: AddonEntry[]): BrowsableCatalog[] {
  return addons.flatMap((addon) =>
    addon.manifest.catalogs
      .filter(
        (c) =>
          isHomeCatalogType(c.type) &&
          !(c.extra ?? []).some((e) => e.isRequired) &&
          (c.extraRequired ?? []).length === 0,
      )
      .map((c) => ({
        addonId: addon.id,
        addonName: addon.manifest.name,
        catalog: c,
        title: `${c.name ?? addon.manifest.name} · ${c.type.charAt(0).toUpperCase()}${c.type.slice(1)}`,
      })),
  )
}

/**
 * One catalog's query, shared by the shelf that draws it and by Home's
 * featured picker, which reads every shelf's catalog at once: one key and one
 * fetch for both.
 */
export function catalogQuery(addonId: string, type: string, id: string, extra: Record<string, string> = {}) {
  // A plain catalog keeps its original key, so nothing cached under it moves.
  const narrowed = Object.keys(extra).length > 0
  return {
    queryKey: narrowed ? (['catalog', addonId, type, id, extra] as const) : (['catalog', addonId, type, id] as const),
    queryFn: async () => (await getClient().getCatalog(addonId, type, id, narrowed ? extra : undefined)).metas,
    staleTime: 10 * 60_000,
  }
}

/** One catalog, resolved server-side. `addonId` is the opaque `AddonEntry.id`. */
export function useCatalog(
  addonId: string,
  type: string,
  id: string,
  opts?: { enabled?: boolean; extra?: Record<string, string> },
) {
  return useQuery({ ...catalogQuery(addonId, type, id, opts?.extra), enabled: opts?.enabled ?? true })
}

/** One catalog narrowed to a genre (the catalog must declare a `genre` extra). */
export function useGenreCatalog(addonId: string, type: string, id: string, genre: string) {
  return useQuery(catalogQuery(addonId, type, id, { genre }))
}

/** Meta resolved server-side: first effective addon that can describe this type/id wins. */
export function useMeta(type: string, id: string, opts?: { enabled?: boolean }) {
  return useQuery({
    queryKey: ['meta', type, id],
    enabled: opts?.enabled ?? true,
    staleTime: 10 * 60_000,
    queryFn: async (): Promise<MetaDetail> => (await getClient().getMeta(type, id)).meta,
  })
}

export interface AddonStreams {
  addonId: string
  addonName: string
  streams: Stream[]
}

/** Playable streams per addon, fanned out server-side. */
export function useStreams(type: string, videoId: string) {
  return useQuery({
    queryKey: ['streams', type, videoId],
    queryFn: async () => {
      const started = performance.now()
      const { results, errors } = await getClient().getStreams(type, videoId)
      const groups: AddonStreams[] = results.map((r) => ({
        addonId: r.addon.id,
        addonName: r.addon.name,
        streams: r.streams,
      }))
      // The sources sheet reports how long the resolve took.
      return { groups, errors, elapsedSeconds: (performance.now() - started) / 1000 }
    },
  })
}

export interface SubtitleOptions {
  type: string
  videoId: string
  /** Remote stream URL — used for hash matching via native range requests. */
  streamUrl?: string
  filename?: string
  videoSize?: number
  enabled?: boolean
}

export interface AddonSubtitles {
  addonId: string
  addonName: string
  subtitles: Subtitle[]
}

/**
 * External subtitles from every subtitle-capable addon. The OpenSubtitles hash
 * is computed client-side with the shell's native fetch (range requests would
 * be CORS-blocked from the webview; through tauri-plugin-http they aren't), so
 * the server never touches stream bytes. `hashMatched: false` means results
 * fell back to id search — surface that, don't silently degrade.
 */
export function useAddonSubtitles(opts: SubtitleOptions) {
  return useQuery({
    queryKey: ['subtitles', opts.type, opts.videoId, opts.streamUrl ?? null],
    enabled: opts.enabled ?? true,
    staleTime: Infinity,
    queryFn: async (): Promise<{ groups: AddonSubtitles[]; hashMatched: boolean }> => {
      let videoHash: string | undefined
      let videoSize = opts.videoSize
      try {
        if (opts.streamUrl) {
          const result = await computeVideoHash(opts.streamUrl, { fetch: nativeFetch })
          videoHash = result.hash
          videoSize = videoSize ?? result.size
        }
      } catch {
        // Host rejected ranges — name/id search still works.
      }
      const { results, hashMatched } = await getClient().getSubtitles(opts.type, opts.videoId, {
        videoHash,
        videoSize,
        filename: opts.filename,
      })
      return {
        groups: results.map((r) => ({
          addonId: r.addon.id,
          addonName: r.addon.name,
          subtitles: r.subtitles ?? [],
        })),
        hashMatched,
      }
    },
  })
}

/**
 * Fan-out search across every installed catalog that supports the `search`
 * extra (Cinemeta's do). Each catalog is its own query, so its row shows as
 * soon as it answers instead of waiting for the slowest addon, and a catalog
 * that fails is reported rather than silently dropped. Duplicates are
 * possible across rows (two addons can know the same title), as in Stremio,
 * where every catalog owns its row.
 */
export function useSearch(term: string): SearchProgress {
  const { data: addons } = useEffectiveAddons()
  const trimmed = term.trim()
  const enabled = !!addons && trimmed.length >= 2
  const targets = useMemo(
    (): SearchTarget[] =>
      (addons ?? []).flatMap((addon) =>
        addon.manifest.catalogs
          .filter(
            (c) =>
              (c.extra ?? []).some((e) => e.name === 'search') ||
              (c.extraSupported ?? []).includes('search'),
          )
          .map((c) => ({
            key: `${addon.id}/${c.type}/${c.id}`,
            addonId: addon.id,
            addonName: addon.manifest.name,
            type: c.type,
            id: c.id,
            title: c.name ?? addon.manifest.name,
          })),
      ),
    [addons],
  )
  const results = useQueries({
    queries: targets.map((t) => ({
      queryKey: ['search', trimmed, t.addonId, t.type, t.id] as const,
      enabled,
      staleTime: 60_000,
      // One failed catalog is reported at once; a retry would hold its row's
      // fate back for seconds while the rest have answered.
      retry: false,
      queryFn: async () =>
        uniqueMetas((await getClient().getCatalog(t.addonId, t.type, t.id, { search: trimmed })).metas ?? []),
    })),
  })
  if (!enabled) return searchProgress([], [])
  return searchProgress(
    targets,
    results.map((r): TargetState =>
      r.isSuccess ? { status: 'done', metas: r.data } : r.isError ? { status: 'failed' } : { status: 'pending' },
    ),
  )
}

export function useLibrary() {
  return useQuery({
    queryKey: ['library'],
    queryFn: () => getClient().getLibrary(),
  })
}

export function useUpsertLibrary() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (items: LibraryItem[]) => getClient().putLibrary(items),
    onSuccess: (items) => queryClient.setQueryData(['library'], items),
  })
}

export function libraryItemFromMeta(meta: MetaPreview): LibraryItem {
  const now = Date.now()
  return {
    id: `${meta.type}:${meta.id}`,
    type: meta.type,
    name: meta.name,
    poster: meta.poster,
    addedAt: now,
    updatedAt: now,
  }
}

export function useWatchStates() {
  return useQuery({
    queryKey: ['watchStates'],
    queryFn: () => getClient().getWatchStates(),
  })
}

export function useReportWatchState() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (states: WatchState[]) => getClient().putWatchStates(states),
    onSuccess: (states) => queryClient.setQueryData(['watchStates'], states),
  })
}
