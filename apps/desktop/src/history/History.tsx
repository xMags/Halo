import type { MetaDetail } from '@halo/core'
import { useQueryClient } from '@tanstack/react-query'
import { useMemo, useState, type MouseEvent } from 'react'
import { getClient } from '../api'
import { useTitleMenu } from '../browse/titleMenu'
import { ArtImage } from '../components/ArtImage'
import { metahubPosterUrl } from '../components/artworkUrl'
import { showDialog } from '../components/Dialog'
import { FluentIcon } from '../components/FluentIcon'
import { InfoBar } from '../components/InfoBar'
import { Menu, MenuAnchor, MenuItem } from '../components/Menu'
import { SearchBox } from '../components/SearchBox'
import { SelectorBar } from '../components/SelectorBar'
import { videoIdTag } from '../format'
import { continueSheetParams } from '../home/continueShelf'
import { useNav } from '../nav'
import { useLibrary, useRemoveFromHistory, useWatchStates } from '../queries'
import {
  buildHistory,
  countHistoryViews,
  filterHistoryView,
  groupHistory,
  historyNote,
  historyProgress,
  historyStateText,
  historyTime,
  historyTitleCount,
  searchHistory,
  type HistoryEntry,
  type HistoryView,
} from './historyRows'

const TABS: ReadonlyArray<{ value: HistoryView; label: string }> = [
  { value: 'all', label: 'All' },
  { value: 'movie', label: 'Movies' },
  { value: 'series', label: 'Series' },
  { value: 'inProgress', label: 'In progress' },
]

/**
 * Everything played, newest first, one row per video, from the synced watch
 * history, as a timeline: each section (Today, Yesterday, Earlier this week,
 * then a month) has its heading in a column on the left and its videos as the
 * rows of one card on the right. A row's buttons replace its time under the
 * pointer or with focus inside it; its menu does what the same title's menu
 * does anywhere else and can also remove the row. Clear history waits in the
 * header's menu. A removal forgets the video's progress too, everywhere the
 * history syncs.
 */
export function History() {
  const { data: watchStates, isLoading, error, refetch } = useWatchStates()
  const { data: library } = useLibrary()
  const [view, setView] = useState<HistoryView>('all')
  const [query, setQuery] = useState('')
  const [menuOpen, setMenuOpen] = useState(false)

  // One build serves every tab: the counts are of what each would show under the same search.
  const matching = useMemo(
    () => searchHistory(buildHistory(watchStates, library, 'all'), query),
    [watchStates, library, query],
  )
  const counts = countHistoryViews(matching)
  const groups = useMemo(() => groupHistory(filterHistoryView(matching, view)), [matching, view])
  const hasAny = (watchStates?.length ?? 0) > 0
  const removeFromHistory = useRemoveFromHistory()
  const canClear = !isLoading && !error && hasAny && !removeFromHistory.isPending
  const note = historyNote({
    loading: isLoading,
    failed: Boolean(error),
    hasAny,
    groupsEmpty: groups.length === 0,
    query,
    view,
  })

  /** Every row, whatever the tab and search show: the dialog says so. */
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
    } catch (failure) {
      await showDialog({ title: 'Could not clear your history', body: String(failure), close: 'Close' })
    }
  }

  return (
    <div className="view history-page">
      <header className="history-head">
        <h1 className="history-title">History</h1>
        <div className="history-bar">
          <SelectorBar
            label="Filter history"
            options={TABS.map((tab) => ({ value: tab.value, label: `${tab.label} (${counts[tab.value]})` }))}
            value={view}
            onChange={setView}
          />
          <span className="spacer" />
          <div className="history-search">
            <SearchBox value={query} onChange={setQuery} placeholder="Search history" />
          </div>
          <MenuAnchor>
            <button
              type="button"
              className="btn-subtle btn-subtle-icon"
              aria-label="More history actions"
              title="More history actions"
              onClick={() => setMenuOpen((open) => !open)}
            >
              <FluentIcon glyph="more" size={14} />
            </button>
            <Menu open={menuOpen} onClose={() => setMenuOpen(false)} minWidth={220}>
              <MenuItem
                glyph="delete"
                label="Clear history…"
                danger
                disabled={!canClear}
                onClick={() => {
                  setMenuOpen(false)
                  void clearHistory()
                }}
              />
            </Menu>
          </MenuAnchor>
        </div>
      </header>

      <div className="history-scroll">
        <div className="history-body">
          {error && (
            <InfoBar title="Your history could not be loaded." message="Check the connection to your Halo server.">
              <button type="button" className="btn" onClick={() => void refetch()}>
                Retry
              </button>
            </InfoBar>
          )}
          {note && <p className="history-note">{note}</p>}
          {groups.map((group) => (
            <section key={group.label} className="history-group">
              <div className="history-heading">
                <h2 className="history-label ellipsis">{group.label}</h2>
                <span className="history-count">{historyTitleCount(group.entries.length)}</span>
              </div>
              <div className="history-card">
                {group.entries.map((entry) => (
                  <HistoryRow key={entry.videoId} entry={entry} />
                ))}
              </div>
            </section>
          ))}
        </div>
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
  const progress = historyProgress(entry)
  const showsProgress = !entry.finished && entry.durationSec > 0
  const played = new Date(entry.updatedAt).toLocaleString(undefined, { dateStyle: 'full', timeStyle: 'short' })
  const openLabel = `${entry.name}${entry.finished ? ': open details' : ': resume'}`
  const playLabel = entry.finished ? 'Play again' : 'Resume'

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
      {/* Everything but the buttons: resumes, or opens the title page once finished.
          The time gives way to the buttons under the pointer, so the tooltip
          carries when it was played. */}
      <button
        type="button"
        className="history-open"
        aria-label={openLabel}
        title={`${openLabel}\n${played}`}
        onClick={() => (entry.finished ? openDetails() : void play())}
      >
        <span className="art history-thumb">
          <ArtImage src={entry.poster} fallback={metahubPosterUrl(entry.metaId)} lazy />
        </span>
        <span className="history-text">
          <span className="history-name ellipsis">{entry.name}</span>
          <span className="history-line">
            <span className="history-tag">{tag}</span>
            {showsProgress && (
              <span className="history-track">
                <span style={{ width: `${Math.max(1, Math.round(120 * progress))}px` }} />
              </span>
            )}
            <span className="history-state">
              {entry.finished && <FluentIcon glyph="check" size={12} />}
              <span>{historyStateText(entry)}</span>
            </span>
          </span>
        </span>
      </button>
      <span className="history-end">
        <span className="history-time">{historyTime(entry.updatedAt)}</span>
        <span className="history-actions">
          <button type="button" className="btn-accent history-play" aria-label={playLabel} onClick={() => void play()}>
            <FluentIcon glyph={entry.finished ? 'retry' : 'play'} size={12} />
            <span>{playLabel}</span>
          </button>
          <button
            type="button"
            className="btn-subtle btn-subtle-icon"
            aria-label="More options"
            aria-haspopup="menu"
            title="More options"
            onClick={showMenu}
          >
            <FluentIcon glyph="more" size={14} />
          </button>
        </span>
      </span>
    </div>
  )
}
