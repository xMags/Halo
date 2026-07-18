import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  computeVideoHash,
  languageMatches,
  type AddonRef,
  type CatalogResponse,
  type LibraryItem,
  type MetaDetail,
  type MetaPreview,
  type Stream,
  type Subtitle,
  type WatchState,
} from '@halo/core'
import { api } from './api'
import { computeLocalVideoHash } from './localHash'

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
    queryFn: () => api().getAddons(),
    staleTime: 5 * 60_000,
  })
}

/** The effective resolution order: global addons first, then the user's own. */
export function useEffectiveAddons() {
  return useQuery({
    queryKey: ['addons'],
    queryFn: () => api().getAddons(),
    staleTime: 5 * 60_000,
    select: (data) => [...data.global, ...data.user],
  })
}

/** Replaces the caller's own addons (server fetches the manifests). */
export function useSetAddons() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (entries: AddonRef[]) => api().putAddons(entries),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['addons'] }),
  })
}

/**
 * Replaces the global (admin-managed) addon list shown to every user. The
 * server rejects this with 403 for non-admins, so only surface it behind
 * `useMe().isAdmin`. Shares the `['addons']` cache key with the user list.
 */
export function useSetGlobalAddons() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (entries: AddonRef[]) => api().putGlobalAddons(entries),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['addons'] }),
  })
}

/** The current user incl. admin status (server-computed); gates admin-only UI. */
export function useMe() {
  return useQuery({
    queryKey: ['me'],
    queryFn: () => api().getMe(),
    staleTime: Infinity,
  })
}

/** One catalog, resolved server-side. `addonId` is the opaque `AddonEntry.id`. */
export function useCatalog(addonId: string, type: string, id: string, opts?: { enabled?: boolean }) {
  return useQuery({
    queryKey: ['catalog', addonId, type, id],
    queryFn: async () => (await api().getCatalog(addonId, type, id)).metas,
    staleTime: 10 * 60_000,
    enabled: opts?.enabled ?? true,
  })
}

/** Meta resolved server-side: first effective addon that can describe this type/id wins (404 → error state). */
export function useMeta(type: string, id: string, opts?: { enabled?: boolean }) {
  return useQuery({
    queryKey: ['meta', type, id],
    enabled: opts?.enabled ?? true,
    staleTime: 10 * 60_000,
    queryFn: async (): Promise<MetaDetail> => (await api().getMeta(type, id)).meta,
  })
}

export interface AddonStreams {
  addonId: string
  addonName: string
  streams: Stream[]
}

/** Playable streams per addon, fanned out server-side; failed addons drop out (parity with the old silent drop). */
export function useStreams(type: string, videoId: string) {
  return useQuery({
    queryKey: ['streams', type, videoId],
    queryFn: async (): Promise<AddonStreams[]> => {
      const { results } = await api().getStreams(type, videoId)
      return results.map((r) => ({ addonId: r.addon.id, addonName: r.addon.name, streams: r.streams }))
    },
  })
}

export interface SubtitleOptions {
  type: string
  videoId: string
  /** Remote stream URL — used for hash matching. Omit for local playback. */
  streamUrl?: string
  /** Downloaded file — hashed from disk so offline results are exact matches. */
  localFileUri?: string
  filename?: string
  videoSize?: number
}

export interface AddonSubtitles {
  addonId: string
  addonName: string
  subtitles: Subtitle[]
}

/**
 * External subtitles from every subtitle-capable addon, grouped per addon so
 * the UI can attribute each variant to its source. Hash matching is
 * best-effort: when the stream host supports range requests the results are
 * exact matches, otherwise addons fall back to id-based search.
 */
export function useAddonSubtitles(opts: SubtitleOptions) {
  return useQuery({
    queryKey: ['subtitles', opts.type, opts.videoId, opts.streamUrl ?? opts.localFileUri ?? null],
    staleTime: Infinity,
    queryFn: async (): Promise<AddonSubtitles[]> => {
      // Hashing stays client-side by design: the server never touches stream
      // bytes, and only the device can read a downloaded file.
      let videoHash: string | undefined
      let videoSize = opts.videoSize
      try {
        if (opts.localFileUri) {
          const result = await computeLocalVideoHash(opts.localFileUri)
          videoHash = result.hash
          videoSize = videoSize ?? result.size
        } else if (opts.streamUrl) {
          const result = await computeVideoHash(opts.streamUrl)
          videoHash = result.hash
          videoSize = videoSize ?? result.size
        }
      } catch {
        // Host rejected ranges / file unreadable — name/id search still works.
      }

      const { results } = await api().getSubtitles(opts.type, opts.videoId, {
        videoHash,
        videoSize,
        filename: opts.filename,
      })
      return results.map((r) => ({ addonId: r.addon.id, addonName: r.addon.name, subtitles: r.subtitles ?? [] }))
    },
  })
}

/**
 * Fan-out search across every installed catalog that supports the `search`
 * extra (Cinemeta's do). Results merge in addon order, deduped by type:id.
 */
export function useSearch(term: string) {
  const { data: addons } = useEffectiveAddons()
  const trimmed = term.trim()
  return useQuery({
    queryKey: ['search', trimmed],
    enabled: !!addons && trimmed.length >= 2,
    staleTime: 60_000,
    queryFn: async (): Promise<MetaPreview[]> => {
      const targets = (addons ?? []).flatMap((addon) =>
        addon.manifest.catalogs
          .filter(
            (c) =>
              (c.extra ?? []).some((e) => e.name === 'search') ||
              (c.extraSupported ?? []).includes('search'),
          )
          .map((c) => ({ addonId: addon.id, type: c.type, id: c.id })),
      )
      const results = await Promise.allSettled(
        targets.map((t) => api().getCatalog(t.addonId, t.type, t.id, { search: trimmed })),
      )
      const metas = results
        .filter((r): r is PromiseFulfilledResult<CatalogResponse> => r.status === 'fulfilled')
        .flatMap((r) => r.value.metas ?? [])
      const seen = new Set<string>()
      return metas.filter((meta) => {
        const key = `${meta.type}:${meta.id}`
        if (seen.has(key)) return false
        seen.add(key)
        return true
      })
    },
  })
}

export function useLibrary() {
  return useQuery({
    queryKey: ['library'],
    queryFn: () => api().getLibrary(),
  })
}

export function useUpsertLibrary() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (items: LibraryItem[]) => api().putLibrary(items),
    onSuccess: (items) => queryClient.setQueryData(['library'], items),
  })
}

export function useWatchStates() {
  return useQuery({
    queryKey: ['watchStates'],
    queryFn: () => api().getWatchStates(),
  })
}

export function useReportWatchState() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (states: WatchState[]) => api().putWatchStates(states),
    onSuccess: (states) => queryClient.setQueryData(['watchStates'], states),
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
