import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { ArtImage } from '../components/ArtImage'
import { showDialog } from '../components/Dialog'
import { FluentIcon } from '../components/FluentIcon'
import { InfoBar } from '../components/InfoBar'
import { Menu, MenuAnchor, MenuItem } from '../components/Menu'
import { QualityBadge } from '../components/QualityBadge'
import { SectionHeader } from '../components/SectionHeader'
import { PathText } from '../components/PathText'
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
} from '../downloads'
import {
  activeCount,
  addedFact,
  aggregateRate,
  chartBars,
  FILTERS,
  filterCounts,
  folderLine,
  folderPath,
  freeLine,
  itemsLabel,
  matchesFilter,
  noMatchesLine,
  peakText,
  percentText,
  queueLine,
  rateText,
  rateTone,
  resolveSelection,
  rowOf,
  storageFigures,
  type DownloadFilter,
  type DownloadRow,
  type RowTone,
} from '../downloadsPresentation'
import { useNav } from '../nav'
import { playDownload, sheetParamsForDownload } from './SourcesSheet'

/**
 * The Downloads page, reproducing the native client's `DownloadsPage.xaml`:
 * the list column owns the header, and the 300px detail panel beside it runs
 * the full height to the window edge. Everything worded or gated here comes
 * from `downloadsPresentation`, the port of the native view model.
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

  // The pane follows a row the filter still shows, or the first one it does,
  // and keeps that as the selection, as the native view model does.
  const selected = resolveSelection(selectedId, shownReady, shownTransfers)
  useEffect(() => {
    if (selected && selected.id !== selectedId) setSelectedId(selected.id)
  }, [selected, selectedId])
  const itemFor = (id: string): DownloadView | undefined =>
    downloads.find((item) => item.job_id === id)

  const rate = aggregateRate(downloads)
  const active = activeCount(downloads)
  const storage = storageFigures(downloads, directory?.free_bytes)
  const bars = chartBars(throughput)

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

  const deleteSelected = async (id: string) => {
    const confirmed = await showDialog({
      title: 'Delete from device?',
      body: 'This permanently removes the video and its subtitle sidecar from this device.',
      primary: 'Delete',
      close: 'Cancel',
    })
    if (confirmed) deleteReady(id)
  }

  const empty = rows.length === 0 && !loading
  const noMatches = rows.length > 0 && shownTransfers.length === 0 && shownReady.length === 0

  return (
    <div className="view dl-screen">
      <div className="dl-main">
        <SectionHeader title="Downloads">
          <div className="dl-head-actions">
            <Segmented
              pills
              options={FILTERS.map((entry) => ({ ...entry, count: counts[entry.value] }))}
              value={filter}
              onChange={setFilter}
            />
            {(active > 0 || pausedAll) && (
              <button type="button" className="btn dl-pause-all" onClick={() => void togglePauseAll()}>
                <FluentIcon glyph={pausedAll ? 'play' : 'pause'} size={13} />
                <span>{pausedAll ? 'Resume all' : 'Pause all'}</span>
              </button>
            )}
            <MenuAnchor>
              <button
                type="button"
                className="icon-btn"
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
                    showInExplorer()
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
        </SectionHeader>

        <div className="dl-scroll">
          {actionError && (
            <InfoBar title="Download action failed" message={actionError} onClose={dismissActionError} />
          )}
          {error && !actionError && <InfoBar title="Downloads could not be read" message={error} />}

          <div className="instrument">
            <div className="inst-block inst-rate">
              <div className={`mono-big rate-${rateTone(rate, pausedAll)}`}>{rateText(rate, pausedAll)}</div>
              <div className="inst-queue">{queueLine(active)}</div>
            </div>
            <div className="inst-block inst-chart">
              <div className="bars">
                {bars.map((bar, index) => (
                  <div
                    key={index}
                    className={`bar ${bar.recent ? 'bar-live' : ''}`}
                    style={{ height: bar.height }}
                  />
                ))}
              </div>
              <div className="inst-chart-foot">
                <span className="mono ellipsis">THROUGHPUT</span>
                <span className="mono">{peakText(throughput)}</span>
              </div>
            </div>
            <div className="inst-block inst-storage">
              <div className="inst-storage-head">
                <span className="ellipsis inst-storage-line">
                  {formatDownloadBytes(storage.storedBytes)} on this device
                </span>
                <span className="mono">{freeLine(storage.freeBytes)}</span>
              </div>
              {/* The caution bar runs to stored plus in flight and the accent
                  bar to stored alone, so yellow only shows as the remainder. */}
              <div className="storage-track">
                <div className="storage-fill-flight" style={{ width: `${storage.usedFraction * 100}%` }} />
                <div className="storage-fill-stored" style={{ width: `${storage.storedFraction * 100}%` }} />
              </div>
              <div className="legend">
                <div className="legend-item">
                  <span className="swatch swatch-stored" />
                  <span>
                    Stored <span className="legend-value">{formatDownloadBytes(storage.storedBytes)}</span>
                  </span>
                </div>
                <div className="legend-item">
                  <span className="swatch swatch-flight" />
                  <span>
                    In flight{' '}
                    <span className="legend-value">{formatDownloadBytes(storage.inFlightBytes)}</span>
                  </span>
                </div>
              </div>
            </div>
          </div>

          {shownTransfers.length > 0 && (
            <>
              <div className="section-rule dl-section-rule">
                <span className="kicker">IN PROGRESS</span>
                <hr />
                <span className="mono">{itemsLabel(shownTransfers.length)}</span>
              </div>
              <div className="dl-list">
                {shownTransfers.map((row) => (
                  <TransferRow
                    key={row.id}
                    row={row}
                    selected={row.id === selected?.id}
                    onSelect={() => setSelectedId(row.id)}
                    onChooseSource={() => chooseSource(row.id)}
                    onCancel={() => void cancel(row.id)}
                  />
                ))}
              </div>
            </>
          )}

          {shownReady.length > 0 && (
            <>
              <div className="section-rule dl-section-rule">
                <span className="kicker">READY TO WATCH</span>
                <hr />
                <span className="mono">{itemsLabel(shownReady.length)}</span>
              </div>
              <div className="dl-list">
                {shownReady.map((row) => (
                  <ReadyRow
                    key={row.id}
                    row={row}
                    selected={row.id === selected?.id}
                    onSelect={() => setSelectedId(row.id)}
                  />
                ))}
              </div>
            </>
          )}

          {loading && rows.length === 0 && (
            <div className="dl-list">
              {Array.from({ length: 4 }).map((_, index) => (
                <DownloadRowSkeleton key={`skeleton-${index}`} />
              ))}
            </div>
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
                <div className="dl-empty-body">
                  Downloads live on this device and play without a connection to your Halo server.
                  Nothing here yet, so there is nothing to watch offline.
                </div>
                <div className="dl-empty-steps">
                  {EMPTY_STEPS.map((step, index) => (
                    <div key={step} className="dl-empty-step">
                      <span className="kicker kicker-accent dl-step-number">{`0${index + 1}`}</span>
                      <span>{step}</span>
                    </div>
                  ))}
                </div>
                <div className="dl-empty-actions">
                  <button type="button" className="btn-accent" onClick={() => setRoot('library')}>
                    Browse library
                  </button>
                  <button type="button" className="btn" onClick={() => void manageFolder()}>
                    Manage download folder
                  </button>
                </div>
                <div className="mono dl-empty-folder">
                  <PathText text={folderLine(directory?.path, directory?.free_bytes)} />
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

      <aside className="dl-pane">
        {selected ? (
          <DetailPane
            row={selected}
            onPlay={() => {
              const item = itemFor(selected.id)
              if (item) playDownload(push, item)
            }}
            onPause={() => pauseTransfer(selected.id)}
            onResume={() => resumeTransfer(selected.id)}
            onCancel={() => void cancel(selected.id)}
            onChooseSource={() => chooseSource(selected.id)}
            onDelete={() => void deleteSelected(selected.id)}
          />
        ) : (
          <div className="dl-folder-card">
            <div className="kicker">DOWNLOAD FOLDER</div>
            <div className="dl-folder-path">
              <PathText text={folderPath(directory?.path)} />
            </div>
            <button type="button" className="btn btn-block" onClick={() => void manageFolder()}>
              Manage folder
            </button>
          </div>
        )}
      </aside>
    </div>
  )
}

/** The row's pill is framed; the pane's copy sits on the tint alone. */
function StatePill({ state, framed }: { state: DownloadRow['state']; framed: boolean }) {
  return (
    <span className={`state-pill state-pill-${state} ${framed ? '' : 'state-pill-flat'}`}>
      {STATE_PILLS[state]}
    </span>
  )
}

function ProgressTrack({ progress, tone }: { progress: number; tone: RowTone }) {
  return (
    <div className="dl-bar">
      <div className={`dl-bar-fill tone-fill-${tone}`} style={{ width: `${progress * 100}%` }} />
    </div>
  )
}

/** Tag, name, and the dimmer sub-line that trims first. */
function RowTitle({ row, pill }: { row: DownloadRow; pill: boolean }) {
  return (
    <div className={`dl-title-line ${pill ? 'dl-title-line-pill' : ''}`}>
      <span className="dl-tag">{row.tag}</span>
      <span className="dl-name">{row.name}</span>
      <span className="dl-sub ellipsis">{row.sub}</span>
      {pill && <StatePill state={row.state} framed />}
    </div>
  )
}

/** Size, quality badge, subtitle chip, and the day the download was added. */
function RowFacts({ row }: { row: DownloadRow }) {
  return (
    <>
      <span className="mono">{row.downloadedLine}</span>
      <span className="dot-sep">·</span>
      <QualityBadge tier={row.qualityTier} detail={row.qualityDetail || undefined} gold={row.gold} />
      <span className="dot-sep">·</span>
      <span className={`subs-chip ${row.subsMuted ? 'subs-chip-off' : ''}`}>{row.subsChip}</span>
      <span className="mono dl-added">{row.addedLabel}</span>
    </>
  )
}

function Thumb({ row, progress }: { row: DownloadRow; progress?: number }) {
  return (
    <div className="art dl-thumb">
      <ArtImage src={row.rowArtwork} />
      {progress !== undefined && (
        <div className="art-progress art-progress-bare">
          <div style={{ width: `${progress * 100}%` }} />
        </div>
      )}
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
  onSelect,
  children,
}: {
  selected: boolean
  onSelect: () => void
  children: ReactNode
}) {
  return (
    <div
      className={`dl-row ${selected ? 'dl-row-sel' : ''}`}
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
  onClick,
}: {
  glyph: 'pause' | 'play' | 'retry' | 'close'
  label: string
  onClick: () => void
}) {
  return (
    <button
      type="button"
      className="icon-btn icon-btn-30"
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

function TransferRow({
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
  return (
    <RowFrame selected={selected} onSelect={onSelect}>
      <Thumb row={row} progress={row.progress} />
      <div className="dl-row-body dl-row-body-transfer">
        <RowTitle row={row} pill />
        <ProgressTrack progress={row.progress} tone={row.tone} />
        <div className="dl-meta">
          <span className={`mono tone-text-${row.tone}`}>{row.lead}</span>
          <span className="dot-sep">·</span>
          <RowFacts row={row} />
        </div>
        {row.requiresNewSource && (
          <button
            type="button"
            className="btn h30 dl-choose-source"
            onClick={(event) => {
              event.stopPropagation()
              onChooseSource()
            }}
          >
            <FluentIcon glyph="switchSource" size={14} />
            <span>Choose source again</span>
          </button>
        )}
      </div>
      <div className="dl-row-actions">
        {row.canPause && (
          <RowIconButton glyph="pause" label="Pause transfer" onClick={() => pauseTransfer(row.id)} />
        )}
        {row.canResume && (
          <RowIconButton glyph="play" label="Resume transfer" onClick={() => resumeTransfer(row.id)} />
        )}
        {row.canRetry && (
          <RowIconButton glyph="retry" label="Retry this source" onClick={() => resumeTransfer(row.id)} />
        )}
        <RowIconButton glyph="close" label="Cancel transfer" onClick={onCancel} />
      </div>
    </RowFrame>
  )
}

function ReadyRow({
  row,
  selected,
  onSelect,
}: {
  row: DownloadRow
  selected: boolean
  onSelect: () => void
}) {
  return (
    <RowFrame selected={selected} onSelect={onSelect}>
      <Thumb row={row} />
      <div className="dl-row-body dl-row-body-ready">
        <RowTitle row={row} pill={false} />
        <div className="dl-meta">
          <RowFacts row={row} />
        </div>
      </div>
      <div className="dl-ready-actions">
        <StatePill state="ondisk" framed />
        <button
          type="button"
          className="btn h28"
          aria-label="Open download folder"
          title="Show this file in Explorer"
          onClick={(event) => {
            event.stopPropagation()
            showInExplorer(row.id)
          }}
        >
          <FluentIcon glyph="folder" size={13} />
          <span>Open folder</span>
        </button>
      </div>
    </RowFrame>
  )
}

/** The 300px panel: everything about one download, including what is too long for a row. */
function DetailPane({
  row,
  onPlay,
  onPause,
  onResume,
  onCancel,
  onChooseSource,
  onDelete,
}: {
  row: DownloadRow
  onPlay: () => void
  onPause: () => void
  onResume: () => void
  onCancel: () => void
  onChooseSource: () => void
  onDelete: () => void
}) {
  const ready = row.state === 'ondisk'
  const facts = [
    { label: 'Quality', value: row.qualityLine },
    { label: ready ? 'File size' : 'Downloaded', value: row.downloadedLine },
    { label: 'Subtitle', value: row.subs },
    { label: 'Added', value: addedFact(row.addedLabel) },
  ]
  return (
    <div className="dl-pane-inner">
      {/* 2:3, the aspect posters are drawn at. */}
      <div className="art pane-poster">
        <ArtImage src={row.poster} />
        <div className="art-progress art-progress-pane">
          <div style={{ width: `${row.progress * 100}%` }} />
        </div>
      </div>
      <div className="dl-pane-title">
        <span className="dl-pane-name">{row.name}</span>
        {!ready && <StatePill state={row.state} framed={false} />}
      </div>
      {/* Release names run far longer than the pane is wide; the tooltip carries the whole name. */}
      <div className="mono ellipsis dl-pane-file" title={row.fileName}>
        {row.fileName}
      </div>

      {!ready && (
        <div className="dl-pane-transfer">
          <ProgressTrack progress={row.progress} tone={row.tone} />
          <div className="dl-pane-lead">
            <span className={`mono spacer ellipsis tone-text-${row.tone}`}>{row.lead}</span>
            <span className="mono">{percentText(row.progress)}</span>
          </div>
          <div className="dl-pane-controls">
            {row.state === 'downloading' && (
              <button type="button" className="btn h34" onClick={onPause}>
                Pause transfer
              </button>
            )}
            {row.canResume && (
              <button type="button" className="btn h34" onClick={onResume}>
                Resume transfer
              </button>
            )}
            <button
              type="button"
              className="icon-btn icon-btn-34 dl-pane-cancel"
              aria-label="Cancel transfer"
              title="Cancel transfer"
              onClick={onCancel}
            >
              <FluentIcon glyph="close" size={13} />
            </button>
          </div>
          {row.requiresNewSource && (
            <button type="button" className="btn h34 btn-block" onClick={onChooseSource}>
              Choose source again
            </button>
          )}
        </div>
      )}

      {ready && (
        <button type="button" className="btn-accent h34 btn-block" onClick={onPlay}>
          <FluentIcon glyph="play" size={14} />
          <span>Play offline</span>
        </button>
      )}

      <div className="facts">
        {facts.map((fact) => (
          <div key={fact.label} className="fact-row">
            <span className="fact-label">{fact.label}</span>
            <span className="fact-value">{fact.value}</span>
          </div>
        ))}
      </div>

      {ready && (
        <button type="button" className="btn-danger btn-danger-fill h34 btn-block" onClick={onDelete}>
          <FluentIcon glyph="delete" size={15} />
          <span>Delete from device</span>
        </button>
      )}

      {/* The failure sentence is already on the lead line; this says what
          happens to the bytes on disk, which is the part a viewer acts on. */}
      {row.state === 'failed' && (
        <div className="mono dl-pane-note">THE PARTIAL FILE IS KEPT UNTIL A NEW SOURCE FINISHES.</div>
      )}
    </div>
  )
}
