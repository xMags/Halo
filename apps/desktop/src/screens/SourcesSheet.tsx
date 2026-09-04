import { computeVideoHash, languageLabel, languageMatches, type Stream } from '@halo/core'
import { fetch as nativeFetch } from '@tauri-apps/plugin-http'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { getClient } from '../api'
import { Icon } from '../components/Icon'
import { Menu, MenuAnchor, MenuItem } from '../components/Menu'
import {
  attachDownloadSubtitle,
  startDownload,
  useDownloads,
  type DownloadMedia,
  type DownloadSubtitleRequest,
  type DownloadView,
} from '../downloads'
import { formatBytes, formatClock } from '../format'
import { useNav, type StreamsParams } from '../nav'
import { useStreams, useWatchStates } from '../queries'
import { useSettings } from '../settings'
import { setSettingsSection } from '../settingsSection'
import {
  compareStreams,
  parseStreamInfo,
  qualityRank,
  qualityResolution,
  qualityTier,
  type Quality,
  type StreamInfo,
} from '../streamInfo'

/**
 * The source picker, as a sheet over whatever opened it.
 *
 * The server has already filtered to playable direct URLs; everything here is
 * about helping the user choose between them, so every row is parsed for
 * quality, codec, size and cached state, and the groups are ordered by how
 * soon the file will actually start.
 *
 * Cached state maps onto the design's three groups like this — addons report
 * only "cached", "not cached", or nothing at all:
 *   already downloaded here        → Plays now
 *   addon says cached              → Plays now
 *   addon said nothing             → Starts in a moment  (it may need warming)
 *   addon says not cached          → Not cached yet      (collapsed)
 */

interface Source {
  key: string
  addonId: string
  addonName: string
  stream: Stream
  info: StreamInfo
  /** A completed download of this exact file is on the device. */
  onDisk: boolean
}

type StatusTone = 'instant' | 'ondisk' | 'caching' | 'cold'

const SORTS = ['Recommended', 'Best picture', 'Smallest file', 'Fastest start'] as const
type Sort = (typeof SORTS)[number]

function tierOf(info: StreamInfo): string {
  return qualityTier(info.quality)
}

function statusOf(source: Source): { tone: StatusTone; label: string } {
  if (source.onDisk) return { tone: 'ondisk', label: 'Already on this device' }
  if (source.info.cached === true) return { tone: 'instant', label: 'Plays instantly' }
  if (source.info.cached === false) return { tone: 'cold', label: 'Not cached yet' }
  return { tone: 'caching', label: 'The addon did not say' }
}

const TONE_COLOR: Record<StatusTone, { dot: string; text: string }> = {
  instant: { dot: 'var(--su)', text: 'var(--su)' },
  ondisk: { dot: 'var(--ac)', text: 'var(--ac)' },
  caching: { dot: 'var(--ca)', text: 'var(--ca)' },
  cold: { dot: 'var(--t4)', text: 'var(--t3)' },
}

/** `DDP 5.1` → format and channel count as separate lines of the SOUND tile. */
function splitAudio(audio: string | null): { format: string; channels: string } {
  if (!audio) return { format: 'Unknown', channels: '' }
  const match = /^(.*?)\s*(\d(?:\.\d)?)$/.exec(audio)
  return match
    ? { format: match[1]!.trim(), channels: match[2]! }
    : { format: audio, channels: '' }
}

/** Estimated stream bitrate in Mbps; null unless both size and runtime are known. */
function bitrateMbps(info: StreamInfo, runtimeMinutes: number | undefined): number | null {
  if (!info.sizeBytes || !runtimeMinutes) return null
  return (info.sizeBytes * 8) / (runtimeMinutes * 60) / 1e6
}

function warningFor(source: Source): string | null {
  if ((source.stream.subtitles ?? []).length === 0) {
    return 'No subtitle track in this release. Halo will look for one through your subtitle addons.'
  }
  if (source.stream.behaviorHints?.notWebReady) {
    return 'The addon flags this file as awkward to stream. mpv will still try it directly.'
  }
  return null
}

export function SourcesSheet({ params }: { params: StreamsParams }) {
  const { push, setRoot, closeSheet } = useNav()
  const { data, isLoading, error } = useStreams(params.type, params.videoId)
  const { data: watchStates } = useWatchStates()
  const { downloads } = useDownloads()
  const settings = useSettings()

  const [filter, setFilter] = useState<string | null>(null)
  const [sort, setSort] = useState<Sort>('Recommended')
  const [sortOpen, setSortOpen] = useState(false)
  const [openKey, setOpenKey] = useState<string | null>(null)
  const [coldOpen, setColdOpen] = useState(false)
  const [copied, setCopied] = useState<string | null>(null)
  const [infoOpen, setInfoOpen] = useState(false)
  const [cursor, setCursor] = useState(0)
  const [busy, setBusy] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  const downloadFor = downloads.find((item) => item.media.video_id === params.videoId)

  const sources = useMemo<Source[]>(
    () =>
      (data?.groups ?? []).flatMap((group) =>
        group.streams
          .filter((stream) => !!stream.url)
          .map((stream, index): Source => {
            const info = parseStreamInfo(stream)
            return {
              key: `${group.addonId}:${index}`,
              addonId: group.addonId,
              addonName: group.addonName,
              stream,
              info,
              onDisk:
                downloadFor?.status === 'done' &&
                !!info.filename &&
                downloadFor.media.filename === info.filename,
            }
          }),
      ),
    [data, downloadFor],
  )

  const best = useMemo(
    () => [...sources].sort((a, b) => compareStreams(a.info, b.info))[0] ?? null,
    [sources],
  )

  const heaviestMbps = useMemo(
    () =>
      sources.reduce((peak, source) => {
        const value = bitrateMbps(source.info, params.runtimeMinutes)
        return value && value > peak ? value : peak
      }, 0),
    [sources, params.runtimeMinutes],
  )

  const tiers = useMemo(() => {
    const seen = new Map<string, Quality>()
    for (const source of sources) {
      if (source.info.quality) seen.set(tierOf(source.info), source.info.quality)
    }
    return [...seen.entries()].sort((a, b) => qualityRank(a[1]) - qualityRank(b[1]))
  }, [sources])

  const matches = useCallback(
    (source: Source, key: string | null) => {
      if (key === null) return true
      if (key === 'plays-now') {
        const tone = statusOf(source).tone
        return tone === 'instant' || tone === 'ondisk'
      }
      return tierOf(source.info) === key
    },
    [],
  )

  const filters = useMemo(
    () => [
      { key: null as string | null, label: 'All', count: sources.length },
      {
        key: 'plays-now',
        label: 'Plays now',
        count: sources.filter((s) => matches(s, 'plays-now')).length,
      },
      ...tiers.map(([tier]) => ({
        key: tier,
        label: tier === 'FULL HD' ? 'Full HD' : tier,
        count: sources.filter((s) => tierOf(s.info) === tier).length,
      })),
    ],
    [sources, tiers, matches],
  )

  const sorted = useMemo(() => {
    const shown = sources.filter((source) => matches(source, filter))
    const rank: Record<Sort, (source: Source) => number> = {
      Recommended: () => 0,
      'Best picture': (source) => qualityRank(source.info.quality),
      'Smallest file': (source) => source.info.sizeBytes ?? Number.MAX_SAFE_INTEGER,
      'Fastest start': (source) => ({ instant: 0, ondisk: 0, caching: 1, cold: 2 })[statusOf(source).tone],
    }
    // Recommended keeps `compareStreams` order; the others are stable sorts on
    // top of it, so equal keys stay in the recommended order.
    const base = [...shown].sort((a, b) => compareStreams(a.info, b.info))
    return sort === 'Recommended' ? base : base.sort((a, b) => rank[sort](a) - rank[sort](b))
  }, [sources, filter, sort, matches])

  const rest = sorted.filter((source) => source.key !== best?.key)

  const groups = useMemo(() => {
    const bucket = (source: Source): 'now' | 'soon' | 'cold' => {
      const tone = statusOf(source).tone
      if (tone === 'instant' || tone === 'ondisk') return 'now'
      return tone === 'caching' ? 'soon' : 'cold'
    }
    const defs = [
      {
        id: 'now' as const,
        name: 'Plays now',
        note: 'cached by an addon or already on this device',
      },
      {
        id: 'soon' as const,
        name: 'Starts in a moment',
        note: 'the addon did not say whether the file is ready',
      },
      { id: 'cold' as const, name: 'Not cached yet', note: 'may take a while to start' },
    ]
    return defs
      .map((def) => {
        const items = rest.filter((source) => bucket(source) === def.id)
        const collapsed = def.id === 'cold' && !coldOpen && items.length > 0
        return { ...def, items: collapsed ? [] : items, collapsed, total: items.length }
      })
      .filter((group) => group.items.length > 0 || group.collapsed)
  }, [rest, coldOpen])

  /** Flat keyboard order: the pick first, then every visible row. */
  const flat = useMemo(
    () => [...(best ? [best] : []), ...groups.flatMap((group) => group.items)],
    [best, groups],
  )

  const resumeState = (watchStates ?? []).find(
    (state) => state.videoId === params.videoId && !state.watched && state.positionSec > 30,
  )

  const play = useCallback(
    (source: Source) => {
      const { stream } = source
      if (!stream.url) return
      push({
        name: 'player',
        url: stream.url,
        videoId: params.videoId,
        itemId: params.itemId,
        type: params.type,
        title: params.title,
        addonId: source.addonId,
        ...(params.metaId ? { metaId: params.metaId } : {}),
        ...(params.showName ? { showName: params.showName } : {}),
        ...(params.episodeLabel ? { episodeLabel: params.episodeLabel } : {}),
        ...(params.poster ? { poster: params.poster } : {}),
        ...(stream.behaviorHints?.bingeGroup ? { bingeGroup: stream.behaviorHints.bingeGroup } : {}),
        ...(stream.behaviorHints?.filename ? { filename: stream.behaviorHints.filename } : {}),
        ...(stream.behaviorHints?.videoSize ? { videoSize: stream.behaviorHints.videoSize } : {}),
      })
    },
    [push, params],
  )

  const downloadMedia = (source: Source): DownloadMedia => ({
    video_id: params.videoId,
    item_id: params.itemId,
    media_type: params.type,
    meta_id: params.metaId,
    title: params.title,
    show_name: params.showName,
    episode_label: params.episodeLabel,
    poster: params.poster,
    addon_id: source.addonId,
    binge_group: source.stream.behaviorHints?.bingeGroup,
    filename: source.info.filename,
    ...(source.info.sizeBytes != null ? { video_size: source.info.sizeBytes } : {}),
    video_hash: source.stream.behaviorHints?.videoHash,
    stream_name: source.stream.name,
    stream_title: source.stream.title,
  })

  /**
   * The subtitle the download should carry: the release's own track when it
   * matches the preferred language, otherwise the best addon result. Hashing
   * runs through the shell's native fetch so range requests are not CORS-blocked.
   */
  const resolvePreferredSubtitle = async (
    source: Source,
  ): Promise<DownloadSubtitleRequest | undefined> => {
    const preferred = settings.preferredSubtitleLang
    if (!preferred) return undefined
    const bundled = source.stream.subtitles?.find((subtitle) =>
      languageMatches(subtitle.lang, preferred),
    )
    if (bundled) return { url: bundled.url, lang: bundled.lang, id: bundled.id }

    let videoHash = source.stream.behaviorHints?.videoHash
    let videoSize = source.stream.behaviorHints?.videoSize
    try {
      if (!videoHash && source.stream.url) {
        const result = await computeVideoHash(source.stream.url, { fetch: nativeFetch })
        videoHash = result.hash
        videoSize = videoSize ?? result.size
      }
    } catch {
      // ID and filename lookup remains useful when a host rejects ranges.
    }
    const result = await getClient().getSubtitles(params.type, params.videoId, {
      videoHash,
      videoSize,
      filename: source.stream.behaviorHints?.filename,
    })
    const selected = result.results
      .flatMap((group) => group.subtitles ?? [])
      .find((subtitle) => languageMatches(subtitle.lang, preferred))
    return selected ? { url: selected.url, lang: selected.lang, id: selected.id } : undefined
  }

  const save = async (source: Source) => {
    if (!source.stream.url || busy) return
    setBusy(source.key)
    setActionError(null)
    const request = {
      media: downloadMedia(source),
      url: source.stream.url,
      headers: source.stream.behaviorHints?.proxyHeaders?.request ?? {},
    }
    const begin = async (replace: boolean) => {
      const entry = await startDownload({ ...request, replace_existing: replace })
      void resolvePreferredSubtitle(source)
        .then((subtitle) => (subtitle ? attachDownloadSubtitle(entry.job_id, subtitle) : undefined))
        .catch(() => undefined)
    }
    try {
      await begin(false)
      setRoot('downloads')
    } catch (value) {
      const message = value instanceof Error ? value.message : String(value)
      // One download per video: swapping the source has to be deliberate.
      if (message.includes('different source is already saved')) {
        if (window.confirm(`${message}\n\nReplace the existing download with this source?`)) {
          try {
            await begin(true)
            setRoot('downloads')
          } catch (retry) {
            setActionError(retry instanceof Error ? retry.message : String(retry))
          }
        }
      } else {
        setActionError(message)
      }
    } finally {
      setBusy(null)
    }
  }

  const copyName = async (source: Source) => {
    try {
      await navigator.clipboard.writeText(source.info.filename)
      setCopied(source.key)
    } catch {
      setActionError('This system did not allow copying to the clipboard.')
    }
  }

  // Keyboard: the design's own legend — move, play, details, close.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        closeSheet()
        return
      }
      if (event.target instanceof HTMLInputElement) return
      if (flat.length === 0) return
      if (event.key === 'ArrowDown') {
        event.preventDefault()
        setCursor((index) => Math.min(flat.length - 1, index + 1))
      } else if (event.key === 'ArrowUp') {
        event.preventDefault()
        setCursor((index) => Math.max(0, index - 1))
      } else if (event.key === 'ArrowRight') {
        event.preventDefault()
        const source = flat[Math.min(cursor, flat.length - 1)]!
        setOpenKey((key) => (key === source.key ? null : source.key))
      } else if (event.key === 'Enter') {
        event.preventDefault()
        play(flat[Math.min(cursor, flat.length - 1)]!)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [flat, cursor, closeSheet, play])

  const cachedCount = sources.filter((source) => source.info.cached === true).length
  const addonCount = (data?.groups ?? []).filter((group) => group.streams.length > 0).length
  const kicker = [params.showName ?? params.title, params.episodeLabel, params.episodeLabel && params.title]
    .filter(Boolean)
    .join(' · ')
    .toUpperCase()

  const specTile = (source: Source) => {
    const mbps = bitrateMbps(source.info, params.runtimeMinutes)
    const audio = splitAudio(source.info.audio)
    const subLangs = [...new Set((source.stream.subtitles ?? []).map((s) => s.lang))]
    const status = statusOf(source)
    const cacheGood = status.tone === 'instant' || status.tone === 'ondisk'
    return (
      <div className="spec-panel">
        <div className="spec-pair">
          <div className="spec-tile">
            <div className="mono-cap">PICTURE</div>
            <div style={{ display: 'flex', alignItems: 'flex-end', gap: 9, flexWrap: 'wrap' }}>
              <span className="spec-value">{qualityResolution(source.info.quality) ?? '—'}</span>
              <span className="mono">{source.info.codec ?? ''}</span>
            </div>
            <span className="spec-chip">{source.info.dynamicRange ?? 'SDR'}</span>
          </div>
          <div className="spec-tile">
            <div className="mono-cap">SOUND</div>
            <div style={{ display: 'flex', alignItems: 'flex-end', gap: 9, flexWrap: 'wrap' }}>
              <span className="spec-word">{audio.format}</span>
              <span className="mono">{audio.channels}</span>
            </div>
            <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap' }}>
              {source.info.languages.length === 0 ? (
                <span className="spec-chip">NOT STATED</span>
              ) : (
                source.info.languages.map((lang) => (
                  <span key={lang} className="spec-chip">
                    {lang}
                  </span>
                ))
              )}
            </div>
          </div>
        </div>

        <div className="spec-tile">
          <div style={{ display: 'flex', justifyContent: 'space-between' }} className="mono-cap">
            <span>BANDWIDTH NEEDED</span>
            <span>
              {heaviestMbps > 0 ? `HEAVIEST HERE ${heaviestMbps.toFixed(0)} MBPS` : 'NEEDS RUNTIME'}
            </span>
          </div>
          <div style={{ display: 'flex', alignItems: 'flex-end', gap: 10, flexWrap: 'wrap' }}>
            <span className="spec-value">{mbps ? `${mbps.toFixed(0)} MBPS` : '—'}</span>
            <span style={{ fontSize: 14, color: 'var(--t3)' }}>
              {source.info.sizeBytes ? formatBytes(source.info.sizeBytes) : 'size not stated'}
            </span>
          </div>
          <div className="meter">
            <div
              style={{
                width: mbps && heaviestMbps > 0 ? `${Math.round((mbps / heaviestMbps) * 100)}%` : '0%',
              }}
            />
          </div>
        </div>

        <div className="spec-pair">
          <div className="spec-tile">
            <div className="mono-cap">SUBTITLES</div>
            <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap' }}>
              {subLangs.length === 0 ? (
                <span className="spec-chip">NONE</span>
              ) : (
                subLangs.slice(0, 8).map((lang) => (
                  <span key={lang} className="spec-chip" title={languageLabel(lang)}>
                    {lang.toUpperCase()}
                  </span>
                ))
              )}
            </div>
          </div>
          <div className="spec-tile">
            <div className="mono-cap">COMES FROM</div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 9, flexWrap: 'wrap' }}>
              <span className="spec-word">{source.addonName}</span>
              <span
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 6,
                  padding: '3px 9px',
                  borderRadius: 4,
                  background: cacheGood ? 'var(--sut)' : 'var(--ct)',
                  border: `1px solid ${cacheGood ? 'var(--sus)' : 'var(--cts)'}`,
                }}
              >
                <span
                  className="dot"
                  style={{ width: 5, height: 5, flex: '0 0 5px', background: TONE_COLOR[status.tone].dot }}
                />
                <span style={{ fontSize: 13, color: cacheGood ? 'var(--su)' : 'var(--t3)' }}>
                  {source.onDisk
                    ? 'On disk'
                    : source.info.cached === true
                      ? 'Cached'
                      : source.info.cached === false
                        ? 'Not cached'
                        : 'Unknown'}
                </span>
              </span>
            </div>
          </div>
        </div>

        <button
          type="button"
          className="btn h34"
          style={{ alignSelf: 'flex-start' }}
          onClick={() => void copyName(source)}
        >
          <Icon name="copy" size={12} />
          <span>{copied === source.key ? 'File name copied' : 'Copy file name'}</span>
        </button>
      </div>
    )
  }

  const actions = (source: Source) => (
    <>
      <button type="button" className="btn-accent h36" onClick={() => play(source)}>
        <Icon name="play" size={14} />
        <span>Play</span>
      </button>
      <button
        type="button"
        className="btn h36"
        disabled={busy === source.key}
        onClick={() => void save(source)}
      >
        <Icon name="downloads" size={15} />
        <span>{busy === source.key ? 'Saving…' : 'Save for offline'}</span>
      </button>
      <div className="spacer" />
      <button
        type="button"
        className="btn h36"
        onClick={() => setOpenKey((key) => (key === source.key ? null : source.key))}
      >
        <span>{openKey === source.key ? 'Hide details' : 'Details'}</span>
        <Icon name={openKey === source.key ? 'chevronUp' : 'chevronDown'} size={12} />
      </button>
    </>
  )

  return (
    <div className="sheet-layer">
      <div className="sheet-scrim" onClick={closeSheet} />
      <div className="sheet" role="dialog" aria-label="Choose a source">
        <div className="sheet-head">
          <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="sheet-kicker ellipsis">{kicker}</div>
              <div className="sheet-heading">Choose a source</div>
              <div className="sheet-count">
                {isLoading
                  ? 'Asking your addons…'
                  : `${sources.length} source${sources.length === 1 ? '' : 's'} from ${addonCount} addon${addonCount === 1 ? '' : 's'} · ${cachedCount} cached`}
              </div>
            </div>
            <button
              type="button"
              className="icon-btn icon-btn-bare icon-btn-30"
              title="Close"
              onClick={closeSheet}
            >
              <Icon name="x" size={12} />
            </button>
          </div>

          {sources.length > 0 && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 18 }}>
              <div style={{ flex: 1, minWidth: 0, display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                {filters.map((entry) => (
                  <button
                    key={entry.key ?? 'all'}
                    type="button"
                    className={`chip-sq ${filter === entry.key ? 'chip-sq-active' : ''}`}
                    onClick={() => setFilter(entry.key)}
                  >
                    <span>{entry.label}</span>
                    <span className="tray-count">{entry.count}</span>
                  </button>
                ))}
              </div>
              <MenuAnchor>
                <button
                  type="button"
                  className="btn h30"
                  style={{ color: 'var(--t2)', fontSize: 14 }}
                  onClick={() => setSortOpen((open) => !open)}
                >
                  <span>{sort}</span>
                  <Icon name="chevronDown" size={12} />
                </button>
                <Menu open={sortOpen} onClose={() => setSortOpen(false)} minWidth={180}>
                  {SORTS.map((option) => (
                    <MenuItem
                      key={option}
                      label={option}
                      checked={sort === option}
                      onClick={() => {
                        setSort(option)
                        setSortOpen(false)
                      }}
                    />
                  ))}
                </Menu>
              </MenuAnchor>
            </div>
          )}
        </div>

        <div className="sheet-body thin-bar">
          {isLoading && (
            <div className="state-note" style={{ padding: '20px 0' }}>
              <span className="spinner" /> Fetching sources…
            </div>
          )}
          {error && <div className="state-note error-text" style={{ padding: '20px 0' }}>{String(error)}</div>}
          {actionError && (
            <div className="state-note error-text" style={{ padding: '12px 0' }}>
              {actionError}
            </div>
          )}
          {!isLoading && sources.length === 0 && (
            <div className="state-note" style={{ padding: '20px 0' }}>
              No playable sources from your addons for this title.
            </div>
          )}

          {best && (
            <div className={`pick-card ${cursor === 0 ? 'src-card-cursor' : ''}`}>
              <div className="pick-wash" />
              <div style={{ position: 'relative', padding: 20 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <span className="dot" style={{ background: TONE_COLOR[statusOf(best).tone].dot }} />
                  <span style={{ fontSize: 14, color: TONE_COLOR[statusOf(best).tone].text }}>
                    {statusOf(best).label}
                  </span>
                  <span className="dot-sep">·</span>
                  <span style={{ fontSize: 14, color: 'var(--t3)' }}>Halo picked this for you</span>
                </div>
                <div
                  style={{
                    display: 'grid',
                    gridTemplateColumns: '104px minmax(0,1fr)',
                    gap: 20,
                    marginTop: 12,
                  }}
                >
                  <div className="tierbox">
                    <span className={`tier-badge ${tierOf(best.info) === '4K' ? 'tier-badge-hi' : ''}`}>
                      {tierOf(best.info)}
                    </span>
                    <span className="mono">{best.info.dynamicRange ?? 'SDR'}</span>
                  </div>
                  <div style={{ minWidth: 0 }}>
                    <div style={{ fontSize: 16, fontWeight: 600, textWrap: 'pretty' }}>
                      {whyLine(best)}
                    </div>
                    <div
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 10,
                        marginTop: 7,
                        flexWrap: 'wrap',
                      }}
                    >
                      <span className="mono">{metaLine(best)}</span>
                      <span className={`subs-chip ${subsLabel(best) === 'NO SUBS' ? 'subs-chip-off' : ''}`}>
                        {subsLabel(best)}
                      </span>
                    </div>
                    {resumeState && (
                      <div style={{ marginTop: 6, fontSize: 14, color: 'var(--ac)' }}>
                        Picks up at {formatClock(resumeState.positionSec)} where you left off.
                      </div>
                    )}
                    <div className="src-file ellipsis" title={best.info.filename}>
                      {best.info.filename}
                    </div>
                  </div>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 18 }}>
                  {actions(best)}
                </div>
              </div>
              {openKey === best.key && specTile(best)}
            </div>
          )}

          {groups.map((group) => (
            <div key={group.id}>
              <div className="group-head">
                <span className="group-name">{group.name}</span>
                <span className="spacer ellipsis" style={{ fontSize: 14, color: 'var(--t3)' }}>
                  {group.note}
                </span>
                <span style={{ fontSize: 14, color: 'var(--t4)' }}>
                  {group.total} source{group.total === 1 ? '' : 's'}
                </span>
              </div>
              {group.collapsed && (
                <button type="button" className="reveal-row" onClick={() => setColdOpen(true)}>
                  Show {group.total} uncached source{group.total === 1 ? '' : 's'}
                </button>
              )}
              {group.items.map((source) => {
                const status = statusOf(source)
                const warning = warningFor(source)
                const at = flat.findIndex((entry) => entry.key === source.key)
                return (
                  <div
                    key={source.key}
                    className={`src-card ${openKey === source.key ? 'src-card-open' : ''} ${
                      at === cursor ? 'src-card-cursor' : ''
                    }`}
                  >
                    <button
                      type="button"
                      className="src-click"
                      onClick={() => {
                        setCursor(at)
                        setOpenKey((key) => (key === source.key ? null : source.key))
                      }}
                    >
                      <span className="tierbox">
                        <span
                          className={`tier-badge ${tierOf(source.info) === '4K' ? 'tier-badge-hi' : ''}`}
                        >
                          {tierOf(source.info)}
                        </span>
                        <span className="mono">{source.info.dynamicRange ?? 'SDR'}</span>
                      </span>
                      <span style={{ display: 'flex', flexDirection: 'column', minWidth: 0 }}>
                        <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                          <span className="dot" style={{ background: TONE_COLOR[status.tone].dot }} />
                          <span style={{ fontSize: 15, color: TONE_COLOR[status.tone].text }}>
                            {status.label}
                          </span>
                        </span>
                        <span
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: 10,
                            marginTop: 6,
                            flexWrap: 'wrap',
                          }}
                        >
                          <span className="mono">{metaLine(source)}</span>
                          <span
                            className={`subs-chip ${subsLabel(source) === 'NO SUBS' ? 'subs-chip-off' : ''}`}
                          >
                            {subsLabel(source)}
                          </span>
                        </span>
                        {warning && (
                          <span style={{ display: 'flex', alignItems: 'flex-start', gap: 7, marginTop: 5 }}>
                            <span style={{ color: 'var(--ca)', display: 'flex', paddingTop: 1 }}>
                              <Icon name="warning" size={13} />
                            </span>
                            <span style={{ fontSize: 14, color: 'var(--ca)', textWrap: 'pretty' }}>
                              {warning}
                            </span>
                          </span>
                        )}
                        <span className="src-file ellipsis" title={source.info.filename}>
                          {source.info.filename}
                        </span>
                      </span>
                      <span className="src-reason">{reasonFor(source, sources)}</span>
                    </button>
                    <div className="src-actions">{actions(source)}</div>
                    {openKey === source.key && specTile(source)}
                  </div>
                )
              })}
            </div>
          ))}

          {filter !== null && rest.length === 0 && sources.length > 0 && (
            <div className="state-note" style={{ padding: '20px 0' }}>
              No sources match that filter.
            </div>
          )}
        </div>

        <div className="sheet-foot">
          <button type="button" className="sheet-foot-btn" onClick={() => setInfoOpen((open) => !open)}>
            <span style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              {[
                { key: '↑↓', label: 'move' },
                { key: 'Enter', label: 'play' },
                { key: '→', label: 'details' },
                { key: 'Esc', label: 'close' },
              ].map((hint) => (
                <span key={hint.key} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                  <span className="keycap">{hint.key}</span>
                  <span style={{ fontSize: 13, color: 'var(--t4)' }}>{hint.label}</span>
                </span>
              ))}
            </span>
            <span className="spacer" />
            <span style={{ fontSize: 14, color: 'var(--ac)' }}>How this list was built</span>
            <span style={{ color: 'var(--t3)', display: 'flex' }}>
              <Icon name={infoOpen ? 'chevronUp' : 'chevronDown'} size={12} />
            </span>
          </button>
          {infoOpen && (
            <div className="info-cols">
              <div>
                <div className="info-head">Providers</div>
                {(data?.groups ?? []).map((group) => (
                  <div
                    key={group.addonId}
                    style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}
                  >
                    <span
                      className="dot"
                      style={{ background: group.streams.length > 0 ? 'var(--su)' : 'var(--t4)' }}
                    />
                    <span className="spacer ellipsis" style={{ fontSize: 14, color: 'var(--t2)' }}>
                      {group.addonName}
                    </span>
                    <span style={{ fontSize: 13, color: 'var(--t4)' }}>
                      {group.streams.length} sources
                    </span>
                  </div>
                ))}
                {(data?.errors ?? []).map((failure) => (
                  <div
                    key={failure.id}
                    style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}
                  >
                    <span className="dot" style={{ background: 'var(--cr)' }} />
                    <span className="spacer ellipsis" style={{ fontSize: 14, color: 'var(--t2)' }}>
                      {failure.name ?? 'An addon'}
                    </span>
                    <span style={{ fontSize: 13, color: 'var(--t4)' }}>no answer</span>
                  </div>
                ))}
              </div>
              <div>
                <div className="info-head">Quality mix</div>
                {tiers.map(([tier], index) => {
                  const count = sources.filter((source) => tierOf(source.info) === tier).length
                  const share = sources.length > 0 ? Math.round((count / sources.length) * 100) : 0
                  return (
                    <div key={tier} className="share-row">
                      <span style={{ fontSize: 14, color: 'var(--t2)' }}>
                        {tier === 'FULL HD' ? '1080p' : tier}
                      </span>
                      <span className="share-track">
                        <span
                          style={{
                            width: `${share}%`,
                            background: index === 0 ? 'var(--ac)' : 'var(--chd)',
                          }}
                        />
                      </span>
                      <span style={{ fontSize: 13, color: 'var(--t4)' }}>{count}</span>
                    </div>
                  )
                })}
              </div>
              <div>
                <div className="info-head">How Halo chose</div>
                {[
                  { name: 'Prefer cached', value: 'On' },
                  { name: 'Prefer resolution', value: 'Highest' },
                  {
                    name: 'Audio language',
                    value: settings.preferredAudioLang
                      ? languageLabel(settings.preferredAudioLang)
                      : 'Any',
                  },
                  {
                    name: 'Subtitle language',
                    value: settings.preferredSubtitleLang
                      ? languageLabel(settings.preferredSubtitleLang)
                      : 'Off',
                  },
                ].map((rule) => (
                  <div key={rule.name} style={{ display: 'flex', gap: 12, marginBottom: 7 }}>
                    <span style={{ flex: 1, fontSize: 14, color: 'var(--t2)' }}>{rule.name}</span>
                    <span style={{ fontSize: 14, color: 'var(--t1)' }}>{rule.value}</span>
                  </div>
                ))}
                <button
                  type="button"
                  className="btn-link"
                  style={{ marginTop: 6, paddingInline: 0 }}
                  onClick={() => {
                    setSettingsSection('playback')
                    setRoot('settings')
                  }}
                >
                  Change these in Playback
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

/** `8.4 GB · 4K · ATMOS · EN` — the mono fact line under a row's status. */
function metaLine(source: Source): string {
  return [
    source.info.sizeBytes ? formatBytes(source.info.sizeBytes) : null,
    source.info.quality,
    source.info.audio,
    source.info.languages.join('/') || null,
  ]
    .filter(Boolean)
    .join(' · ')
    .toUpperCase()
}

function subsLabel(source: Source): string {
  const count = new Set((source.stream.subtitles ?? []).map((s) => s.lang)).size
  return count === 0 ? 'NO SUBS' : `${count} SUB${count === 1 ? '' : 'S'}`
}

/** The pick card's one-sentence justification, assembled from what we know. */
function whyLine(source: Source): string {
  const picture = [source.info.quality?.toUpperCase(), source.info.dynamicRange]
    .filter(Boolean)
    .join(' ')
  const wait = source.onDisk
    ? 'already saved on this device'
    : source.info.cached === true
      ? 'cached and ready'
      : source.info.cached === false
        ? 'not cached yet, so it needs a moment'
        : 'wait time unknown'
  const extras = [
    source.info.audio,
    (source.stream.subtitles ?? []).length > 0 ? 'subtitles included' : null,
  ].filter(Boolean)
  return `${picture || 'This release'}, ${wait}${extras.length > 0 ? `, with ${extras.join(' and ')}` : ''}.`
}

/** The short right-aligned label that says why a row is in the list at all. */
function reasonFor(source: Source, all: Source[]): string {
  if (source.onDisk) return 'Saved offline'
  const sizes = all.map((entry) => entry.info.sizeBytes).filter((size): size is number => !!size)
  if (source.info.sizeBytes && sizes.length > 1) {
    if (source.info.sizeBytes === Math.max(...sizes)) return 'Largest file'
    if (source.info.sizeBytes === Math.min(...sizes)) return 'Smallest file'
  }
  const ranks = all.map((entry) => qualityRank(entry.info.quality))
  if (qualityRank(source.info.quality) === Math.max(...ranks)) return 'Lowest quality'
  return ''
}

/** Kept for the download row's "choose again" entry point. */
export function sheetParamsForDownload(item: DownloadView): StreamsParams {
  return {
    type: item.media.media_type,
    videoId: item.media.video_id,
    itemId: item.media.item_id,
    title: item.media.title,
    ...(item.media.meta_id ? { metaId: item.media.meta_id } : {}),
    ...(item.media.show_name ? { showName: item.media.show_name } : {}),
    ...(item.media.episode_label ? { episodeLabel: item.media.episode_label } : {}),
    ...(item.media.poster ? { poster: item.media.poster } : {}),
  }
}
