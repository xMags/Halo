import { memo, useEffect, useMemo, useState, type ReactNode } from 'react'
import { ArtImage } from '../components/ArtImage'
import { openContextMenu } from '../components/ContextMenu'
import { showDialog } from '../components/Dialog'
import { FluentIcon } from '../components/FluentIcon'
import { InfoBar } from '../components/InfoBar'
import { Menu, MenuAnchor, MenuItem, MenuSeparator } from '../components/Menu'
import { DownloadRowSkeleton } from '../components/Skeleton'
import {
  cancelTransfer,
  changeDownloadFolder,
  deleteAllDownloads,
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
  rateFigure,
  readyHeadline,
  rowOf,
  splitBytes,
  storageFigures,
  summaryLine,
  type DownloadFilter,
  type DownloadRow,
  type StorageFigures,
} from './downloadsPresentation'
import { useNav } from '../nav'
import { playDownload, sheetParamsForDownload } from '../sources/SourcesSheet'
import { confirmDeleteFromDevice } from './downloadPrompts'

/**
 * The Downloads page, in Windows' own (Fluent) shapes: filters and the bulk
 * actions under the title, a status card that opens on the speed and storage,
 * failed transfers as error bars, the running transfers as cards whose file
 * facts open on demand, the queue folded into one card, and the finished
 * files as a grid. What is not needed all the time (the speed graph, storage,
 * file facts, the queue, cancel and the rarer folder actions) stays behind a
 * click or a menu. Everything worded or gated here comes from
 * `downloadsPresentation`.
 */

const EMPTY_STEPS = [
  'Open a movie or an episode from Library or Search.',
  'Pick a source, then choose Save for offline instead of Play.',
  'Keep Halo open until the transfer finishes. It resumes if you close it.',
]

/** The gap between the parts of a row's meta line. */
const META_GAP = '    '

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

/** Everything this account has on the PC goes, so it is asked first, with the count, and Cancel is the default. */
function confirmDeleteAll(count: number): Promise<boolean> {
  return showDialog({
    title: 'Delete all downloads?',
    body: `All ${count} ${count === 1 ? 'download goes' : 'downloads go'} from this PC, including any still downloading. The files are deleted and cannot be recovered.`,
    primary: 'Delete all',
    close: 'Cancel',
    destructive: true,
    defaultButton: 'close',
  })
}

export function Downloads() {
  const { push, openSheet, setRoot } = useNav()
  const { downloads, loading, error, actionError, throughput, pausedAll, directory } =
    useDownloadsSnapshot()
  const [filter, setFilter] = useState<DownloadFilter>('all')
  const [menuOpen, setMenuOpen] = useState(false)
  const [statusOpen, setStatusOpen] = useState(false)
  const [waitingOpen, setWaitingOpen] = useState(false)
  /** The running row whose file facts are open; one at a time, none at first. */
  const [expandedId, setExpandedId] = useState<string | null>(null)

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

  // Where an unfinished transfer sits: moving (or paused by the viewer) in
  // the main list, waiting its turn in the folded "Up next" card, or stopped
  // and needing the viewer, as an error bar above both.
  const running = shownTransfers.filter((row) => row.state === 'downloading' || row.state === 'paused')
  const waiting = shownTransfers.filter((row) => row.state === 'queued')
  const attention = shownTransfers.filter((row) => row.state === 'failed')

  const itemFor = (id: string): DownloadView | undefined => downloads.find((item) => item.job_id === id)

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

  // The page the download came from: a film's own, an episode's show. A
  // download without the title's id (an addon that gave none) has no page.
  const detailsFor = (id: string): (() => void) | null => {
    const media = itemFor(id)?.media
    if (!media?.meta_id || !media.media_type) return null
    const metaId = media.meta_id
    const type = media.media_type
    return () => push({ name: 'detail', type, id: metaId })
  }

  const deleteOne = async (id: string) => {
    if (await confirmDeleteFromDevice()) void deleteReady(id)
  }

  const deleteAll = async () => {
    if (counts.all === 0) return
    if (await confirmDeleteAll(counts.all)) void deleteAllDownloads()
  }

  const empty = rows.length === 0 && !loading
  const noMatches = rows.length > 0 && shownTransfers.length === 0 && shownReady.length === 0
  const figure = rateFigure(rate, false)
  const statusLine = `${pausedAll ? 'All paused' : figure.unit ? `${figure.value} ${figure.unit}` : figure.value} · ${freeLine(storage.freeBytes)}`

  return (
    <div className="view dl-screen">
      <header className="dl-head">
        <h1 className="dl-title">Downloads</h1>
        <div className="dl-head-bar">
          <div className="dl-selector" role="tablist" aria-label="Filter downloads">
            {FILTERS.map((entry) => (
              <button
                key={entry.value}
                type="button"
                role="tab"
                aria-selected={filter === entry.value}
                className={`dl-selector-item ${filter === entry.value ? 'dl-selector-item-active' : ''}`}
                onClick={() => setFilter(entry.value)}
              >
                {`${entry.label} (${counts[entry.value]})`}
              </button>
            ))}
          </div>
          <div className="dl-head-actions">
            {(active > 0 || pausedAll) && (
              <button type="button" className="dl-subtle-btn" onClick={() => void togglePauseAll()}>
                <FluentIcon glyph={pausedAll ? 'play' : 'pause'} size={14} />
                <span>{pausedAll ? 'Resume all' : 'Pause all'}</span>
              </button>
            )}
            <button type="button" className="dl-subtle-btn" onClick={() => void showInExplorer()}>
              <FluentIcon glyph="folder" size={14} />
              <span>Open folder</span>
            </button>
            <MenuAnchor>
              <button
                type="button"
                className="dl-subtle-btn dl-subtle-icon"
                aria-label="More download actions"
                title="More download actions"
                onClick={() => setMenuOpen((open) => !open)}
              >
                <FluentIcon glyph="more" size={14} />
              </button>
              <Menu open={menuOpen} onClose={() => setMenuOpen(false)} minWidth={220}>
                <MenuItem
                  glyph="changeFolder"
                  label="Change download folder…"
                  onClick={() => {
                    setMenuOpen(false)
                    void manageFolder()
                  }}
                />
                <MenuItem
                  glyph="retry"
                  label="Retry all failed"
                  onClick={() => {
                    setMenuOpen(false)
                    retryFailedTransfers()
                  }}
                />
                <MenuSeparator />
                <MenuItem
                  glyph="delete"
                  label="Delete all downloads…"
                  danger
                  disabled={counts.all === 0}
                  onClick={() => {
                    setMenuOpen(false)
                    void deleteAll()
                  }}
                />
              </Menu>
            </MenuAnchor>
          </div>
        </div>
      </header>

      <div className="dl-scroll">
        <div className="dl-body">
          {actionError && (
            <div className="dl-gap-after">
              <InfoBar title="Download action failed" message={actionError} onClose={dismissActionError} />
            </div>
          )}
          {error && !actionError && (
            <div className="dl-gap-after">
              <InfoBar title="Downloads could not be read" message={error} />
            </div>
          )}

          {rows.length > 0 && (
            <div className="dl-card-box dl-gap-after">
              <button
                type="button"
                className="dl-card-header dl-status-header"
                aria-label="Transfer details"
                aria-expanded={statusOpen}
                onClick={() => setStatusOpen((open) => !open)}
              >
                <span className="dl-status-glyph">
                  <FluentIcon glyph="cloudDownload" size={20} />
                </span>
                <span className="dl-status-text">
                  <span className="dl-status-summary ellipsis">{summaryLine(rows)}</span>
                  <span className="dl-caption ellipsis">{statusLine}</span>
                </span>
                <span className="dl-caption">{statusOpen ? 'Hide details' : 'Details'}</span>
                <Chevron open={statusOpen} />
              </button>
              {statusOpen && (
                <div className="dl-status-details dl-enter">
                  <div className="dl-speed">
                    <div className="dl-split">
                      <span className="dl-strong">Speed, last minute</span>
                      <span className="dl-caption">{peakText(throughput)}</span>
                    </div>
                    <ThroughputChart throughput={throughput} />
                    <div className="dl-split">
                      <span className="dl-caption">1 min ago</span>
                      <span className="dl-caption">Now</span>
                    </div>
                  </div>
                  <StorageBlock storage={storage} />
                </div>
              )}
            </div>
          )}

          {attention.length > 0 && (
            <div className="dl-list dl-gap-after">
              {attention.map((row) => (
                <InfoBar key={row.id} title={row.cardTitle} message={row.lead}>
                  {row.requiresNewSource && (
                    <button type="button" className="btn" onClick={() => chooseSource(row.id)}>
                      Choose another source
                    </button>
                  )}
                  {row.canRetry && (
                    <button type="button" className="btn" onClick={() => resumeTransfer(row.id)}>
                      Try again
                    </button>
                  )}
                  <button type="button" className="dl-subtle-btn dl-danger-text" onClick={() => void cancel(row.id)}>
                    Remove
                  </button>
                </InfoBar>
              ))}
            </div>
          )}

          {running.length > 0 && (
            <>
              <h2 className="dl-section-title">Downloading</h2>
              <div className="dl-list">
                {running.map((row) => (
                  <RunningCard
                    key={row.id}
                    row={row}
                    open={expandedId === row.id}
                    onToggle={() => setExpandedId((current) => (current === row.id ? null : row.id))}
                    onChooseSource={() => chooseSource(row.id)}
                    onCancel={() => void cancel(row.id)}
                  />
                ))}
              </div>
            </>
          )}

          {loading && rows.length === 0 && (
            <div className="dl-list">
              {Array.from({ length: 3 }).map((_, index) => (
                <DownloadRowSkeleton key={`skeleton-${index}`} />
              ))}
            </div>
          )}

          {waiting.length > 0 && (
            <div className="dl-card-box dl-waiting">
              <button
                type="button"
                className="dl-card-header dl-waiting-header"
                aria-label="Up next"
                aria-expanded={waitingOpen}
                onClick={() => setWaitingOpen((open) => !open)}
              >
                <FluentIcon glyph="queue" size={16} />
                <span className="dl-waiting-title">Up next</span>
                <span className="dl-caption">{`${waiting.length} waiting`}</span>
                <Chevron open={waitingOpen} />
              </button>
              {waitingOpen && (
                <div className="dl-enter">
                  {waiting.map((row) => (
                    <div key={row.id} className="dl-waiting-row">
                      <RowTitle row={row} />
                      <span className="dl-caption">{row.downloadedLine}</span>
                      <button
                        type="button"
                        className="dl-subtle-btn dl-subtle-icon dl-danger-text"
                        aria-label="Remove from the queue"
                        title="Remove from the queue"
                        onClick={() => void cancel(row.id)}
                      >
                        <FluentIcon glyph="delete" size={14} />
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {shownReady.length > 0 && (
            <section className="dl-ready" aria-label="Ready to watch">
              <h2 className="dl-section-title dl-ready-title">
                Ready to watch
                <span className="dl-ready-headline">{readyHeadline(ready.length, storage.storedBytes)}</span>
              </h2>
              <div className="dl-grid">
                {shownReady.map((row) => {
                  const openDetails = detailsFor(row.id)
                  return (
                    <ReadyCard
                      key={row.id}
                      row={row}
                      onPlay={() => play(row.id)}
                      onMenu={(event) =>
                        openContextMenu(event, [
                          { kind: 'item', label: 'Play offline', glyph: 'play', onSelect: () => play(row.id) },
                          ...(openDetails
                            ? [{ kind: 'item' as const, label: 'View details', glyph: 'info' as const, onSelect: openDetails }]
                            : []),
                          {
                            kind: 'item',
                            label: 'Show in folder',
                            glyph: 'folder',
                            onSelect: () => void showInExplorer(row.id),
                          },
                          { kind: 'separator' },
                          {
                            kind: 'item',
                            label: 'Delete from this PC',
                            glyph: 'delete',
                            danger: true,
                            onSelect: () => void deleteOne(row.id),
                          },
                        ])
                      }
                    />
                  )
                })}
              </div>
            </section>
          )}

          {empty && (
            <div className="dl-empty">
              <span className="dl-empty-tile">
                <FluentIcon glyph="cloudDownload" size={30} />
              </span>
              <div className="dl-empty-copy">
                <h2 className="dl-empty-title">Watch without a connection</h2>
                <p className="dl-empty-body">
                  Save a movie or an episode to this PC and it plays here even when you are offline.
                </p>
              </div>
              <div className="dl-list">
                {EMPTY_STEPS.map((step, index) => (
                  <div key={step} className="dl-card-box dl-empty-step">
                    <span className="dl-step-number">{index + 1}</span>
                    <span>{step}</span>
                  </div>
                ))}
              </div>
              <div className="dl-empty-actions">
                <button type="button" className="btn-accent" onClick={() => setRoot('library')}>
                  Browse your library
                </button>
                <button type="button" className="btn" onClick={() => void manageFolder()}>
                  Choose download folder
                </button>
              </div>
            </div>
          )}

          {noMatches && (
            <div className="dl-card-box dl-nomatch">
              <span className="dl-strong">Nothing in this filter</span>
              <span className="dl-nomatch-body">{noMatchesLine(filter)}</span>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

/** The fold chevron, turned to point up while its section is open. */
function Chevron({ open }: { open: boolean }) {
  return (
    <span className={`dl-chevron ${open ? 'dl-chevron-open' : ''}`}>
      <FluentIcon glyph="chevronDown" size={12} />
    </span>
  )
}

/** The name, then the episode's label in the quieter ink. */
function RowTitle({ row }: { row: DownloadRow }) {
  return (
    <span className="dl-row-title ellipsis">
      <span className="dl-row-name">{row.name}</span>
      {row.episodeLabel && <span className="dl-row-episode">{`  ${row.episodeLabel}`}</span>}
    </span>
  )
}

/**
 * The minute of throughput. Each bar is keyed by its slot, never by its
 * sample, so a new sample changes the heights of bars that stay mounted and
 * every one of them eases to its new height (a CSS transition on height),
 * which is what makes the row flow instead of jump. Memoised on the history
 * array, which changes only when a sample is taken, so progress ticks never
 * touch it.
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
      <span className="dl-strong">Storage</span>
      <div className="dl-storage-track">
        {storage.storedFraction > 0 && (
          <span className="dl-storage-stored" style={{ width: `${storage.storedFraction * 100}%` }} />
        )}
        {flightFraction > 0 && <span className="dl-storage-flight" style={{ width: `${flightFraction * 100}%` }} />}
      </div>
      <div className="dl-legend">
        <div className="dl-legend-item">
          <span className="dl-swatch dl-swatch-stored" />
          <span className="dl-caption">Downloaded</span>
          <span className="dl-caption">{formatDownloadBytes(storage.storedBytes)}</span>
        </div>
        <div className="dl-legend-item">
          <span className="dl-swatch dl-swatch-flight" />
          <span className="dl-caption">Arriving</span>
          <span className="dl-caption">{formatDownloadBytes(storage.inFlightBytes)}</span>
        </div>
        <span className="dl-caption">{`${used.unit} · ${freeLine(storage.freeBytes)}`}</span>
      </div>
    </div>
  )
}

/** WinUI's ProgressBar: a 1px track under a 3px rounded indicator, caution-coloured while paused. */
function ProgressBar({ progress, paused }: { progress: number; paused: boolean }) {
  return (
    <div
      className={`dl-progress ${paused ? 'dl-progress-paused' : ''}`}
      role="progressbar"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(progress * 100)}
    >
      <span className="dl-progress-fill" style={{ width: `${progress * 100}%` }} />
    </div>
  )
}

/** A running transfer: progress and pause always there; the file's facts open on a click. */
function RunningCard({
  row,
  open,
  onToggle,
  onChooseSource,
  onCancel,
}: {
  row: DownloadRow
  open: boolean
  onToggle: () => void
  onChooseSource: () => void
  onCancel: () => void
}) {
  const [menuOpen, setMenuOpen] = useState(false)
  const paused = row.state === 'paused'
  const [lead, ...rest] = row.transferMeta
  const last = rest.length > 0 ? rest[rest.length - 1] : undefined
  const middle = rest.slice(0, -1)
  const facts: Array<{ label: string; value: string; title?: string }> = [
    { label: 'Quality', value: row.qualityLine },
    { label: 'Downloaded', value: row.downloadedLine },
    { label: 'Subtitle', value: row.subs },
    { label: 'Added', value: addedFact(row.addedLabel) },
    // Release names run far longer than the row; the tooltip carries the whole name.
    { label: 'File', value: row.fileName, title: row.fileName },
  ]
  return (
    <div className="dl-card-box">
      <div className="dl-run-head">
        <button
          type="button"
          className="dl-card-header dl-run-header"
          aria-label={row.sub ? `${row.name} · ${row.sub}` : row.name}
          aria-expanded={open}
          title="Details"
          onClick={onToggle}
        >
          <span className="art dl-run-thumb">
            <ArtImage src={row.rowArtwork} />
          </span>
          <span className="dl-run-text">
            <RowTitle row={row} />
            <ProgressBar progress={row.progress} paused={paused} />
            <span className={`dl-caption ellipsis dl-meta-${row.tone}`}>
              {lead}
              {middle.map((part) => `${META_GAP}${part}`).join('')}
              {last !== undefined && <span className="dl-meta-last">{`${META_GAP}${last}`}</span>}
            </span>
          </span>
          <Chevron open={open} />
        </button>
        {row.canPause && (
          <button
            type="button"
            className="btn dl-row-icon-btn"
            aria-label="Pause transfer"
            title="Pause"
            onClick={() => pauseTransfer(row.id)}
          >
            <FluentIcon glyph="pause" size={14} />
          </button>
        )}
        {paused && (
          <button
            type="button"
            className="btn dl-row-icon-btn"
            aria-label="Resume transfer"
            title="Resume"
            onClick={() => resumeTransfer(row.id)}
          >
            <FluentIcon glyph="play" size={14} />
          </button>
        )}
        <MenuAnchor>
          <button
            type="button"
            className="dl-subtle-btn dl-subtle-icon"
            aria-label="More actions"
            title="More actions"
            onClick={() => setMenuOpen((value) => !value)}
          >
            <FluentIcon glyph="more" size={14} />
          </button>
          <Menu open={menuOpen} onClose={() => setMenuOpen(false)} minWidth={200}>
            <MenuItem
              glyph="switchSource"
              label="Choose another source"
              onClick={() => {
                setMenuOpen(false)
                onChooseSource()
              }}
            />
            <MenuSeparator />
            <MenuItem
              glyph="delete"
              label="Cancel download"
              danger
              onClick={() => {
                setMenuOpen(false)
                onCancel()
              }}
            />
          </Menu>
        </MenuAnchor>
      </div>
      {open && (
        <dl className="dl-facts dl-enter">
          {facts.map((fact) => (
            <div key={fact.label} className="dl-fact">
              <dt>{fact.label}</dt>
              <dd className="ellipsis" title={fact.title}>
                {fact.value}
              </dd>
            </div>
          ))}
        </dl>
      )}
    </div>
  )
}

/**
 * A finished download as an artwork card: clicking it plays, and the rest is
 * on right-click. The card and its play button are siblings, not nested,
 * since a button inside a button is invalid HTML; the play button sits in a
 * layer the size of the art, over its corner.
 */
function ReadyCard({
  row,
  onPlay,
  onMenu,
}: {
  row: DownloadRow
  onPlay: () => void
  onMenu: (event: React.MouseEvent<HTMLElement>) => void
}): ReactNode {
  const name = row.cardSub ? `${row.cardTitle} · ${row.cardSub}` : row.cardTitle
  return (
    <div className="dl-card" onContextMenu={onMenu}>
      <button type="button" className="dl-card-hit" aria-label={name} title={name} onClick={onPlay}>
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
        <span className="dl-card-frame" />
        <button
          type="button"
          className="dl-card-play"
          aria-label={`Play ${name} offline`}
          title="Play offline"
          onClick={onPlay}
        >
          <FluentIcon glyph="play" size={13} />
        </button>
      </div>
    </div>
  )
}
