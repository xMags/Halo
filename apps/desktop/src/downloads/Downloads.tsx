import { memo, useEffect, useMemo, useState, type ReactNode } from 'react'
import { ArtImage } from '../components/ArtImage'
import { showDialog } from '../components/Dialog'
import { FluentIcon, type FluentGlyph } from '../components/FluentIcon'
import { InfoBar } from '../components/InfoBar'
import { Menu, MenuAnchor, MenuItem } from '../components/Menu'
import { Segmented } from '../components/Segmented'
import { DownloadRowSkeleton } from '../components/Skeleton'
import {
  cancelTransfer,
  changeDownloadFolder,
  deleteReady,
  dismissActionError,
  formatDownloadBytes,
  pauseAll,
  pauseTransfer,
  refreshDownloads,
  resumeAll,
  resumeTransfer,
  retryFailedTransfers,
  showInExplorer,
  useDownloadsSnapshot,
  type DownloadView,
} from './downloadsStore'
import {
  activeCount,
  addedFact,
  aggregateRate,
  chartBars,
  FILTERS,
  filterCounts,
  freeLine,
  matchesFilter,
  noMatchesLine,
  peakText,
  percentText,
  rateFigure,
  rateNote,
  rateTone,
  readyHeadline,
  resolveSelection,
  rowOf,
  splitBytes,
  storageFigures,
  summaryLine,
  type DownloadFilter,
  type DownloadRow,
  type RowTone,
  type StorageFigures,
} from './downloadsPresentation'
import { useNav } from '../nav'
import { playDownload, sheetParamsForDownload } from '../sources/SourcesSheet'
import { confirmDeleteFromDevice } from './downloadPrompts'

/**
 * The Downloads page, on the phone's palette rather than the rest of the
 * app's grey one: one queue panel (rate, the minute of throughput, storage
 * and every unfinished transfer), the finished downloads as a grid of
 * artwork cards, and the 380px detail pane beside them running the full
 * height to the window edge. Everything worded or gated here comes from
 * `downloadsPresentation`.
 */

const STATE_PILLS: Record<DownloadRow['state'], string> = {
  downloading: 'DOWNLOADING',
  queued: 'QUEUED',
  paused: 'PAUSED',
  failed: 'FAILED',
  ondisk: 'ON DISK',
}

const EMPTY_STEPS = [
  'Open a movie or episode from Library or Search.',
  'Pick a source, then choose Save for offline instead of Play.',
  'Keep Halo open until the transfer finishes. It resumes if you close it.',
]

function confirmCancel(): Promise<boolean> {
  return showDialog({
    title: 'Cancel transfer?',
    body: 'The partial file and protected request will be removed from this device.',
    primary: 'Cancel transfer',
    close: 'Keep transfer',
    destructive: true,
  })
}

async function manageFolder(): Promise<void> {
  if ((await changeDownloadFolder()) !== 'failed') return
  await showDialog({
    title: 'Folder could not be changed',
    body: 'Choose an available local folder and try again.',
    close: 'Close',
  })
}

export function Downloads() {
  const { push, openSheet, setRoot } = useNav()
  const { downloads, loading, error, actionError, throughput, pausedAll, directory } =
    useDownloadsSnapshot()
  const [filter, setFilter] = useState<DownloadFilter>('all')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [menuOpen, setMenuOpen] = useState(false)

  // Opening the page re-reads the list, which is also when the engine checks
  // that finished files are still on disk.
  useEffect(() => {
    void refreshDownloads()
  }, [])

  const rows = useMemo(() => downloads.map(rowOf), [downloads])
  const transfers = rows.filter((row) => row.state !== 'ondisk')
  const ready = rows.filter((row) => row.state === 'ondisk')
  const shownTransfers = transfers.filter((row) => matchesFilter(row, filter))
  const shownReady = ready.filter((row) => matchesFilter(row, filter))
  const counts = filterCounts(transfers, ready)

  // The pane follows a download the filter still shows, or the first one it
  // does, and keeps that as the selection, as the native view model does.
  const selected = resolveSelection(selectedId, shownReady, shownTransfers)
  useEffect(() => {
    if (selected && selected.id !== selectedId) setSelectedId(selected.id)
  }, [selected, selectedId])
  const itemFor = (id: string): DownloadView | undefined =>
    downloads.find((item) => item.job_id === id)

  const rate = aggregateRate(downloads)
  const active = activeCount(downloads)
  const storage = storageFigures(downloads, directory?.free_bytes)

  const togglePauseAll = async () => {
    if (pausedAll) {
      resumeAll()
      return
    }
    const confirmed = await showDialog({
      title: 'Pause all transfers?',
      body: 'Every active and queued transfer will stay paused until you resume all.',
      primary: 'Pause all',
      close: 'Cancel',
      defaultButton: 'primary',
    })
    if (confirmed) pauseAll()
  }

  const cancel = async (id: string) => {
    if (await confirmCancel()) cancelTransfer(id)
  }

  const chooseSource = (id: string) => {
    const item = itemFor(id)
    if (item) openSheet(sheetParamsForDownload(item))
  }

  const play = (id: string) => {
    const item = itemFor(id)
    if (item) playDownload(push, item)
  }

  const deleteSelected = async (id: string) => {
    if (await confirmDeleteFromDevice()) void deleteReady(id)
  }

  const empty = rows.length === 0 && !loading
  const noMatches = rows.length > 0 && shownTransfers.length === 0 && shownReady.length === 0
  const summary = summaryLine(rows)

  return (
    <div className="view dl-screen">
      <div className="dl-main">
        <header className="dl-head">
          <h1 className="dl-title">Downloads</h1>
          {summary && <span className="dl-summary ellipsis">{summary}</span>}
          <span className="spacer" />
          <div className="dl-head-actions">
            <Segmented
              pills
              options={FILTERS.map((entry) => ({ ...entry, count: counts[entry.value] }))}
              value={filter}
              onChange={setFilter}
            />
            {(active > 0 || pausedAll) && (
              <button type="button" className="dl-btn dl-head-btn" onClick={() => void togglePauseAll()}>
                <FluentIcon glyph={pausedAll ? 'play' : 'pause'} size={13} />
                <span>{pausedAll ? 'Resume all' : 'Pause all'}</span>
              </button>
            )}
            <MenuAnchor>
              <button
                type="button"
                className="dl-icon-btn"
                aria-label="More download actions"
                onClick={() => setMenuOpen((open) => !open)}
              >
                <FluentIcon glyph="more" size={13} />
              </button>
              <Menu open={menuOpen} onClose={() => setMenuOpen(false)} minWidth={236}>
                <MenuItem
                  label="Manage download folder"
                  onClick={() => {
                    setMenuOpen(false)
                    void manageFolder()
                  }}
                />
                <MenuItem
                  label="Open folder in Explorer"
                  onClick={() => {
                    setMenuOpen(false)
                    void showInExplorer()
                  }}
                />
                <MenuItem
                  label="Retry all failed transfers"
                  onClick={() => {
                    setMenuOpen(false)
                    retryFailedTransfers()
                  }}
                />
              </Menu>
            </MenuAnchor>
          </div>
        </header>

        <div className="dl-scroll">
          {actionError && (
            <InfoBar title="Download action failed" message={actionError} onClose={dismissActionError} />
          )}
          {error && !actionError && <InfoBar title="Downloads could not be read" message={error} />}

          <section className="dl-queue-panel" aria-label="Queue">
            <div className="dl-instrument">
              <div className="dl-throughput">
                <RateLine rate={rate} pausedAll={pausedAll} downloads={downloads} throughput={throughput} />
                <ThroughputChart throughput={throughput} />
                <div className="dl-chart-foot">
                  <span className="kicker">60 s ago</span>
                  <span className="kicker">Now</span>
                </div>
              </div>
              <div className="dl-instrument-rule" />
              <StorageBlock storage={storage} />
            </div>

            {shownTransfers.length > 0 && (
              <div className="dl-queue">
                {shownTransfers.map((row) => (
                  <QueueRow
                    key={row.id}
                    row={row}
                    selected={row.id === selected?.id}
                    onSelect={() => setSelectedId(row.id)}
                    onChooseSource={() => chooseSource(row.id)}
                    onCancel={() => void cancel(row.id)}
                  />
                ))}
              </div>
            )}

            {loading && rows.length === 0 && (
              <div className="dl-queue">
                {Array.from({ length: 3 }).map((_, index) => (
                  <DownloadRowSkeleton key={`skeleton-${index}`} />
                ))}
              </div>
            )}
          </section>

          {shownReady.length > 0 && (
            <section className="dl-ready" aria-label="Ready to watch">
              <div className="dl-ready-head">
                <span className="kicker">Ready to watch</span>
                <span className="kicker dl-ready-count">{readyHeadline(ready.length, storage.storedBytes)}</span>
              </div>
              <div className="dl-grid">
                {shownReady.map((row) => (
                  <ReadyCard
                    key={row.id}
                    row={row}
                    selected={row.id === selected?.id}
                    onSelect={() => setSelectedId(row.id)}
                    onPlay={() => {
                      setSelectedId(row.id)
                      play(row.id)
                    }}
                  />
                ))}
              </div>
            </section>
          )}

          {empty && (
            <div className="dl-empty-card">
              <div className="art dl-empty-art">
                <div className="art-label">
                  NOTHING
                  <br />
                  ON DISK
                </div>
              </div>
              <div className="dl-empty-copy">
                <div className="dl-empty-title">Nothing downloaded yet</div>
                <div className="dl-empty-steps">
                  {EMPTY_STEPS.map((step, index) => (
                    <div key={step} className="dl-empty-step">
                      <span className="kicker dl-step-number">{`0${index + 1}`}</span>
                      <span>{step}</span>
                    </div>
                  ))}
                </div>
                {/* The folder card in the pane beside this one shows whenever
                    this card does, so the folder action is not repeated here. */}
                <div className="dl-empty-actions">
                  <button type="button" className="dl-btn dl-btn-accent" onClick={() => setRoot('library')}>
                    Browse library
                  </button>
                </div>
              </div>
            </div>
          )}

          {noMatches && (
            <div className="dl-nomatch-card">
              <div className="dl-nomatch-title">Nothing in this filter</div>
              <div className="dl-nomatch-body">{noMatchesLine(filter)}</div>
            </div>
          )}
        </div>
      </div>

      <aside className="dl-pane" aria-label="Details">
        {selected ? (
          <DetailPane
            row={selected}
            onPlay={() => play(selected.id)}
            onOpenFolder={() => void showInExplorer(selected.id)}
            onPause={() => pauseTransfer(selected.id)}
            onResume={() => resumeTransfer(selected.id)}
            onCancel={() => void cancel(selected.id)}
            onChooseSource={() => chooseSource(selected.id)}
            onDelete={() => void deleteSelected(selected.id)}
          />
        ) : (
          <div className="dl-folder-card">
            <div className="kicker">Download folder</div>
            <button type="button" className="dl-btn dl-btn-block" onClick={() => void manageFolder()}>
              <FluentIcon glyph="folder" size={15} />
              <span>Manage folder</span>
            </button>
          </div>
        )}
      </aside>
    </div>
  )
}

/** The big rate, what is carrying it, and the minute's peak. */
function RateLine({
  rate,
  pausedAll,
  downloads,
  throughput,
}: {
  rate: number
  pausedAll: boolean
  downloads: readonly DownloadView[]
  throughput: readonly number[]
}) {
  const figure = rateFigure(rate, pausedAll)
  const note = rateNote(downloads)
  return (
    <div className="dl-rate">
      <span className={`dl-rate-value rate-${rateTone(rate, pausedAll)}`}>{figure.value}</span>
      {figure.unit && <span className="dl-rate-unit">{figure.unit}</span>}
      {note.kind === 'connections' ? (
        <span className="dl-conn-pill">
          <span className="dl-conn-dot" />
          {note.text}
        </span>
      ) : (
        <span className="dl-rate-note">{note.text}</span>
      )}
      <span className="spacer" />
      <span className="kicker dl-peak">{peakText(throughput)}</span>
    </div>
  )
}

/**
 * The phone's chart. Each bar is keyed by its slot, never by its sample, so a
 * new sample changes the heights of bars that stay mounted and every one of
 * them eases to its new height (a CSS transition on height), which is what
 * makes the row flow instead of jump. Memoised on the history array, which
 * changes only when a sample is taken, so progress ticks never touch it.
 */
const ThroughputChart = memo(function ThroughputChart({ throughput }: { throughput: readonly number[] }) {
  const bars = chartBars(throughput)
  return (
    <div className="dl-chart" role="img" aria-label="Throughput over the last minute">
      {bars.map((bar, slot) => (
        <span
          key={slot}
          className="dl-chart-bar"
          style={{ height: `${bar.fraction * 100}%`, opacity: bar.opacity }}
        />
      ))}
    </div>
  )
})

function StorageBlock({ storage }: { storage: StorageFigures }) {
  const used = splitBytes(storage.storedBytes + storage.inFlightBytes)
  const flightFraction = Math.max(0, storage.usedFraction - storage.storedFraction)
  return (
    <div className="dl-storage">
      <span className="kicker">Storage</span>
      <div className="dl-storage-figure">
        <span className="dl-storage-value">{used.value}</span>
        <span className="dl-storage-unit">
          {used.unit} · {freeLine(storage.freeBytes)}
        </span>
      </div>
      <div className="dl-storage-track">
        {storage.storedFraction > 0 && (
          <span className="dl-storage-stored" style={{ width: `${storage.storedFraction * 100}%` }} />
        )}
        {flightFraction > 0 && (
          <span className="dl-storage-flight" style={{ width: `${flightFraction * 100}%` }} />
        )}
      </div>
      <div className="dl-legend">
        <div className="dl-legend-item">
          <span className="dl-swatch dl-swatch-stored" />
          <span className="spacer">Stored</span>
          <span className="dl-legend-value">{formatDownloadBytes(storage.storedBytes)}</span>
        </div>
        <div className="dl-legend-item dl-legend-flight">
          <span className="dl-swatch dl-swatch-flight" />
          <span className="spacer">In flight</span>
          <span className="dl-legend-value">{formatDownloadBytes(storage.inFlightBytes)}</span>
        </div>
      </div>
    </div>
  )
}

/** The pane's state label sits on its tint alone. */
function StatePill({ state }: { state: DownloadRow['state'] }) {
  return <span className={`dl-state-pill dl-state-${state}`}>{STATE_PILLS[state]}</span>
}

function ProgressTrack({ progress, tone }: { progress: number; tone: RowTone }) {
  return (
    <div className="dl-bar">
      <div className={`dl-bar-fill dl-fill-${tone}`} style={{ width: `${progress * 100}%` }} />
    </div>
  )
}

/**
 * The row is a div with the button role rather than a `<button>`: its own
 * pause and cancel controls sit inside it, and a button inside a button is
 * invalid HTML, so the row carries the keyboard behaviour itself.
 */
function RowFrame({
  selected,
  waiting,
  onSelect,
  children,
}: {
  selected: boolean
  waiting: boolean
  onSelect: () => void
  children: ReactNode
}) {
  return (
    <div
      className={`dl-row ${selected ? 'dl-row-sel' : ''} ${waiting ? 'dl-row-waiting' : ''}`}
      role="button"
      tabIndex={0}
      aria-pressed={selected}
      onClick={onSelect}
      onKeyDown={(event) => {
        if (event.target !== event.currentTarget) return
        if (event.key !== 'Enter' && event.key !== ' ') return
        event.preventDefault()
        onSelect()
      }}
    >
      {children}
    </div>
  )
}

function RowIconButton({
  glyph,
  label,
  danger,
  onClick,
}: {
  glyph: FluentGlyph
  label: string
  danger?: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      className={`dl-icon-btn ${danger ? 'dl-icon-btn-danger' : ''}`}
      aria-label={label}
      title={label}
      onClick={(event) => {
        event.stopPropagation()
        onClick()
      }}
    >
      <FluentIcon glyph={glyph} size={13} />
    </button>
  )
}

/** One unfinished download in the queue panel. */
function QueueRow({
  row,
  selected,
  onSelect,
  onChooseSource,
  onCancel,
}: {
  row: DownloadRow
  selected: boolean
  onSelect: () => void
  onChooseSource: () => void
  onCancel: () => void
}) {
  const waiting = row.state === 'queued'
  return (
    <RowFrame selected={selected} waiting={waiting} onSelect={onSelect}>
      <div className="art dl-thumb">
        <ArtImage src={row.rowArtwork} />
      </div>
      <div className="dl-row-body">
        <div className="dl-title-line">
          {row.episodeLabel && <span className="dl-tag">{row.episodeLabel}</span>}
          <span className="dl-name">{row.name}</span>
          <span className="dl-sub ellipsis">{row.sub}</span>
        </div>
        {waiting ? (
          <span className="kicker dl-up-next">Up next</span>
        ) : (
          <>
            <ProgressTrack progress={row.progress} tone={row.tone} />
            <div className={`dl-meta dl-meta-${row.tone}`}>
              {row.transferMeta.map((part, index) => (
                <span key={index} className={index === 0 ? 'dl-meta-lead ellipsis' : 'ellipsis'}>
                  {part}
                </span>
              ))}
            </div>
          </>
        )}
      </div>
      <div className="dl-row-actions">
        {row.requiresNewSource && (
          <button
            type="button"
            className="dl-btn dl-row-btn"
            onClick={(event) => {
              event.stopPropagation()
              onChooseSource()
            }}
          >
            <FluentIcon glyph="switchSource" size={14} />
            <span>Choose source again</span>
          </button>
        )}
        {row.canPause && (
          <RowIconButton glyph="pause" label="Pause transfer" onClick={() => pauseTransfer(row.id)} />
        )}
        {row.state === 'paused' && (
          <RowIconButton glyph="play" label="Resume transfer" onClick={() => resumeTransfer(row.id)} />
        )}
        {row.canRetry && (
          <RowIconButton glyph="retry" label="Retry this source" onClick={() => resumeTransfer(row.id)} />
        )}
        <RowIconButton glyph="delete" label="Cancel transfer" danger onClick={onCancel} />
      </div>
    </RowFrame>
  )
}

/**
 * A finished download as an artwork card. The card and its play button are
 * siblings, not nested, since a button inside a button is invalid HTML; the
 * play button sits in a layer the size of the art, over its corner.
 */
function ReadyCard({
  row,
  selected,
  onSelect,
  onPlay,
}: {
  row: DownloadRow
  selected: boolean
  onSelect: () => void
  onPlay: () => void
}) {
  return (
    <div className={`dl-card ${selected ? 'dl-card-sel' : ''}`}>
      <button
        type="button"
        className="dl-card-hit"
        aria-pressed={selected}
        title={row.cardSub ? `${row.cardTitle} · ${row.cardSub}` : row.cardTitle}
        onClick={onSelect}
        onDoubleClick={onPlay}
      >
        <div className="art dl-card-art">
          <ArtImage src={row.rowArtwork} lazy />
          {row.qualityBadge && (
            <span className={`dl-badge dl-badge-quality ${row.qualityBadge.gold ? 'dl-badge-gold' : ''}`}>
              {row.qualityBadge.label}
            </span>
          )}
          {row.episodeLabel && <span className="dl-badge dl-badge-episode">{row.episodeLabel}</span>}
        </div>
        <div className="dl-card-text">
          <span className="dl-card-title ellipsis">{row.cardTitle}</span>
          {row.cardSub && <span className="dl-card-sub ellipsis">{row.cardSub}</span>}
          <span className="dl-card-meta ellipsis">{row.cardMeta}</span>
        </div>
      </button>
      <div className="dl-card-overlay">
        <button
          type="button"
          className="dl-card-play"
          aria-label={`Play ${row.cardSub ? `${row.cardTitle} · ${row.cardSub}` : row.cardTitle} offline`}
          title="Play offline"
          onClick={onPlay}
        >
          <FluentIcon glyph="play" size={13} />
        </button>
      </div>
    </div>
  )
}

/** The 380px pane: everything about one download, including what is too long for a card. */
function DetailPane({
  row,
  onPlay,
  onOpenFolder,
  onPause,
  onResume,
  onCancel,
  onChooseSource,
  onDelete,
}: {
  row: DownloadRow
  onPlay: () => void
  onOpenFolder: () => void
  onPause: () => void
  onResume: () => void
  onCancel: () => void
  onChooseSource: () => void
  onDelete: () => void
}) {
  const ready = row.state === 'ondisk'
  const facts = [
    { label: 'Quality', value: row.qualityLine },
    { label: ready ? 'Size' : 'Downloaded', value: row.downloadedLine },
    { label: 'Subtitle', value: row.subs },
    { label: 'Added', value: addedFact(row.addedLabel) },
  ]
  return (
    <div className="dl-pane-inner">
      <div className="art dl-pane-art">
        <ArtImage src={row.rowArtwork} />
      </div>
      <div className="dl-pane-head">
        <div className="dl-pane-title">
          <h2 className="dl-pane-name">{row.name}</h2>
          {!ready && <StatePill state={row.state} />}
        </div>
        <span className="dl-pane-meta">{row.paneMeta}</span>
      </div>

      {ready && (
        <div className="dl-pane-actions">
          <button type="button" className="dl-btn dl-btn-play" onClick={onPlay}>
            <FluentIcon glyph="play" size={14} />
            <span>Play offline</span>
          </button>
          <button type="button" className="dl-btn dl-btn-block" onClick={onOpenFolder}>
            <FluentIcon glyph="folder" size={15} />
            <span>Open folder</span>
          </button>
        </div>
      )}

      {!ready && (
        <div className="dl-pane-transfer">
          <ProgressTrack progress={row.progress} tone={row.tone} />
          <div className="dl-pane-lead">
            <span className={`spacer dl-lead-${row.tone}`}>{row.lead}</span>
            <span>{percentText(row.progress)}</span>
          </div>
          <div className="dl-pane-controls">
            {row.state === 'downloading' && (
              <button type="button" className="dl-btn dl-btn-block" onClick={onPause}>
                <FluentIcon glyph="pause" size={13} />
                <span>Pause transfer</span>
              </button>
            )}
            {row.canResume && (
              <button type="button" className="dl-btn dl-btn-block" onClick={onResume}>
                <FluentIcon glyph="play" size={13} />
                <span>Resume transfer</span>
              </button>
            )}
            <button
              type="button"
              className="dl-icon-btn dl-icon-btn-danger dl-pane-cancel"
              aria-label="Cancel transfer"
              title="Cancel transfer"
              onClick={onCancel}
            >
              <FluentIcon glyph="delete" size={13} />
            </button>
          </div>
          {row.requiresNewSource && (
            <button type="button" className="dl-btn dl-btn-block" onClick={onChooseSource}>
              <FluentIcon glyph="switchSource" size={14} />
              <span>Choose source again</span>
            </button>
          )}
        </div>
      )}

      <dl className="dl-facts">
        {facts.map((fact) => (
          <div key={fact.label} className="dl-fact">
            <dt>{fact.label}</dt>
            <dd>{fact.value}</dd>
          </div>
        ))}
        {/* Release names run far longer than the pane is wide; the tooltip carries the whole name. */}
        <div className="dl-fact">
          <dt>File</dt>
          <dd className="ellipsis" title={row.fileName}>
            {row.fileName}
          </dd>
        </div>
      </dl>

      {/* The failure sentence is already on the lead line; this says what
          happens to the bytes on disk, which is the part a viewer acts on. */}
      {row.state === 'failed' && (
        <div className="dl-pane-note">THE PARTIAL FILE IS KEPT UNTIL A NEW SOURCE FINISHES.</div>
      )}

      {ready && (
        <button type="button" className="dl-btn dl-btn-delete" onClick={onDelete}>
          <FluentIcon glyph="delete" size={15} />
          <span>Delete download</span>
        </button>
      )}
    </div>
  )
}
