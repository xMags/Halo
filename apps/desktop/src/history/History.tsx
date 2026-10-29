import type { MetaDetail } from '@halo/core'
import { useQueryClient } from '@tanstack/react-query'
import { useMemo, useState, type MouseEvent } from 'react'
import { getClient } from '../api'
import { useTitleMenu } from '../browse/titleMenu'
import { ArtImage } from '../components/ArtImage'
import { metahubPosterUrl } from '../components/artworkUrl'
import { showDialog } from '../components/Dialog'
import { FluentIcon } from '../components/FluentIcon'
import { SearchBox } from '../components/SearchBox'
import { SectionHeader } from '../components/SectionHeader'
import { Segmented } from '../components/Segmented'
import { formatTimeLeft, videoIdTag } from '../format'
import { continueSheetParams } from '../home/continueShelf'
import type { HomeFilter } from '../home/homeRows'
import { useNav } from '../nav'
import { useLibrary, useRemoveFromHistory, useWatchStates } from '../queries'
import { buildHistory, groupHistory, historyTime, searchHistory, type HistoryEntry } from './historyRows'

const FILTERS: ReadonlyArray<{ value: HomeFilter; label: string }> = [
  { value: 'all', label: 'All' },
  { value: 'movie', label: 'Movies' },
  { value: 'series', label: 'Series' },
]

/**
 * Everything played, newest first, one row per video, from the synced watch
 * history. A row's menu does what the same title's menu does anywhere else,
 * and can also remove the row; Clear history removes every row. A removal
 * forgets the video's progress too, everywhere the history syncs.
 */
export function History() {
  const { data: watchStates, isLoading, error } = useWatchStates()
  const { data: library } = useLibrary()
  const [filter, setFilter] = useState<HomeFilter>('all')
  const [query, setQuery] = useState('')

  const groups = useMemo(
    () => groupHistory(searchHistory(buildHistory(watchStates, library, filter), query)),
    [watchStates, library, filter, query],
  )
  const hasAny = (watchStates?.length ?? 0) > 0
  const searching = query.trim() !== ''
  const removeFromHistory = useRemoveFromHistory()

  /** Every row, whatever the filter and search show: the dialog says so. */
  const clearHistory = async () => {
    const count = watchStates?.length ?? 0
    if (count === 0) return
    const confirmed = await showDialog({
      title: 'Clear your watch history?',
      body: `This forgets all ${count} videos you have played: where you stopped, what you watched, and everything in Continue watching. It cannot be undone.`,
      primary: 'Clear history',
      close: 'Cancel',
      destructive: true,
    })
    if (!confirmed) return
    try {
      await removeFromHistory.mutateAsync(watchStates ?? [])
    } catch (error) {
      await showDialog({ title: 'Could not clear your history', body: String(error), close: 'Close' })
    }
  }

  return (
    <div className="view view-col">
      <SectionHeader title="History">
        <SearchBox value={query} onChange={setQuery} placeholder="Search history" />
        <Segmented options={FILTERS} value={filter} onChange={setFilter} />
        <button
          type="button"
          className="btn-danger history-clear"
          disabled={!hasAny || removeFromHistory.isPending}
          onClick={() => void clearHistory()}
        >
          <FluentIcon glyph="delete" size={13} />
          <span>Clear history</span>
        </button>
      </SectionHeader>

      <div className="history-scroll">
        {error && <div className="state-note error-text">{String(error)}</div>}
        {isLoading && <div className="state-note">Loading your history…</div>}
        {!isLoading && !error && !hasAny && (
          <div className="state-note">Nothing watched yet. What you play shows up here.</div>
        )}
        {hasAny && groups.length === 0 && (
          <div className="state-note">
            {searching
              ? `Nothing in your history matches “${query.trim()}”.`
              : `No ${filter === 'movie' ? 'movies' : 'series'} in your history.`}
          </div>
        )}
        {groups.map((group) => (
          <section key={group.label} className="history-group">
            <h2 className="history-label">
              {group.label}
              <span className="history-count">{group.entries.length}</span>
            </h2>
            <div className="history-grid">
              {group.entries.map((entry) => (
                <HistoryRow key={entry.videoId} entry={entry} />
              ))}
            </div>
          </section>
        ))}
      </div>
    </div>
  )
}

/**
 * One played video. Left partway, it opens that video's sources to carry on,
 * as a continue card does; finished, it opens the title page. The row itself
 * fetches nothing, so a long history costs no requests: the episode tag comes
 * from the video id, and the title's details are asked for only on a click.
 * Right-click, the menu key or the More button open the title menu.
 */
function HistoryRow({ entry }: { entry: HistoryEntry }) {
  const { push, openSheet } = useNav()
  const queryClient = useQueryClient()
  const titleMenu = useTitleMenu()
  const tag = videoIdTag(entry.videoId, entry.metaId)
  const progress = entry.finished || entry.durationSec <= 0 ? 0 : entry.positionSec / entry.durationSec
  const state = entry.finished
    ? 'WATCHED'
    : entry.durationSec > 0
      ? formatTimeLeft(entry.positionSec, entry.durationSec).toUpperCase()
      : 'STARTED'
  const played = new Date(entry.updatedAt).toLocaleString(undefined, { dateStyle: 'full', timeStyle: 'short' })

  /** The sources sheet for this video: resumes it, or plays it again once finished. */
  const play = async () => {
    // The details name the episode and its runtime; the sheet still opens
    // without them when they cannot be fetched.
    const meta = await queryClient
      .fetchQuery({
        queryKey: ['meta', entry.type, entry.metaId],
        staleTime: 10 * 60_000,
        queryFn: async (): Promise<MetaDetail> => (await getClient().getMeta(entry.type, entry.metaId)).meta,
      })
      .catch(() => undefined)
    openSheet(continueSheetParams({ ...entry, kind: 'resume' }, meta))
  }
  const openDetails = () => push({ name: 'detail', type: entry.type, id: entry.metaId })
  const showMenu = (event: MouseEvent<HTMLElement>) => titleMenu.forHistory(event, entry, () => void play())

  return (
    <div className="history-row" onContextMenu={showMenu}>
      <button
        type="button"
        className="history-open"
        onClick={() => (entry.finished ? openDetails() : void play())}
        title={entry.finished ? `${entry.name}: open details` : `${entry.name}: resume`}
      >
        <div className="art history-thumb">
          <ArtImage src={entry.poster} fallback={metahubPosterUrl(entry.metaId)} lazy />
          {progress > 0 && (
            <div className="art-progress">
              <div style={{ width: `${Math.round(progress * 100)}%` }} />
            </div>
          )}
        </div>
        <div className="history-body">
          <div className="history-title ellipsis">{entry.name}</div>
          <div className="card-meta">
            {tag} · {state}
          </div>
          <div className="history-time" title={played}>
            {historyTime(entry.updatedAt)}
          </div>
        </div>
      </button>
      <div className="history-actions">
        <button
          type="button"
          className="icon-btn icon-btn-bare"
          aria-label={entry.finished ? 'Play again' : 'Resume'}
          title={entry.finished ? 'Play again' : 'Resume'}
          onClick={() => void play()}
        >
          <FluentIcon glyph={entry.finished ? 'retry' : 'play'} />
        </button>
        <button
          type="button"
          className="icon-btn icon-btn-bare"
          aria-label="More options"
          aria-haspopup="menu"
          title="More options"
          onClick={showMenu}
        >
          <FluentIcon glyph="more" />
        </button>
      </div>
    </div>
  )
}
