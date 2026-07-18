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
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetch as nativeFetch } from '@tauri-apps/plugin-http'
import { getClient } from './api'

/**
 * Data layer — desktop port of apps/mobile/src/queries.ts (same query keys and
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
    select: (data) => [...data.global, ...data.user],
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
    mutationFn: (transportUrls: string[]) => getClient().putGlobalAddons(transportUrls),
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
      getClient().patchGlobalAddon(addonId, { hideCatalogs }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['addons'] }),
  })
}

/** The current user incl. admin status (server-computed); gates admin-only UI. */
export function useMe() {
  return useQuery({
    queryKey: ['me'],
    queryFn: () => getClient().getMe(),
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
 * search) — those need input the row UI doesn't collect. Hidden catalogs never
 * appear here; the server strips them from the wire manifest.
 */
export function browsableCatalogs(addons: AddonEntry[]): BrowsableCatalog[] {
  return addons.flatMap((addon) =>
    addon.manifest.catalogs
      .filter(
        (c) =>
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

/** One catalog, resolved server-side. `addonId` is the opaque `AddonEntry.id`. */
export function useCatalog(addonId: string, type: string, id: string, opts?: { enabled?: boolean }) {
  return useQuery({
    queryKey: ['catalog', addonId, type, id],
    queryFn: async () => (await getClient().getCatalog(addonId, type, id)).metas,
    staleTime: 10 * 60_000,
    enabled: opts?.enabled ?? true,
  })
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
      const { results, errors } = await getClient().getStreams(type, videoId)
      const groups: AddonStreams[] = results.map((r) => ({
        addonId: r.addon.id,
        addonName: r.addon.name,
        streams: r.streams,
      }))
      return { groups, errors }
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

/** One search row per responding catalog, Stremio-style ("Popular – Movie"). */
export interface SearchResultGroup {
  key: string
  title: string
  metas: MetaPreview[]
}

/**
 * Fan-out search across every installed catalog that supports the `search`
 * extra (Cinemeta's do). Each catalog keeps its own result group, in addon
 * order; groups that error or come back empty are dropped. Duplicates are
 * possible across groups (two addons can know the same title) — that mirrors
 * Stremio, where every catalog owns its row.
 */
export function useSearch(term: string) {
  const { data: addons } = useEffectiveAddons()
  const trimmed = term.trim()
  return useQuery({
    queryKey: ['search', trimmed],
    enabled: !!addons && trimmed.length >= 2,
    staleTime: 60_000,
    queryFn: async (): Promise<SearchResultGroup[]> => {
      const targets = (addons ?? []).flatMap((addon) =>
        addon.manifest.catalogs
          .filter(
            (c) =>
              (c.extra ?? []).some((e) => e.name === 'search') ||
              (c.extraSupported ?? []).includes('search'),
          )
          .map((c) => ({
            addonId: addon.id,
            type: c.type,
            id: c.id,
            title: `${c.name ?? addon.manifest.name} – ${typeLabel(c.type)}`,
          })),
      )
      const results = await Promise.allSettled(
        targets.map((t) => getClient().getCatalog(t.addonId, t.type, t.id, { search: trimmed })),
      )
      return targets.flatMap((t, i) => {
        const r = results[i]!
        if (r.status !== 'fulfilled') return []
        const seen = new Set<string>()
        const metas = (r.value.metas ?? []).filter((meta) => {
          const key = `${meta.type}:${meta.id}`
          if (seen.has(key)) return false
          seen.add(key)
          return true
        })
        return metas.length > 0 ? [{ key: `${t.addonId}/${t.type}/${t.id}`, title: t.title, metas }] : []
      })
    },
  })
}

function typeLabel(type: string): string {
  return type.charAt(0).toUpperCase() + type.slice(1)
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
