import type { LibraryItem, MetaDetail, MetaPreview, MetaVideo, WatchState } from '@halo/core'
import { useQueryClient } from '@tanstack/react-query'
import type { MouseEvent } from 'react'
import { openContextMenu, type ContextMenuEntry } from '../components/ContextMenu'
import { confirmDeleteFromDevice, reportDownloadActionFailure } from '../downloads/downloadPrompts'
import { deleteReady, readyDownloadFor, showInExplorer } from '../downloads/downloadsStore'
import { runtimeMinutes, videoIdTag } from '../format'
import { continueSheetParams } from '../home/continueShelf'
import type { ContinueCard } from '../home/homeRows'
import { useNav } from '../nav'
import { libraryItemFromMeta, useReportWatchState, useUpsertLibrary } from '../queries'
import { playDownload } from '../sources/SourcesSheet'
import {
  earlierEpisodes,
  unwatchedRow,
  watchedRow,
  watchedRowsInOrder,
  type WatchMarkTarget,
} from './watchMarks'

type MenuEvent = MouseEvent<HTMLElement>
type MaybeEntry = ContextMenuEntry | null | false

const SEPARATOR: ContextMenuEntry = { kind: 'separator' }

/** What an episode row's menu acts on: the row's own play action and the show it belongs to. */
export interface EpisodeMenuTarget {
  meta: MetaDetail
  itemId: string
  video: MetaVideo
  play: () => void
}

function runtimeSeconds(runtime: string | undefined): number | null {
  const minutes = runtimeMinutes(runtime)
  return minutes == null ? null : minutes * 60
}

function episodeTarget(meta: MetaDetail, itemId: string, video: MetaVideo): WatchMarkTarget {
  return { videoId: video.id, itemId, name: meta.name, ...(meta.poster ? { poster: meta.poster } : {}) }
}

/**
 * The right-click menus for everything that stands for a title: a poster or
 * the featured banner, a continue card or pane row, an episode row. Every
 * menu reads the same way: what a click would do and where the title lives,
 * then what can be recorded about it (watched, in the library), then what can
 * be done with a copy saved on this device.
 *
 * Menus are built when they open, from whatever the query cache and the
 * downloads store hold then, so a card subscribes to nothing until it is
 * right-clicked, and the pane still fetches no meta. An entry whose state is
 * not loaded yet (library, watch history) is left out rather than guessed.
 */
export function useTitleMenu() {
  const queryClient = useQueryClient()
  const { push, openSheet } = useNav()
  const upsertLibrary = useUpsertLibrary()
  const reportWatchState = useReportWatchState()

  const cachedLibrary = () => queryClient.getQueryData<LibraryItem[]>(['library'])
  const cachedWatchStates = () => queryClient.getQueryData<WatchState[]>(['watchStates'])
  const cachedMeta = (type: string, id: string) => queryClient.getQueryData<MetaDetail>(['meta', type, id])

  const activeLibraryEntry = (itemId: string) =>
    cachedLibrary()?.find((item) => item.id === itemId && !item.removedAt)

  /** Adds or removes, whichever the label promised, against the library as it is when chosen. */
  const setInLibrary = (preview: MetaPreview, wanted: boolean) => {
    const entry = activeLibraryEntry(`${preview.type}:${preview.id}`)
    if (wanted === Boolean(entry)) return
    if (!entry) {
      upsertLibrary.mutate([libraryItemFromMeta(preview)])
      return
    }
    const now = Date.now()
    // Tombstone, not delete: removals must sync and survive stale re-adds.
    upsertLibrary.mutate([{ ...entry, removedAt: now, updatedAt: now }])
  }

  const libraryEntry = (preview: MetaPreview): MaybeEntry => {
    if (!cachedLibrary()) return null
    return activeLibraryEntry(`${preview.type}:${preview.id}`)
      ? { kind: 'item', label: 'Remove from library', glyph: 'unfavorite', onSelect: () => setInLibrary(preview, false) }
      : { kind: 'item', label: 'Add to library', glyph: 'star', onSelect: () => setInLibrary(preview, true) }
  }

  const setWatched = (target: WatchMarkTarget, watched: boolean, runtimeSec: number | null) => {
    const existing = cachedWatchStates()?.find((state) => state.videoId === target.videoId)
    const now = Date.now()
    reportWatchState.mutate([
      watched ? watchedRow(target, existing, runtimeSec, now) : unwatchedRow(target, existing, now),
    ])
  }

  /** `subject` names the video when the surface alone does not (a card standing for a whole show). */
  const watchedEntry = (target: WatchMarkTarget, runtime: string | undefined, subject?: string): MaybeEntry => {
    const states = cachedWatchStates()
    if (!states) return null
    const runtimeSec = runtimeSeconds(runtime)
    const what = subject ? `${subject} ` : ''
    const watched = states.find((state) => state.videoId === target.videoId)?.watched ?? false
    return watched
      ? { kind: 'item', label: `Mark ${what}as unwatched`, glyph: 'undo', onSelect: () => setWatched(target, false, runtimeSec) }
      : { kind: 'item', label: `Mark ${what}as watched`, glyph: 'check', onSelect: () => setWatched(target, true, runtimeSec) }
  }

  /** The episodes before `video` that are not marked watched yet, first to last. */
  const unwatchedEarlier = (meta: MetaDetail, video: MetaVideo, states: readonly WatchState[]) => {
    const watched = new Set(states.filter((state) => state.watched).map((state) => state.videoId))
    return earlierEpisodes(meta.videos ?? [], video).filter((earlier) => !watched.has(earlier.id))
  }

  /** Re-read when chosen, so an episode marked since the menu opened is not written again. */
  const markEarlierWatched = (meta: MetaDetail, itemId: string, video: MetaVideo) => {
    const states = cachedWatchStates()
    if (!states) return
    const targets = unwatchedEarlier(meta, video, states).map((earlier) => episodeTarget(meta, itemId, earlier))
    if (targets.length === 0) return
    const existing = new Map(states.map((state) => [state.videoId, state]))
    reportWatchState.mutate(watchedRowsInOrder(targets, existing, runtimeSeconds(meta.runtime), Date.now()))
  }

  const earlierEntry = (meta: MetaDetail, itemId: string, video: MetaVideo): MaybeEntry => {
    const states = cachedWatchStates()
    if (!states) return null
    const count = unwatchedEarlier(meta, video, states).length
    if (count === 0) return null
    return {
      kind: 'item',
      label: `Mark ${count} earlier episode${count === 1 ? '' : 's'} as watched`,
      glyph: 'checkList',
      onSelect: () => markEarlierWatched(meta, itemId, video),
    }
  }

  const deleteDownload = async (jobId: string) => {
    if (!(await confirmDeleteFromDevice())) return
    await reportDownloadActionFailure(await deleteReady(jobId))
  }

  /**
   * A finished download of the video: "Play offline" goes beside Play, the
   * file's own actions close the menu. Play re-reads the download when chosen,
   * because it may have been deleted while the menu was open; the file actions
   * report such a failure themselves.
   */
  const downloadEntries = (videoId: string, onBeforeOpen?: () => void) => {
    const item = readyDownloadFor(videoId)
    if (!item) return { play: null, manage: [] as MaybeEntry[] }
    const play: MaybeEntry = {
      kind: 'item',
      label: 'Play offline',
      glyph: 'downloads',
      onSelect: () => {
        const current = readyDownloadFor(videoId)
        if (!current) return
        onBeforeOpen?.()
        playDownload(push, current)
      },
    }
    const manage: MaybeEntry[] = [
      SEPARATOR,
      {
        kind: 'item',
        label: 'Show in Explorer',
        glyph: 'folder',
        onSelect: () => void showInExplorer(item.job_id).then(reportDownloadActionFailure),
      },
      { kind: 'item', label: 'Delete from device', glyph: 'delete', onSelect: () => void deleteDownload(item.job_id) },
    ]
    return { play, manage }
  }

  const show = (event: MenuEvent, entries: MaybeEntry[]) =>
    openContextMenu(
      event,
      entries.filter((entry): entry is ContextMenuEntry => Boolean(entry)),
    )

  /**
   * A poster or the featured banner. A film plays from here; a series needs an
   * episode chosen, which is what its title page is for, so it only offers
   * that. `onBeforeOpen` is the poster's own pre-navigation hook (Search
   * records the term with it).
   */
  const forPoster = (event: MenuEvent, meta: MetaPreview, onBeforeOpen?: () => void) => {
    const itemId = `${meta.type}:${meta.id}`
    const isSeries = meta.type === 'series'
    const cached = cachedMeta(meta.type, meta.id)
    const minutes = runtimeMinutes(cached?.runtime)
    // A series' own id is never a video, so it has no download of its own.
    const saved = isSeries ? { play: null, manage: [] } : downloadEntries(meta.id, onBeforeOpen)
    show(event, [
      !isSeries && {
        kind: 'item',
        label: 'Play',
        glyph: 'play',
        onSelect: () => {
          onBeforeOpen?.()
          openSheet({
            type: meta.type,
            videoId: meta.id,
            itemId,
            metaId: meta.id,
            title: meta.name,
            showName: meta.name,
            ...(meta.poster ? { poster: meta.poster } : {}),
            ...(minutes != null ? { runtimeMinutes: minutes } : {}),
          })
        },
      },
      saved.play,
      {
        kind: 'item',
        label: 'Open details',
        glyph: 'info',
        onSelect: () => {
          onBeforeOpen?.()
          push({ name: 'detail', type: meta.type, id: meta.id })
        },
      },
      SEPARATOR,
      !isSeries &&
        watchedEntry({ videoId: meta.id, itemId, name: meta.name, ...(meta.poster ? { poster: meta.poster } : {}) }, cached?.runtime),
      libraryEntry(meta),
      ...saved.manage,
    ])
  }

  /**
   * A continue card, or the pane row that mirrors it. Marking the episode
   * watched moves the shelf on to the next one, as finishing it would.
   */
  const forContinue = (event: MenuEvent, card: ContinueCard) => {
    const meta = cachedMeta(card.type, card.metaId)
    const params = continueSheetParams(card, meta)
    const idTag = videoIdTag(card.videoId, card.metaId)
    const tag = params.episodeLabel ?? (idTag === 'MOVIE' || idTag === 'SERIES' ? null : idTag)
    const playLabel =
      card.kind === 'next' ? (tag ? `Play ${tag}` : 'Play next episode') : tag ? `Resume ${tag}` : 'Resume'
    const target: WatchMarkTarget = {
      videoId: card.videoId,
      itemId: card.itemId,
      name: card.name,
      ...(card.poster ? { poster: card.poster } : {}),
    }
    const saved = downloadEntries(card.videoId)
    show(event, [
      { kind: 'item', label: playLabel, glyph: 'play', onSelect: () => openSheet(params) },
      saved.play,
      {
        kind: 'item',
        label: 'Open details',
        glyph: 'info',
        onSelect: () => push({ name: 'detail', type: card.type, id: card.metaId }),
      },
      SEPARATOR,
      watchedEntry(target, meta?.runtime, tag ?? undefined),
      libraryEntry({ id: card.metaId, type: card.type, name: card.name, ...(card.poster ? { poster: card.poster } : {}) }),
      ...saved.manage,
    ])
  }

  /** An episode row on the title page, which already names the episode. */
  const forEpisode = (event: MenuEvent, { meta, itemId, video, play }: EpisodeMenuTarget) => {
    const state = cachedWatchStates()?.find((row) => row.videoId === video.id)
    const resumable = Boolean(state && !state.watched && state.durationSec > 0 && state.positionSec > 0)
    const saved = downloadEntries(video.id)
    show(event, [
      { kind: 'item', label: resumable ? 'Resume' : 'Play', glyph: 'play', onSelect: play },
      saved.play,
      SEPARATOR,
      watchedEntry(episodeTarget(meta, itemId, video), meta.runtime),
      earlierEntry(meta, itemId, video),
      ...saved.manage,
    ])
  }

  return { forPoster, forContinue, forEpisode }
}
