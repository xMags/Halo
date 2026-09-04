import { useEffect, useMemo, useRef, useState } from 'react'
import { ArtImage } from '../components/ArtImage'
import { Icon } from '../components/Icon'
import { Menu, MenuAnchor, MenuItem } from '../components/Menu'
import { SectionHeader } from '../components/SectionHeader'
import { Segmented } from '../components/Segmented'
import {
  chooseDownloadDirectory,
  downloadEtaSeconds,
  downloadFailureMessage,
  downloadProgress,
  formatDownloadBytes,
  formatDownloadEta,
  getDirectoryInfo,
  openDownloadFolder,
  pauseDownload,
  removeDownload,
  resumeDownload,
  setDownloadDirectory,
  type DirectoryInfo,
  type DownloadView,
  useDownloads,
} from '../downloads'
import { formatRelative } from '../format'
import { useNav } from '../nav'
import { parseStreamInfo, qualityTier } from '../streamInfo'
import { sheetParamsForDownload } from './SourcesSheet'

/** Throughput chart: 24 bars, one per sample, newest on the right. */
const BAR_COUNT = 24
const SAMPLE_MS = 1_000
/** How many bars read as "now" rather than history. */
const LIVE_BARS = 6

const FILTERS = [
  { value: 'all', label: 'All' },
  { value: 'active', label: 'Active' },
  { value: 'ready', label: 'Ready' },
  { value: 'failed', label: 'Failed' },
] as const
type Filter = (typeof FILTERS)[number]['value']

function matches(item: DownloadView, filter: Filter): boolean {
  if (filter === 'all') return true
  if (filter === 'ready') return item.status === 'done'
  if (filter === 'failed') return item.status === 'failed'
  return item.status === 'downloading' || item.status === 'queued' || item.status === 'paused'
}

/** A transfer can be told to carry on; an expired or unresumable one cannot. */
function canResume(item: DownloadView): boolean {
  if (item.status === 'paused') return true
  return (
    item.status === 'failed' &&
    item.failure !== 'source_expired' &&
    item.failure !== 'invalid_range' &&
    item.failure !== 'protected_request_corrupt'
  )
}

function needsNewSource(item: DownloadView): boolean {
  return (
    item.status === 'failed' &&
    (item.failure === 'source_expired' ||
      item.failure === 'invalid_range' ||
      item.failure === 'protected_request_corrupt')
  )
}

interface Pill {
  label: string
  className: string
  /** Tone shared by the progress bar and the lead line, per the design. */
  color: string
}

function pillFor(item: DownloadView): Pill {
  switch (item.status) {
    case 'downloading':
      return { label: 'DOWNLOADING', className: 'state-pill-downloading', color: 'var(--ac)' }
    case 'queued':
      return { label: 'QUEUED', className: '', color: 'var(--ac)' }
    case 'paused':
      return { label: 'PAUSED', className: 'state-pill-paused', color: 'var(--ca)' }
    case 'failed':
      return { label: 'FAILED', className: 'state-pill-failed', color: 'var(--cr)' }
    case 'done':
      return { label: 'ON DISK', className: '', color: 'var(--ac)' }
  }
}

/** The mono lead line: what this transfer is doing right now. */
function leadFor(item: DownloadView): string {
  switch (item.status) {
    case 'downloading': {
      const eta = downloadEtaSeconds(item)
      const rate = `${(item.bytes_per_second / 1024 ** 2).toFixed(1)} MB/S`
      return eta != null ? `${rate} · ${formatDownloadEta(eta).toUpperCase()}` : rate
    }
    case 'queued':
      return 'WAITING FOR A SLOT'
    case 'paused':
      return 'PAUSED BY YOU'
    case 'failed':
      return (downloadFailureMessage(item.failure) ?? 'THE TRANSFER FAILED').toUpperCase()
    case 'done':
      return 'COMPLETE'
  }
}

/** Quality facts, parsed from the addon text the download was started from. */
function qualityOf(item: DownloadView): { tier: string; detail: string } {
  const info = parseStreamInfo({ name: item.media.stream_name, title: item.media.stream_title })
  const detail = [info.quality, info.dynamicRange ?? 'SDR', info.codec].filter(Boolean).join(' · ')
  return { tier: qualityTier(info.quality), detail: detail.toUpperCase() }
}

function subsLabel(item: DownloadView): string {
  return item.subtitle_lang ? `${item.subtitle_lang.toUpperCase()} SUBS` : 'NO SUBS'
}

/**
 * Samples the combined transfer rate into a fixed-width ring so the instrument
 * card can draw real history instead of a decorative sparkline. The peak is
 * sticky for the session: it answers "what has this line managed?", which a
 * peak that decayed with the window would not.
 */
function useThroughput(rate: number): { bars: number[]; peak: number } {
  const [bars, setBars] = useState<number[]>(() => new Array<number>(BAR_COUNT).fill(0))
  const rateRef = useRef(rate)
  const peakRef = useRef(0)
  rateRef.current = rate
  if (rate > peakRef.current) peakRef.current = rate

  useEffect(() => {
    const timer = window.setInterval(() => {
      setBars((previous) => [...previous.slice(1), rateRef.current])
    }, SAMPLE_MS)
    return () => window.clearInterval(timer)
  }, [])

  return { bars, peak: peakRef.current }
}

export function Downloads() {
  const { push, openSheet } = useNav()
  const { downloads, loading, error, refresh } = useDownloads()
  const [filter, setFilter] = useState<Filter>('all')
  const [selected, setSelected] = useState<string | null>(null)
  const [menuOpen, setMenuOpen] = useState(false)
  const [directory, setDirectory] = useState<DirectoryInfo | null>(null)
  const [directoryError, setDirectoryError] = useState<string | null>(null)

  useEffect(() => {
    void getDirectoryInfo()
      .then(setDirectory)
      .catch((value) => setDirectoryError(String(value)))
  }, [downloads.length])

  const shown = downloads.filter((item) => matches(item, filter))
  const transfers = shown.filter((item) => item.status !== 'done')
  const ready = shown.filter((item) => item.status === 'done')

  // The selection drives the detail pane; keep it on a row that still exists.
  const selectedItem = downloads.find((item) => item.job_id === selected) ?? downloads[0] ?? null
  useEffect(() => {
    if (selectedItem && selectedItem.job_id !== selected) setSelected(selectedItem.job_id)
  }, [selectedItem, selected])

  const running = downloads.filter((item) => item.status === 'downloading')
  const rate = running.reduce((total, item) => total + item.bytes_per_second, 0)
  const { bars, peak } = useThroughput(rate)
  const anyLive = running.length > 0 || downloads.some((item) => item.status === 'queued')
  /** Transfers still owed bytes — an all-complete list is idle, not held. */
  const anyTransfer = downloads.some((item) => item.status !== 'done')
  const heldBack = !anyLive && anyTransfer

  const storedBytes = downloads
    .filter((item) => item.status === 'done')
    .reduce((total, item) => total + Math.max(item.total_bytes, item.downloaded_bytes), 0)
  const inFlightBytes = downloads
    .filter((item) => item.status !== 'done')
    .reduce((total, item) => total + item.downloaded_bytes, 0)
  // Capacity is what we can actually see: the free space plus what Halo holds.
  const capacity = (directory?.free_bytes ?? 0) + storedBytes + inFlightBytes
  const pct = (value: number) => (capacity > 0 ? `${((value / capacity) * 100).toFixed(1)}%` : '0%')

  const play = (item: DownloadView) => {
    if (item.status !== 'done') return
    push({
      name: 'player',
      sourceKind: 'download',
      downloadId: item.job_id,
      ...(item.subtitle_lang ? { subtitleLang: item.subtitle_lang } : {}),
      url: '',
      videoId: item.media.video_id,
      itemId: item.media.item_id,
      type: item.media.media_type,
      title: item.media.title,
      ...(item.media.show_name ? { showName: item.media.show_name } : {}),
      ...(item.media.episode_label ? { episodeLabel: item.media.episode_label } : {}),
      ...(item.media.poster ? { poster: item.media.poster } : {}),
      ...(item.media.meta_id ? { metaId: item.media.meta_id } : {}),
    })
  }

  const remove = async (item: DownloadView, confirmFirst: boolean) => {
    if (confirmFirst && !window.confirm(`Delete “${item.media.title}” from this device?`)) return
    await removeDownload(item.job_id).catch(() => undefined)
    await refresh()
  }

  const changeDirectory = async () => {
    const chosen = await chooseDownloadDirectory().catch((value) => {
      setDirectoryError(String(value))
      return null
    })
    if (!chosen) return
    try {
      const path = await setDownloadDirectory(chosen)
      setDirectory({ path, exists: true })
      setDirectoryError(null)
    } catch (value) {
      setDirectoryError(value instanceof Error ? value.message : String(value))
    }
  }

  /**
   * There is no server-side "pause everything" switch, so the button reads the
   * fleet and acts on each transfer. Its label follows the same read, which is
   * why it can never disagree with what the rows show.
   */
  const toggleAll = async () => {
    if (anyLive) {
      await Promise.all(
        downloads
          .filter((item) => item.status === 'downloading' || item.status === 'queued')
          .map((item) => pauseDownload(item.job_id).catch(() => undefined)),
      )
    } else {
      await Promise.all(
        downloads
          .filter((item) => item.status === 'paused')
          .map((item) => resumeDownload(item.job_id).catch(() => undefined)),
      )
    }
    await refresh()
  }

  const retryAllFailed = async () => {
    await Promise.all(
      downloads
        .filter((item) => item.status === 'failed' && canResume(item))
        .map((item) => resumeDownload(item.job_id).catch(() => undefined)),
    )
    await refresh()
  }

  const counts = useMemo(
    () =>
      Object.fromEntries(
        FILTERS.map((entry) => [entry.value, downloads.filter((item) => matches(item, entry.value)).length]),
      ) as Record<Filter, number>,
    [downloads],
  )

  return (
    <div className="view dl-screen">
      <div className="dl-main">
        <SectionHeader title="Downloads" wrap>
          <Segmented
            options={FILTERS.map((entry) => ({ ...entry, count: counts[entry.value] }))}
            value={filter}
            onChange={setFilter}
          />
          <button
            type="button"
            className="btn"
            disabled={!anyTransfer}
            onClick={() => void toggleAll()}
          >
            <Icon name={anyLive ? 'pause' : 'play'} size={13} />
            <span>{anyLive ? 'Pause all' : 'Resume all'}</span>
          </button>
          <MenuAnchor>
            <button
              type="button"
              className="icon-btn"
              title="More download actions"
              onClick={() => setMenuOpen((open) => !open)}
            >
              <Icon name="more" size={13} />
            </button>
            <Menu open={menuOpen} onClose={() => setMenuOpen(false)} minWidth={236}>
              <MenuItem
                label="Manage download folder"
                onClick={() => {
                  setMenuOpen(false)
                  void changeDirectory()
                }}
              />
              <MenuItem
                label="Open folder in Explorer"
                onClick={() => {
                  setMenuOpen(false)
                  void openDownloadFolder().catch((value) => setDirectoryError(String(value)))
                }}
              />
              <MenuItem
                label="Retry all failed transfers"
                onClick={() => {
                  setMenuOpen(false)
                  void retryAllFailed()
                }}
              />
            </Menu>
          </MenuAnchor>
        </SectionHeader>

        <div className="dl-scroll">
          {(error || directoryError) && (
            <div className="state-note error-text" style={{ padding: 0 }}>
              {error ?? directoryError}
            </div>
          )}
          {loading && (
            <div className="state-note" style={{ padding: 0 }}>
              <span className="spinner" /> Loading downloads…
            </div>
          )}
          {!loading && directory && !directory.exists && (
            <div className="state-note error-text" style={{ padding: 0 }}>
              The download folder is unavailable. Choose another folder to continue.
            </div>
          )}

          <div className="instrument">
            <div className="inst-block inst-rate">
              <div
                className="mono-big"
                style={{ color: anyLive ? 'var(--t1)' : heldBack ? 'var(--ca)' : 'var(--t3)' }}
              >
                {(rate / 1024 ** 2).toFixed(1)} MB/S
              </div>
              <div style={{ fontSize: 14, color: 'var(--t3)' }}>
                {running.length} downloading ·{' '}
                {downloads.filter((item) => item.status === 'queued').length} queued ·{' '}
                {downloads.filter((item) => item.status === 'paused').length} paused
              </div>
            </div>

            <div className="inst-block inst-chart">
              <div className="bars">
                {bars.map((value, index) => (
                  <div
                    key={index}
                    className={`bar ${index >= BAR_COUNT - LIVE_BARS && anyLive ? 'bar-live' : ''}`}
                    style={{
                      height: `${peak > 0 ? Math.max(3, Math.round((value / peak) * 38)) : 3}px`,
                    }}
                  />
                ))}
              </div>
              <div
                className="mono"
                style={{ display: 'flex', justifyContent: 'space-between', color: 'var(--t4)' }}
              >
                <span>THROUGHPUT</span>
                <span>PEAK {(peak / 1024 ** 2).toFixed(1)} MB/S</span>
              </div>
            </div>

            <div className="inst-block inst-storage">
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <span
                  className="spacer ellipsis"
                  style={{ fontSize: 14, color: 'var(--t2)' }}
                  title={directory?.path}
                >
                  {directory ? `Download folder at ${directory.path}` : 'Download folder'}
                </span>
                <span className="mono">
                  {directory?.free_bytes != null
                    ? `${formatDownloadBytes(directory.free_bytes)} FREE`.toUpperCase()
                    : ''}
                </span>
              </div>
              <div className="storage-track">
                <div style={{ background: 'var(--ca)', width: pct(storedBytes + inFlightBytes) }} />
                <div style={{ background: 'var(--aa)', width: pct(storedBytes) }} />
              </div>
              <div className="legend">
                <div className="legend-item">
                  <span className="swatch" style={{ background: 'var(--ac)' }} />
                  <span>
                    Stored <span className="mono">{formatDownloadBytes(storedBytes)}</span>
                  </span>
                </div>
                <div className="legend-item">
                  <span className="swatch" style={{ background: 'var(--ca)' }} />
                  <span>
                    In flight <span className="mono">{formatDownloadBytes(inFlightBytes)}</span>
                  </span>
                </div>
              </div>
            </div>
          </div>

          {transfers.length > 0 && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 4 }}>
              <div className="section-rule">
                <span className="kicker">IN PROGRESS</span>
                <hr />
                <span className="mono">
                  {transfers.length} TRANSFER{transfers.length === 1 ? '' : 'S'}
                </span>
              </div>
              {transfers.map((item) => (
                <TransferRow
                  key={item.job_id}
                  item={item}
                  selected={item.job_id === selectedItem?.job_id}
                  onSelect={() => setSelected(item.job_id)}
                  onChooseSource={() => openSheet(sheetParamsForDownload(item))}
                  onRemove={() => void remove(item, false)}
                />
              ))}
            </div>
          )}

          {ready.length > 0 && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 4 }}>
              <div className="section-rule">
                <span className="kicker">READY TO WATCH</span>
                <hr />
                <span className="mono">
                  {ready.length} TITLE{ready.length === 1 ? '' : 'S'}
                </span>
              </div>
              {ready.map((item) => (
                <ReadyRow
                  key={item.job_id}
                  item={item}
                  selected={item.job_id === selectedItem?.job_id}
                  onSelect={() => setSelected(item.job_id)}
                />
              ))}
            </div>
          )}

          {!loading && shown.length === 0 && (
            <div className="dl-empty">
              <div style={{ fontSize: 18, fontWeight: 600 }}>
                {downloads.length === 0 ? 'Nothing saved yet' : 'Nothing in this filter'}
              </div>
              <div style={{ fontSize: 15, color: 'var(--t3)' }}>
                {downloads.length === 0
                  ? 'Choose a source on any title and use “Save for offline”.'
                  : `No transfers match ${filter}.`}
              </div>
            </div>
          )}
        </div>
      </div>

      <div className="dl-pane">
        {selectedItem && (
          <DetailPane
            item={selectedItem}
            onPlay={() => play(selectedItem)}
            onRemove={() => void remove(selectedItem, true)}
            onCancel={() => void remove(selectedItem, false)}
            onChooseSource={() => openSheet(sheetParamsForDownload(selectedItem))}
          />
        )}
      </div>
    </div>
  )
}

/** The shared left half of both row kinds: thumb, title line, meta line. */
function RowIdentity({ item }: { item: DownloadView }) {
  const quality = qualityOf(item)
  const done = item.status === 'done'
  return (
    <div className="dl-meta">
      <span className="mono">
        {formatDownloadBytes(done ? Math.max(item.total_bytes, item.downloaded_bytes) : item.downloaded_bytes)}
      </span>
      {!done && item.total_bytes > 0 && (
        <>
          <span className="dot-sep">·</span>
          <span className="mono">OF {formatDownloadBytes(item.total_bytes)}</span>
        </>
      )}
      <span className="dot-sep">·</span>
      <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <span className={`tier-chip ${quality.tier === '4K' ? 'tier-chip-hi' : ''}`}>
          {quality.tier}
        </span>
        {quality.detail && <span className="mono">{quality.detail}</span>}
      </span>
      <span className="dot-sep">·</span>
      <span className={`subs-chip ${item.subtitle_lang ? '' : 'subs-chip-off'}`}>
        {subsLabel(item)}
      </span>
      <span className="mono" style={{ color: 'var(--t4)' }}>
        {formatRelative(item.created_at).toUpperCase()}
      </span>
    </div>
  )
}

/**
 * One in-flight transfer. The row is a div with the button role rather than a
 * `<button>`: the design nests the pause/cancel controls inside the row, and a
 * button inside a button is invalid HTML — so the row carries the keyboard
 * behaviour itself.
 */
function TransferRow({
  item,
  selected,
  onSelect,
  onChooseSource,
  onRemove,
}: {
  item: DownloadView
  selected: boolean
  onSelect: () => void
  onChooseSource: () => void
  onRemove: () => void
}) {
  const pill = pillFor(item)
  const progress = downloadProgress(item) ?? 0

  return (
    <div
      className={`dl-row ${selected ? 'dl-row-sel' : ''}`}
      role="button"
      tabIndex={0}
      aria-pressed={selected}
      onClick={onSelect}
      onKeyDown={(event) => {
        if (event.key !== 'Enter' && event.key !== ' ') return
        event.preventDefault()
        onSelect()
      }}
    >
      <div className="art dl-thumb">
        <ArtImage src={item.media.poster} />
        <div className="art-progress">
          <div style={{ width: `${Math.round(progress * 100)}%` }} />
        </div>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 7, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 9, flexWrap: 'wrap', minWidth: 0 }}>
          {item.media.episode_label && (
            <span className="mono mono-b" style={{ color: 'var(--t3)' }}>
              {item.media.episode_label}
            </span>
          )}
          <span className="dl-name">{item.media.show_name ?? item.media.title}</span>
          <span className="dl-sub ellipsis">{item.media.show_name ? item.media.title : ''}</span>
          <span className={`state-pill ${pill.className}`}>{pill.label}</span>
        </div>

        <div className="dl-bar">
          <div style={{ width: `${Math.round(progress * 100)}%`, background: pill.color }} />
        </div>

        <div className="dl-meta">
          <span className="mono" style={{ color: pill.color }}>
            {leadFor(item)}
          </span>
        </div>
        <RowIdentity item={item} />

        {needsNewSource(item) && (
          <button
            type="button"
            className="btn h30"
            style={{ alignSelf: 'flex-start', marginTop: 2 }}
            onClick={(event) => {
              event.stopPropagation()
              onChooseSource()
            }}
          >
            <Icon name="list" size={14} />
            <span>Choose source again</span>
          </button>
        )}
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        {(item.status === 'downloading' || item.status === 'queued') && (
          <button
            type="button"
            className="icon-btn icon-btn-30"
            title="Pause transfer"
            onClick={(event) => {
              event.stopPropagation()
              void pauseDownload(item.job_id)
            }}
          >
            <Icon name="pause" size={13} />
          </button>
        )}
        {canResume(item) && (
          <button
            type="button"
            className="icon-btn icon-btn-30"
            title={item.status === 'failed' ? 'Retry this source' : 'Resume transfer'}
            onClick={(event) => {
              event.stopPropagation()
              void resumeDownload(item.job_id)
            }}
          >
            <Icon name={item.status === 'failed' ? 'retry' : 'play'} size={13} />
          </button>
        )}
        <button
          type="button"
          className="icon-btn icon-btn-30"
          title="Cancel transfer"
          onClick={(event) => {
            event.stopPropagation()
            onRemove()
          }}
        >
          <Icon name="x" size={12} />
        </button>
      </div>
    </div>
  )
}

function ReadyRow({
  item,
  selected,
  onSelect,
}: {
  item: DownloadView
  selected: boolean
  onSelect: () => void
}) {
  return (
    <div
      className={`dl-row ${selected ? 'dl-row-sel' : ''}`}
      role="button"
      tabIndex={0}
      aria-pressed={selected}
      onClick={onSelect}
      onKeyDown={(event) => {
        if (event.key !== 'Enter' && event.key !== ' ') return
        event.preventDefault()
        onSelect()
      }}
    >
      <div className="art dl-thumb">
        <ArtImage src={item.media.poster} />
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 9, flexWrap: 'wrap', minWidth: 0 }}>
          {item.media.episode_label && (
            <span className="mono mono-b" style={{ color: 'var(--t3)' }}>
              {item.media.episode_label}
            </span>
          )}
          <span className="dl-name">{item.media.show_name ?? item.media.title}</span>
          <span className="dl-sub ellipsis">{item.media.show_name ? item.media.title : ''}</span>
        </div>
        <RowIdentity item={item} />
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 8 }}>
        <span className="state-pill">ON DISK</span>
        <button
          type="button"
          className="btn h28"
          title="Show this file in Explorer"
          onClick={(event) => {
            event.stopPropagation()
            void openDownloadFolder(item.job_id).catch(() => undefined)
          }}
        >
          <Icon name="folder" size={13} />
          <span>Open folder</span>
        </button>
      </div>
    </div>
  )
}

/**
 * The 300px pane: everything about one transfer, including the facts that are
 * too long for a row. Hidden below 1200px of content width (`--dpd`), where
 * the rows already fill the column.
 */
function DetailPane({
  item,
  onPlay,
  onRemove,
  onCancel,
  onChooseSource,
}: {
  item: DownloadView
  onPlay: () => void
  onRemove: () => void
  onCancel: () => void
  onChooseSource: () => void
}) {
  const pill = pillFor(item)
  const quality = qualityOf(item)
  const progress = downloadProgress(item) ?? 0
  const isTransfer = item.status !== 'done'
  const paused = item.status === 'paused'

  return (
    <div className="dl-pane-inner">
      <div className="art pane-poster">
        <ArtImage src={item.media.poster} />
        {isTransfer && (
          <div className="art-progress">
            <div style={{ width: `${Math.round(progress * 100)}%` }} />
          </div>
        )}
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        <span style={{ fontSize: 18, fontWeight: 600 }}>{item.media.title}</span>
        <span className={`state-pill ${pill.className}`}>{pill.label}</span>
      </div>
      <div className="mono ellipsis" title={item.file_name}>
        {item.file_name}
      </div>

      {isTransfer && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <div className="dl-bar">
            <div style={{ width: `${Math.round(progress * 100)}%`, background: pill.color }} />
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span className="mono spacer ellipsis" style={{ color: pill.color }}>
              {leadFor(item)}
            </span>
            <span className="mono">{Math.round(progress * 100)}%</span>
          </div>
          <div style={{ display: 'flex', gap: 6 }}>
            <button
              type="button"
              className="btn h34 spacer"
              onClick={() =>
                void (paused ? resumeDownload(item.job_id) : pauseDownload(item.job_id))
              }
            >
              {paused ? 'Resume transfer' : 'Pause transfer'}
            </button>
            <button
              type="button"
              className="icon-btn icon-btn-34"
              title="Cancel transfer"
              onClick={onCancel}
            >
              <Icon name="x" size={13} />
            </button>
          </div>
          {needsNewSource(item) && (
            <button type="button" className="btn h34 btn-block" onClick={onChooseSource}>
              Choose source again
            </button>
          )}
        </div>
      )}

      {!isTransfer && (
        <button type="button" className="btn-accent h34" onClick={onPlay}>
          <Icon name="play" size={14} />
          <span>{item.media.episode_label ? `Play ${item.media.episode_label}` : 'Play'}</span>
        </button>
      )}

      <div className="facts">
        {[
          { label: 'Quality', value: quality.detail || quality.tier },
          {
            label: isTransfer ? 'Total size' : 'Size on disk',
            value: formatDownloadBytes(
              isTransfer ? item.total_bytes : Math.max(item.total_bytes, item.downloaded_bytes),
            ),
          },
          { label: 'Subtitle', value: subsLabel(item) },
          { label: 'Added', value: formatRelative(item.created_at).toUpperCase() },
        ].map((fact) => (
          <div key={fact.label} className="fact-row">
            <span className="fact-label">{fact.label}</span>
            <span className="fact-value">{fact.value}</span>
          </div>
        ))}
      </div>

      {!isTransfer && (
        <button type="button" className="btn-danger btn-danger-fill h34" onClick={onRemove}>
          <Icon name="trash" size={15} />
          <span>Delete from device</span>
        </button>
      )}

      <div className="mono" style={{ color: 'var(--t4)', lineHeight: 1.5 }}>
        {isTransfer
          ? 'TRANSFERS RESUME AUTOMATICALLY WHEN HALO REOPENS.'
          : 'PLAYS OFFLINE FROM THIS DEVICE.'}
      </div>
    </div>
  )
}
