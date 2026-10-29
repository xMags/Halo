import type { LibraryItem, WatchState } from '@halo/core'
import { isFinishedWatchState, itemMetaId, itemType, matchesHomeFilter, type HomeFilter } from '../home/homeRows'

/**
 * The History page's rows: every video played, newest first, one row per
 * video (each episode of a show on its own line), read from the synced watch
 * history only. Nothing here touches the saved library, which only lends a
 * name or poster to rows written before they carried their own.
 */

export interface HistoryEntry {
  itemId: string
  type: string
  metaId: string
  videoId: string
  name: string
  poster: string | undefined
  positionSec: number
  durationSec: number
  /** Watched, or so close to the end that nothing is left to return to. */
  finished: boolean
  /** When it was last played, epoch milliseconds. */
  updatedAt: number
}

export interface HistoryGroup {
  label: string
  entries: HistoryEntry[]
}

const DAY_MS = 24 * 60 * 60 * 1000

/** Watch rows as history entries, newest first. A row nothing names is left out. */
export function buildHistory(
  watchStates: WatchState[] | undefined,
  library: LibraryItem[] | undefined,
  filter: HomeFilter,
): HistoryEntry[] {
  const libById = new Map((library ?? []).filter((item) => !item.removedAt).map((item) => [item.id, item]))
  return [...(watchStates ?? [])]
    .sort((a, b) => b.updatedAt - a.updatedAt)
    .flatMap((row): HistoryEntry[] => {
      const entry = libById.get(row.itemId)
      const name = row.name ?? entry?.name
      const type = itemType(row.itemId)
      if (!name || !matchesHomeFilter(filter, type)) return []
      return [
        {
          itemId: row.itemId,
          type,
          metaId: itemMetaId(row.itemId),
          videoId: row.videoId,
          name,
          poster: row.poster ?? entry?.poster,
          positionSec: row.positionSec,
          durationSec: row.durationSec,
          finished: isFinishedWatchState(row),
          updatedAt: row.updatedAt,
        },
      ]
    })
}

/**
 * Splits newest-first entries into the page's sections by the viewer's local
 * calendar: Today, Yesterday, Earlier this week (the five days before), then
 * one section per month ("October 2026").
 */
export function groupHistory(entries: HistoryEntry[], now = Date.now()): HistoryGroup[] {
  const today = startOfLocalDay(now)
  const groups: HistoryGroup[] = []
  for (const entry of entries) {
    const label = historyGroupLabel(entry.updatedAt, today)
    const last = groups[groups.length - 1]
    if (last && last.label === label) last.entries.push(entry)
    else groups.push({ label, entries: [entry] })
  }
  return groups
}

function historyGroupLabel(timestamp: number, today: number): string {
  const day = startOfLocalDay(timestamp)
  // Rounded: a day across a daylight-saving change is 23 or 25 hours long.
  const daysAgo = Math.round((today - day) / DAY_MS)
  if (daysAgo <= 0) return 'Today'
  if (daysAgo === 1) return 'Yesterday'
  if (daysAgo < 7) return 'Earlier this week'
  return new Date(timestamp).toLocaleDateString(undefined, { month: 'long', year: 'numeric' })
}

function startOfLocalDay(timestamp: number): number {
  const date = new Date(timestamp)
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
}

/**
 * When an entry was played, as its row prints it: the time of day within the
 * last two days, the weekday within the week, the date before that.
 */
export function historyTime(timestamp: number, now = Date.now()): string {
  const daysAgo = Math.round((startOfLocalDay(now) - startOfLocalDay(timestamp)) / DAY_MS)
  const date = new Date(timestamp)
  if (daysAgo <= 1) return date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
  if (daysAgo < 7) return date.toLocaleDateString(undefined, { weekday: 'long' })
  return date.toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
}

/**
 * The entries whose title contains every word of `query`, ignoring case and
 * accents, so "spider verse" finds "Spider-Man: Across the Spider-Verse".
 */
export function searchHistory(entries: HistoryEntry[], query: string): HistoryEntry[] {
  const words = foldForSearch(query).split(/\s+/).filter(Boolean)
  if (words.length === 0) return entries
  return entries.filter((entry) => {
    const name = foldForSearch(entry.name)
    return words.every((word) => name.includes(word))
  })
}

function foldForSearch(text: string): string {
  return text.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()
}
