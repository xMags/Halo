import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  addonSupportsResource,
  computeVideoHash,
  getCatalog,
  getMeta,
  getStreams,
  getSubtitles,
  isPlayableStream,
  type AddonEntry,
  type LibraryItem,
  type MetaDetail,
  type MetaPreview,
  type Stream,
  type Subtitle,
  type WatchState,
} from '@halo/core'
import { api } from './api'

export function useAddons() {
  return useQuery({
    queryKey: ['addons'],
    queryFn: () => api().getAddons(),
    staleTime: 5 * 60_000,
  })
}

export function useSetAddons() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (addons: AddonEntry[]) => api().putAddons(addons),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['addons'] }),
  })
}

export function useCatalog(transportUrl: string, type: string, id: string) {
  return useQuery({
    queryKey: ['catalog', transportUrl, type, id],
    queryFn: async () => (await getCatalog(transportUrl, type, id)).metas,
    staleTime: 10 * 60_000,
  })
}

/** Meta from the first installed addon that can describe this type/id. */
export function useMeta(type: string, id: string) {
  const { data: addons } = useAddons()
  return useQuery({
    queryKey: ['meta', type, id],
    enabled: !!addons,
    staleTime: 10 * 60_000,
    queryFn: async (): Promise<MetaDetail> => {
      const capable = (addons ?? []).filter((a) => addonSupportsResource(a.manifest, 'meta', type, id))
      let lastError: unknown = new Error(`no installed addon provides metadata for ${type}/${id}`)
      for (const addon of capable) {
        try {
          return (await getMeta(addon.transportUrl, type, id)).meta
        } catch (err) {
          lastError = err
        }
      }
      throw lastError
    },
  })
}

export interface AddonStreams {
  addonName: string
  transportUrl: string
  streams: Stream[]
}

/** Streams from every capable addon, queried concurrently; failures drop out. */
export function useStreams(type: string, videoId: string) {
  const { data: addons } = useAddons()
  return useQuery({
    queryKey: ['streams', type, videoId],
    enabled: !!addons,
    queryFn: async (): Promise<AddonStreams[]> => {
      const capable = (addons ?? []).filter((a) => addonSupportsResource(a.manifest, 'stream', type, videoId))
      const results = await Promise.allSettled(
        capable.map(async (a): Promise<AddonStreams> => ({
          addonName: a.manifest.name,
          transportUrl: a.transportUrl,
          streams: (await getStreams(a.transportUrl, type, videoId)).streams.filter(isPlayableStream),
        })),
      )
      return results
        .filter((r): r is PromiseFulfilledResult<AddonStreams> => r.status === 'fulfilled')
        .map((r) => r.value)
        .filter((r) => r.streams.length > 0)
    },
  })
}

export interface SubtitleOptions {
  type: string
  videoId: string
  /** Remote stream URL — used for hash matching. Omit for local playback. */
  streamUrl?: string
  filename?: string
  videoSize?: number
}

/**
 * External subtitles from every subtitle-capable addon. Hash matching is
 * best-effort: when the stream host supports range requests the results are
 * exact matches, otherwise addons fall back to id-based search.
 */
export function useAddonSubtitles(opts: SubtitleOptions) {
  const { data: addons } = useAddons()
  return useQuery({
    queryKey: ['subtitles', opts.type, opts.videoId, opts.streamUrl ?? null],
    enabled: !!addons,
    staleTime: Infinity,
    queryFn: async (): Promise<Subtitle[]> => {
      const capable = (addons ?? []).filter((a) =>
        addonSupportsResource(a.manifest, 'subtitles', opts.type, opts.videoId),
      )
      if (capable.length === 0) return []

      let videoHash: string | undefined
      let videoSize = opts.videoSize
      if (opts.streamUrl) {
        try {
          const result = await computeVideoHash(opts.streamUrl)
          videoHash = result.hash
          videoSize = videoSize ?? result.size
        } catch {
          // Host rejected range requests — name/id search still works.
        }
      }

      const results = await Promise.allSettled(
        capable.map((a) =>
          getSubtitles(a.transportUrl, opts.type, opts.videoId, { videoHash, videoSize, filename: opts.filename }),
        ),
      )
      return results
        .filter((r): r is PromiseFulfilledResult<{ subtitles: Subtitle[] }> => r.status === 'fulfilled')
        .flatMap((r) => r.value.subtitles ?? [])
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
