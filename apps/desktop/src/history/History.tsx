import type { MetaDetail } from '@halo/core'
import { useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { getClient } from '../api'
import { ArtImage } from '../components/ArtImage'
import { SectionHeader } from '../components/SectionHeader'
import { Segmented } from '../components/Segmented'
import { formatTimeLeft, videoIdTag } from '../format'
import { continueSheetParams } from '../home/continueShelf'
import type { HomeFilter } from '../home/homeRows'
import { useNav } from '../nav'
import { useLibrary, useWatchStates } from '../queries'
import { buildHistory, groupHistory, historyTime, type HistoryEntry } from './historyRows'

const FILTERS: ReadonlyArray<{ value: HomeFilter; label: string }> = [
  { value: 'all', label: 'All' },
  { value: 'movie', label: 'Movies' },
  { value: 'series', label: 'Series' },
]

/**
 * Everything played, newest first, one row per video, from the synced watch
 * history. Read only: nothing here saves to the library or removes history.
 */
export function History() {
  const { data: watchStates, isLoading, error } = useWatchStates()
  const { data: library } = useLibrary()
  const [filter, setFilter] = useState<HomeFilter>('all')

  const groups = useMemo(
    () => groupHistory(buildHistory(watchStates, library, filter)),
    [watchStates, library, filter],
  )
  const hasAny = (watchStates?.length ?? 0) > 0

  return (
    <div className="view view-col">
      <SectionHeader title="History" />
      <div className="lib-bar">
        <Segmented options={FILTERS} value={filter} onChange={setFilter} />
      </div>

      <div className="history-scroll">
        {error && <div className="state-note error-text">{String(error)}</div>}
        {isLoading && <div className="state-note">Loading your history…</div>}
        {!isLoading && !error && !hasAny && (
          <div className="state-note">Nothing watched yet. What you play shows up here.</div>
        )}
        {hasAny && groups.length === 0 && (
          <div className="state-note">No {filter === 'movie' ? 'movies' : 'series'} in your history.</div>
        )}
        {groups.map((group) => (
          <section key={group.label} className="history-group">
            <h2 className="history-label">{group.label}</h2>
            {group.entries.map((entry) => (
              <HistoryRow key={entry.videoId} entry={entry} />
            ))}
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
 */
function HistoryRow({ entry }: { entry: HistoryEntry }) {
  const { push, openSheet } = useNav()
  const queryClient = useQueryClient()
  const tag = videoIdTag(entry.videoId, entry.metaId)
  const progress = entry.finished || entry.durationSec <= 0 ? 0 : entry.positionSec / entry.durationSec
  const state = entry.finished
    ? 'WATCHED'
    : entry.durationSec > 0
      ? formatTimeLeft(entry.positionSec, entry.durationSec).toUpperCase()
      : 'STARTED'

  const open = async () => {
    if (entry.finished) {
      push({ name: 'detail', type: entry.type, id: entry.metaId })
      return
    }
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

  return (
    <button type="button" className="history-row" onClick={() => void open()} title={entry.name}>
      <div className="art history-thumb">
        <ArtImage src={entry.poster} lazy />
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
      </div>
      <div className="history-time">{historyTime(entry.updatedAt)}</div>
    </button>
  )
}
