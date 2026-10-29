import type { LibraryItem, MetaDetail, MetaPreview, MetaVideo, WatchState } from '@halo/core'
import { useQueryClient } from '@tanstack/react-query'
import type { MouseEvent } from 'react'
import { openContextMenu, type ContextMenuEntry } from '../components/ContextMenu'
import { showDialog } from '../components/Dialog'
import { confirmDeleteFromDevice, reportDownloadActionFailure } from '../downloads/downloadPrompts'
import { deleteReady, readyDownloadFor, showInExplorer } from '../downloads/downloadsStore'
import { runtimeMinutes, videoIdTag } from '../format'
import { continueSheetParams } from '../home/continueShelf'
import type { HistoryEntry } from '../history/historyRows'
import type { ContinueCard } from '../home/homeRows'
import { useNav } from '../nav'
import { libraryItemFromMeta, useRemoveFromHistory, useReportWatchState, useUpsertLibrary } from '../queries'
import { playDownload } from '../sources/SourcesSheet'
import {
  earlierEpisodes,
  unwatchedRow,
  watchedRow,
  watchedRowsInOrder,
  type WatchMarkTarget,
} from './watchMarks'
import { movieVideoId, playableFromPreview } from './playableVideo'

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
  const removeFromHistory = useRemoveFromHistory()

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

  /** Forgets videos in the watch history; a failure is said, since the row would otherwise just stay. */
  const forget = async (rows: WatchState[]) => {
    if (rows.length === 0) return
    try {
      await removeFromHistory.mutateAsync(rows)
    } catch (error) {
      await showDialog({ title: 'Could not remove from history', body: String(error), close: 'Close' })
    }
  }

  /** Re-read when chosen, so a row removed since the menu opened is not written again. */
  const removeVideo = (videoId: string) =>
    void forget(cachedWatchStates()?.filter((state) => state.videoId === videoId) ?? [])

  /**
   * Takes a title off Continue watching by forgetting every video of it: the
   * shelf shows a show by its latest episode, so forgetting that one alone
   * would only bring the one before it back. Asks first when that forgets
   * more than the one video on the card.
   */
  const removeTitle = async (itemId: string, name: string) => {
    const count = cachedWatchStates()?.filter((state) => state.itemId === itemId).length ?? 0
    if (count === 0) return
    if (count > 1) {
      const confirmed = await showDialog({
        title: `Remove ${name} from Continue watching?`,
        body: `This forgets all ${count} videos of it in your history: where you stopped and what you watched. Playing it again starts over.`,
        primary: 'Remove',
        close: 'Cancel',
        destructive: true,
      })
      if (!confirmed) return
    }
    await forget(cachedWatchStates()?.filter((state) => state.itemId === itemId) ?? [])
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
      {
        kind: 'item',
        label: 'Delete from device',
        glyph: 'delete',
        danger: true,
        onSelect: () => void deleteDownload(item.job_id),
      },
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
    // The full details, once loaded, may name the video the card does not.
    const videoId = movieVideoId(cached ?? meta)
    const playable = videoId !== meta.id || playableFromPreview(meta)
    // A series' own id is never a video, so it has no download of its own.
    const saved = isSeries ? { play: null, manage: [] } : downloadEntries(videoId, onBeforeOpen)
    show(event, [
      !isSeries && {
        kind: 'item',
        label: 'Play',
        glyph: 'play',
        onSelect: () => {
          onBeforeOpen?.()
          // Without a video id the addons know, the title page finds it.
          if (!playable) return push({ name: 'detail', type: meta.type, id: meta.id })
          openSheet({
            type: meta.type,
            videoId,
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
        watchedEntry({ videoId, itemId, name: meta.name, ...(meta.poster ? { poster: meta.poster } : {}) }, cached?.runtime),
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
      SEPARATOR,
      Boolean(cachedWatchStates()) && {
        kind: 'item',
        label: 'Remove from Continue watching',
        glyph: 'close',
        danger: true,
        onSelect: () => void removeTitle(card.itemId, card.name),
      },
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

  /**
   * A History row: one video played. Left partway it resumes; finished, it
   * plays again from the start (the player only resumes unfinished rows).
   * `play` is the row's own opener, which fetches the details the sheet needs.
   * Earlier episodes are offered only when the show's details are cached, so
   * a right click never fetches.
   */
  const forHistory = (event: MenuEvent, entry: HistoryEntry, play: () => void) => {
    const meta = cachedMeta(entry.type, entry.metaId)
    const video = meta?.videos?.find((candidate) => candidate.id === entry.videoId)
    const idTag = videoIdTag(entry.videoId, entry.metaId)
    const tag = idTag === 'MOVIE' || idTag === 'SERIES' ? null : idTag
    const playLabel = entry.finished ? (tag ? `Play ${tag} again` : 'Play again') : tag ? `Resume ${tag}` : 'Resume'
    const target: WatchMarkTarget = {
      videoId: entry.videoId,
      itemId: entry.itemId,
      name: entry.name,
      ...(entry.poster ? { poster: entry.poster } : {}),
    }
    const saved = downloadEntries(entry.videoId)
    show(event, [
      { kind: 'item', label: playLabel, glyph: entry.finished ? 'retry' : 'play', onSelect: play },
      saved.play,
      {
        kind: 'item',
        label: 'Open details',
        glyph: 'info',
        onSelect: () => push({ name: 'detail', type: entry.type, id: entry.metaId }),
      },
      SEPARATOR,
      watchedEntry(target, meta?.runtime, tag ?? undefined),
      meta && video ? earlierEntry(meta, entry.itemId, video) : null,
      libraryEntry({ id: entry.metaId, type: entry.type, name: entry.name, ...(entry.poster ? { poster: entry.poster } : {}) }),
      ...saved.manage,
      SEPARATOR,
      { kind: 'item', label: 'Remove from history', glyph: 'delete', danger: true, onSelect: () => removeVideo(entry.videoId) },
    ])
  }

  return { forPoster, forContinue, forEpisode, forHistory }
}
