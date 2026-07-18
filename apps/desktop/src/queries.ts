import {
  computeVideoHash,
  languageMatches,
  type AddonEntry,
  type ManifestCatalog,
  type MetaDetail,
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

/** The effective resolution order: global addons first, then the user's own. */
export function useEffectiveAddons() {
  return useQuery({
    queryKey: ['addons'],
    queryFn: () => getClient().getAddons(),
    staleTime: 5 * 60_000,
    select: (data) => [...data.global, ...data.user],
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
export function useCatalog(addonId: string, type: string, id: string) {
  return useQuery({
    queryKey: ['catalog', addonId, type, id],
    queryFn: async () => (await getClient().getCatalog(addonId, type, id)).metas,
    staleTime: 10 * 60_000,
  })
}

/** Meta resolved server-side: first effective addon that can describe this type/id wins. */
export function useMeta(type: string, id: string) {
  return useQuery({
    queryKey: ['meta', type, id],
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
