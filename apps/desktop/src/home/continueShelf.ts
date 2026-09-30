import type { MetaDetail, NextEpisodeResult } from '@halo/core'
import { useQueries, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { getClient } from '../api'
import { episodeTag, runtimeMinutes } from '../format'
import type { StreamsParams } from '../nav'
import {
  buildContinueShelf,
  type ContinueCard,
  type ContinueNextRequest,
  type ContinueShelf,
  type NextEpisodeLookup,
} from './homeRows'
import { useLibrary, useWatchStates } from '../queries'

function nextEpisodeKey(request: ContinueNextRequest) {
  return ['nextEpisode', request.type, request.metaId, request.videoId] as const
}

/**
 * An answered lookup, from the query cache. Only a successful answer counts:
 * a failed one stays unknown, so one unreachable addon does not hide the show
 * for the rest of the session; it is asked again on the next visit.
 */
function cachedLookup(queryClient: QueryClient, request: ContinueNextRequest): NextEpisodeLookup {
  const state = queryClient.getQueryState<NextEpisodeResult>(nextEpisodeKey(request))
  if (!state || state.status !== 'success' || !state.data) return { state: 'unknown' }
  const video = state.data.video
  return video?.id ? { state: 'resolved', videoId: video.id } : { state: 'none' }
}

/**
 * The continue shelf Home draws and the pane's Jump back in list reads, with
 * finished series promoted to their next episode as the native app does.
 *
 * The shelf is built from what the cache already knows; series whose
 * successor is not known yet become lookups (the server's `/next-episode`,
 * metadata only), and each answer re-renders the caller, which rebuilds the
 * shelf with the promoted card in place. Answers are kept for the session
 * because an episode's successor does not change under the viewer.
 */
export function useContinueShelf(opts?: { enabled?: boolean }): ContinueShelf {
  const enabled = opts?.enabled ?? true
  const queryClient = useQueryClient()
  const { data: watchStates } = useWatchStates()
  const { data: library } = useLibrary()

  const shelf = buildContinueShelf(watchStates, library, (request) =>
    cachedLookup(queryClient, request),
  )

  // Subscribing is what re-renders the caller when an answer lands; the shelf
  // above reads the answers themselves from the cache.
  useQueries({
    queries: shelf.requests.map((request) => ({
      queryKey: nextEpisodeKey(request),
      queryFn: () =>
        getClient().getNextEpisode({
          type: request.type,
          metaId: request.metaId,
          videoId: request.videoId,
        }),
      enabled,
      staleTime: Infinity,
      gcTime: Infinity,
      retry: false,
      refetchOnWindowFocus: false,
      refetchOnReconnect: false,
    })),
  })

  return shelf
}

/**
 * What a continue card opens the sources sheet with: the episode the card
 * names, labelled from the title's meta when it is known. The pane never
 * fetches meta, so its rows pass whatever the cache holds, possibly nothing.
 */
export function continueSheetParams(card: ContinueCard, meta: MetaDetail | undefined): StreamsParams {
  const video = meta?.videos?.find((v) => v.id === card.videoId)
  const episodeLabel = video ? episodeTag(video.season, video.episode) : null
  const episodeName = video?.title ?? video?.name ?? null
  const minutes = runtimeMinutes(meta?.runtime)
  return {
    type: card.type,
    videoId: card.videoId,
    itemId: card.itemId,
    metaId: card.metaId,
    title: episodeName ?? card.name,
    showName: card.name,
    ...(episodeLabel ? { episodeLabel } : {}),
    ...(card.poster ? { poster: card.poster } : {}),
    ...(minutes != null ? { runtimeMinutes: minutes } : {}),
  }
}
