import { computeVideoHash, languageMatches } from '@halo/core'
import { useQueryClient } from '@tanstack/react-query'
import { fetch as nativeFetch } from '@tauri-apps/plugin-http'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { getClient } from '../api'
import { showDialog } from '../components/Dialog'
import { FluentIcon } from '../components/FluentIcon'
import { Menu, MenuAnchor, MenuItem } from '../components/Menu'
import {
  attachDownloadSubtitle,
  startDownload,
  useDownloads,
  type DownloadMedia,
  type DownloadSubtitleRequest,
  type DownloadView,
} from '../downloads/downloadsStore'
import { useLocalPrefs } from '../localPrefs'
import { useNav, type StreamsParams } from '../nav'
import { useStreams, useWatchStates } from '../queries'
import { useSettings } from '../settings/syncedSettings'
import { revealSettingsSection, type SettingsSection } from '../settings/settingsSection'
import { languageName, watchNote, type DeviceContext } from './sourcePresentation'
import {
  SORTS,
  buildListing,
  filterCounts,
  qualityMix,
  resolveSources,
  selectionStops,
  type LocalFile,
  type SourceFilter,
  type SourceRecord,
  type SourceSort,
} from './sourcesModel'
import { GroupHeader, PickCard, RevealRow, SourceCard } from './SourceCards'
import { SheetFooter } from './SheetFooter'
import { Banner, EmptyState, Resolving } from './SheetStates'

/**
 * The source picker, reproducing the native WinUI Halo Desktop's sources
 * sheet: the same header, filters, pick, provider-grouped list, states and
 * footer, over whatever screen opened it. What each part says comes from
 * `sourcesModel` and `sourcePresentation`; this component owns the sheet's
 * state, keys and actions.
 */

const FILTER_PILLS: Array<{ filter: SourceFilter; label: string }> = [
  { filter: 'all', label: 'All' },
  { filter: 'playsNow', label: 'Plays now' },
  { filter: 'ultraHd', label: '4K' },
  { filter: 'fullHd', label: 'Full HD' },
  { filter: 'hd', label: 'HD' },
]

/** How much of the sheet a selected card may leave before the list follows it, and the lead it keeps. */
const SELECTION_MARGIN = 12
const SELECTION_LEAD = 28

export function SourcesSheet({ params }: { params: StreamsParams }) {
  const { push, setRoot, closeSheet } = useNav()
  const queryClient = useQueryClient()
  const query = useStreams(params.type, params.videoId)
  const { data: watchStates } = useWatchStates()
  const { downloads } = useDownloads()
  const settings = useSettings()
  const localPrefs = useLocalPrefs()

  const [filter, setFilter] = useState<SourceFilter>('all')
  const [sort, setSort] = useState<SourceSort>('Recommended')
  const [sortOpen, setSortOpen] = useState(false)
  const [expandedKey, setExpandedKey] = useState<string | null>(null)
  const [pickExpanded, setPickExpanded] = useState(false)
  /** A row key, or null for the pick (the native sheet's index -1). */
  const [selected, setSelected] = useState<string | null>(null)
  const [coldRevealed, setColdRevealed] = useState(false)
  const [copiedKey, setCopiedKey] = useState<string | null>(null)
  const [infoOpen, setInfoOpen] = useState(false)
  const [closing, setClosing] = useState(false)
  const [busyKey, setBusyKey] = useState<string | null>(null)
  const [revealRequest, setRevealRequest] = useState(0)

  const scrollerRef = useRef<HTMLDivElement>(null)
  const cardRefs = useRef(new Map<string, HTMLDivElement>())

  const loading = query.isPending
  const failed = query.isError

  const completed = useMemo(
    () => downloads.filter((item) => item.status === 'done' && item.media.video_id === params.videoId),
    [downloads, params.videoId],
  )
  const localFiles = useMemo<LocalFile[]>(
    () =>
      completed.map((item) => ({
        jobId: item.job_id,
        releaseName: item.media.filename || item.file_name,
        sizeBytes: Math.max(item.total_bytes, item.downloaded_bytes),
      })),
    [completed],
  )

  const device = useMemo<DeviceContext>(() => {
    const row = (watchStates ?? []).find((state) => state.videoId === params.videoId)
    const tracked = row && row.durationSec > 0
    return {
      preferredSubtitleLanguage: settings.preferredSubtitleLang ?? null,
      // Zero until a download has measured the line; the sheet says so outright.
      lineMbps: localPrefs.measuredLineMbps,
      durationSeconds: tracked ? row.durationSec : (params.runtimeMinutes ?? 0) * 60,
      watchedSeconds: tracked && !row.watched ? row.positionSec : 0,
      watchedDurationSeconds: tracked ? row.durationSec : 0,
    }
  }, [
    watchStates,
    params.videoId,
    params.runtimeMinutes,
    settings.preferredSubtitleLang,
    localPrefs.measuredLineMbps,
  ])

  // A failed resolve still offers what is saved on this device.
  const resolve = useMemo(
    () =>
      resolveSources({
        groups: failed ? [] : (query.data?.groups ?? []),
        errors: failed ? [] : (query.data?.errors ?? []),
        localFiles,
        durationSeconds: device.durationSeconds,
        elapsedSeconds: query.data?.elapsedSeconds ?? 0,
      }),
    [failed, query.data, localFiles, device.durationSeconds],
  )
  const listing = useMemo(
    () =>
      loading
        ? { pick: null, items: [] }
        : buildListing(resolve, { filter, sort, coldRevealed, device }),
    [loading, resolve, filter, sort, coldRevealed, device],
  )
  const counts = useMemo(() => filterCounts(resolve.pool), [resolve.pool])
  const mix = useMemo(() => qualityMix(resolve.pool), [resolve.pool])
  const stops = useMemo(() => selectionStops(listing), [listing])
  const pick = listing.pick
  const selectedKey = selected ?? pick?.entry.key ?? null

  const poolEmpty = resolve.pool.length === 0
  const filteredEmpty = counts[filter] === 0
  const showList = !loading && !poolEmpty && !filteredEmpty
  const showEmpty = !loading && (poolEmpty || filteredEmpty)
  const showBanner = !loading && (failed || resolve.failedProviders > 0)

  const resetView = () => {
    setExpandedKey(null)
    setPickExpanded(false)
    setSelected(null)
    setCopiedKey(null)
  }

  const chooseFilter = (next: SourceFilter) => {
    if (next === filter) return
    setFilter(next)
    resetView()
  }

  const chooseSort = (next: SourceSort) => {
    setSortOpen(false)
    if (next === sort) return
    setSort(next)
    resetView()
  }

  const retry = () => {
    setFilter('all')
    setSort('Recommended')
    setColdRevealed(false)
    resetView()
    void queryClient.resetQueries({ queryKey: ['streams', params.type, params.videoId], exact: true })
  }

  const beginClose = useCallback(() => setClosing(true), [])

  const openSettings = (section: SettingsSection) => {
    revealSettingsSection(section)
    setRoot('settings')
  }

  const toggleExpanded = (key: string) => {
    setCopiedKey(null)
    setPickExpanded(false)
    setExpandedKey((current) => (current === key ? null : key))
    setSelected(key)
  }

  const togglePickExpanded = () => {
    if (!pick) return
    setCopiedKey(null)
    setPickExpanded((open) => !open)
    setExpandedKey(null)
    setSelected(null)
  }

  const play = useCallback(
    (key: string) => {
      const record = resolve.records.get(key)
      if (!record) return
      if (record.downloadJobId) {
        const item = completed.find((download) => download.job_id === record.downloadJobId)
        if (item) playDownload(push, item)
        return
      }
      const stream = record.stream
      if (!stream?.url) return
      push({
        name: 'player',
        url: stream.url,
        videoId: params.videoId,
        itemId: params.itemId,
        type: params.type,
        title: params.title,
        addonId: record.addonId,
        ...(params.metaId ? { metaId: params.metaId } : {}),
        ...(params.showName ? { showName: params.showName } : {}),
        ...(params.episodeLabel ? { episodeLabel: params.episodeLabel } : {}),
        ...(params.poster ? { poster: params.poster } : {}),
        ...(stream.behaviorHints?.bingeGroup ? { bingeGroup: stream.behaviorHints.bingeGroup } : {}),
        ...(stream.behaviorHints?.filename ? { filename: stream.behaviorHints.filename } : {}),
        ...(stream.behaviorHints?.videoSize ? { videoSize: stream.behaviorHints.videoSize } : {}),
      })
    },
    [resolve.records, completed, push, params],
  )

  const downloadMedia = (record: SourceRecord): DownloadMedia => ({
    video_id: params.videoId,
    item_id: params.itemId,
    media_type: params.type,
    meta_id: params.metaId,
    title: params.title,
    show_name: params.showName,
    episode_label: params.episodeLabel,
    poster: params.poster,
    addon_id: record.addonId,
    binge_group: record.stream?.behaviorHints?.bingeGroup,
    filename: record.info.filename,
    ...(record.info.sizeBytes != null ? { video_size: record.info.sizeBytes } : {}),
    video_hash: record.stream?.behaviorHints?.videoHash,
    stream_name: record.stream?.name,
    stream_title: record.stream?.title,
  })

  /**
   * The subtitle the download should carry: the release's own track when it
   * matches the preferred language, otherwise the best addon result. Hashing
   * runs through the shell's native fetch so range requests are not CORS-blocked.
   */
  const resolvePreferredSubtitle = async (
    record: SourceRecord,
  ): Promise<DownloadSubtitleRequest | undefined> => {
    const preferred = settings.preferredSubtitleLang
    const stream = record.stream
    if (!preferred || !stream) return undefined
    const bundled = stream.subtitles?.find((subtitle) => languageMatches(subtitle.lang, preferred))
    if (bundled) return { url: bundled.url, lang: bundled.lang, id: bundled.id }

    let videoHash = stream.behaviorHints?.videoHash
    let videoSize = stream.behaviorHints?.videoSize
    try {
      if (!videoHash && stream.url) {
        const result = await computeVideoHash(stream.url, { fetch: nativeFetch })
        videoHash = result.hash
        videoSize = videoSize ?? result.size
      }
    } catch {
      // ID and filename lookup remains useful when a host rejects ranges.
    }
    const result = await getClient().getSubtitles(params.type, params.videoId, {
      videoHash,
      videoSize,
      filename: stream.behaviorHints?.filename,
    })
    const chosen = result.results
      .flatMap((group) => group.subtitles ?? [])
      .find((subtitle) => languageMatches(subtitle.lang, preferred))
    return chosen ? { url: chosen.url, lang: chosen.lang, id: chosen.id } : undefined
  }

  const save = async (key: string) => {
    const record = resolve.records.get(key)
    if (!record?.stream?.url || busyKey) return
    setBusyKey(key)
    const request = {
      media: downloadMedia(record),
      url: record.stream.url,
      headers: record.stream.behaviorHints?.proxyHeaders?.request ?? {},
    }
    const begin = async (replace: boolean) => {
      const result = await startDownload({ ...request, replace_existing: replace })
      if (result.outcome === 'started') {
        const jobId = result.download.job_id
        void resolvePreferredSubtitle(record)
          .then((subtitle) => (subtitle ? attachDownloadSubtitle(jobId, subtitle) : undefined))
          .catch(() => undefined)
      }
      return result.outcome
    }
    try {
      let outcome = await begin(false)
      // One download per video: swapping the source has to be deliberate.
      if (outcome === 'replacement_required') {
        const replace = await showDialog({
          title: 'Replace the saved source?',
          body: 'Halo will keep the current file until the replacement finishes successfully.',
          primary: 'Replace',
          close: 'Keep current',
          defaultButton: 'close',
        })
        if (!replace) return
        outcome = await begin(true)
      }
      if (outcome !== 'replacement_required') {
        // The transfer is on another page, so the sheet gets out of the way.
        setRoot('downloads')
        return
      }
    } catch {
      // Reported below, like any other start that did not happen.
    } finally {
      setBusyKey(null)
    }
    await showDialog({
      title: 'Download could not start',
      body: 'Check the source, free storage, and download folder, then try again.',
      close: 'Close',
    })
  }

  const copyName = async (key: string, file: string) => {
    try {
      await navigator.clipboard.writeText(file)
      setCopiedKey(key)
    } catch {
      setCopiedKey(null)
    }
  }

  // The sheet owns the arrow keys while it is open: move, play, open, close.
  // Re-subscribed every render on purpose, so the handler always sees the
  // current selection and listing without a dependency list to keep in sync.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        beginClose()
        return
      }
      const target = event.target
      if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement) return
      // Enter on a focused button is that button's; the sheet plays otherwise.
      if (event.key === 'Enter') {
        if (target instanceof HTMLButtonElement) return
        event.preventDefault()
        const key = selected ?? pick?.entry.key
        if (key) play(key)
        return
      }
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault()
        if (stops.length === 0) return
        const at = selectedKey ? stops.indexOf(selectedKey) : -1
        const delta = event.key === 'ArrowDown' ? 1 : -1
        const next = at === -1 ? 0 : Math.min(stops.length - 1, Math.max(0, at + delta))
        const key = stops[next]!
        setSelected(pick && key === pick.entry.key ? null : key)
        setRevealRequest((count) => count + 1)
        return
      }
      if (event.key === 'ArrowRight') {
        event.preventDefault()
        if (selected === null) {
          if (pick && !pickExpanded) togglePickExpanded()
        } else if (expandedKey !== selected) {
          toggleExpanded(selected)
        }
        return
      }
      if (event.key === 'ArrowLeft') {
        event.preventDefault()
        if (pickExpanded) togglePickExpanded()
        else if (expandedKey) toggleExpanded(expandedKey)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  // Brings a keyboard selection back into view, leaving a lead above it.
  useEffect(() => {
    if (revealRequest === 0) return
    const scroller = scrollerRef.current
    if (!scroller) return
    if (selected === null) {
      scroller.scrollTo({ top: 0 })
      return
    }
    const card = cardRefs.current.get(selected)
    if (!card) return
    const top = card.getBoundingClientRect().top - scroller.getBoundingClientRect().top
    const bottom = top + card.offsetHeight
    if (top >= SELECTION_MARGIN && bottom <= scroller.clientHeight - SELECTION_MARGIN) return
    scroller.scrollTo({ top: scroller.scrollTop + top - SELECTION_LEAD })
  }, [revealRequest, selected])

  const kicker = kickerFor(params)
  const countLine = loading ? 'Asking your providers…' : failed ? 'No provider could be reached' : resolve.summary
  const rules = [
    { name: 'Prefer sources that play instantly', value: 'On' },
    { name: 'Preferred audio language', value: languageName(settings.preferredAudioLang ?? 'Automatic') },
    {
      name: 'Preferred subtitles',
      value: settings.preferredSubtitleLang ? languageName(settings.preferredSubtitleLang) : 'Off',
    },
    { name: 'Autoplay next episode', value: settings.autoplayNextEpisode === false ? 'Off' : 'On' },
  ]

  return (
    <div className={`sx-layer ${closing ? 'sx-closing' : ''}`}>
      <div className="sx-scrim" onClick={beginClose} />
      <div
        className="sx-panel"
        onAnimationEnd={(event) => {
          if (event.target === event.currentTarget && event.animationName === 'sxSlideOut') closeSheet()
        }}
      >
        <div className="sx-shadow" />
        <div className="sx-sheet" role="dialog" aria-label="Choose a source">
          <div className="sx-head">
            <div className="sx-head-row">
              <div className="sx-head-text">
                {kicker && <div className="sx-kicker ellipsis">{kicker}</div>}
                <div className="sx-heading ellipsis">
                  {params.title ? `Where to play ${params.title}` : 'Where to play this'}
                </div>
                <div className="sx-count ellipsis">{countLine}</div>
              </div>
              <button
                type="button"
                className="sx-close"
                aria-label="Close sources"
                title="Close"
                onClick={beginClose}
              >
                <FluentIcon glyph="close" size={12} />
              </button>
            </div>

            {!loading && !poolEmpty && (
              <div className="sx-filter-row">
                <div className="sx-pills" role="radiogroup">
                  {FILTER_PILLS.map((pill) => (
                    <button
                      key={pill.filter}
                      type="button"
                      role="radio"
                      aria-checked={filter === pill.filter}
                      className={`sx-pill ${filter === pill.filter ? 'sx-pill-on' : ''}`}
                      onClick={() => chooseFilter(pill.filter)}
                    >
                      <span>{pill.label}</span>
                      <span className="sx-pill-count">{counts[pill.filter]}</span>
                    </button>
                  ))}
                </div>
                <MenuAnchor>
                  <button
                    type="button"
                    className="sx-sort"
                    aria-label="Change how sources are sorted"
                    onClick={() => setSortOpen((open) => !open)}
                  >
                    <span>{sort}</span>
                    <FluentIcon glyph="chevronDown" size={12} />
                  </button>
                  <Menu open={sortOpen} onClose={() => setSortOpen(false)} minWidth={0}>
                    {SORTS.map((option) => (
                      <MenuItem
                        key={option}
                        label={option}
                        radio
                        checked={sort === option}
                        onClick={() => chooseSort(option)}
                      />
                    ))}
                  </Menu>
                </MenuAnchor>
              </div>
            )}
          </div>

          {showBanner && (
            <Banner
              tone={failed ? 'info' : 'caution'}
              title={
                failed
                  ? 'You are offline'
                  : resolve.failedProviders === 1
                    ? resolve.firstFailure
                    : `${resolve.failedProviders} providers did not answer`
              }
              body={
                failed
                  ? 'Only files already downloaded to this device can play right now.'
                  : resolve.answeredProviders === 0
                    ? 'Nothing was returned, so some sources may be missing.'
                    : `Showing what the other ${resolve.answeredProviders} ${resolve.answeredProviders === 1 ? 'provider' : 'providers'} returned. Some sources may be missing.`
              }
              action={failed ? 'Retry connection' : 'Ask them again'}
              onAction={retry}
            />
          )}

          <div className="sx-body">
            {loading && <Resolving />}
            {showEmpty && (
              <EmptyState
                title={failed ? 'Nothing could be reached' : poolEmpty ? 'Nothing playable came back' : 'No source matches that filter'}
                body={
                  failed
                    ? 'Halo could not reach any provider, and nothing for this is saved on this device. Anything you download appears here and plays without a connection.'
                    : poolEmpty
                      ? 'Every provider answered, and none of them had a file for this. This usually clears up within a day of release.'
                      : 'Try All, or widen the quality you are willing to accept.'
                }
                onRetry={retry}
                onManageAddons={() => openSettings('addons')}
              />
            )}
            {showList && (
              <div ref={scrollerRef} className="sx-scroller">
                <div className="sx-list">
                  {pick && (
                    <PickCard
                      pick={pick}
                      watchNote={watchNote(device)}
                      expanded={pickExpanded}
                      selected={selected === null}
                      copied={copiedKey === pick.entry.key}
                      saving={busyKey === pick.entry.key}
                      onPlay={() => {
                        setSelected(null)
                        play(pick.entry.key)
                      }}
                      onSave={() => void save(pick.entry.key)}
                      onToggle={togglePickExpanded}
                      onCopy={() => void copyName(pick.entry.key, pick.entry.file)}
                    />
                  )}
                  {listing.items.map((item) => {
                    if (item.kind === 'header') {
                      return <GroupHeader key={item.key} name={item.name} note={item.note} count={item.count} />
                    }
                    if (item.kind === 'more') {
                      return <RevealRow key={item.key} label={item.label} onReveal={() => setColdRevealed(true)} />
                    }
                    const key = item.entry.key
                    return (
                      <SourceCard
                        key={key}
                        row={item}
                        expanded={expandedKey === key}
                        selected={selected === key}
                        copied={copiedKey === key}
                        saving={busyKey === key}
                        cardRef={(element) => {
                          if (element) cardRefs.current.set(key, element)
                          else cardRefs.current.delete(key)
                        }}
                        onPlay={() => play(key)}
                        onSave={() => void save(key)}
                        onToggle={() => toggleExpanded(key)}
                        onCopy={() => void copyName(key, item.entry.file)}
                      />
                    )
                  })}
                </div>
              </div>
            )}
          </div>

          <SheetFooter
            open={infoOpen}
            onToggle={() => setInfoOpen((open) => !open)}
            providers={resolve.providers}
            mix={mix}
            rules={rules}
            onEditPlayback={() => openSettings('playback')}
          />
        </div>
      </div>
    </div>
  )
}

/**
 * Only what the heading does not already say: a movie's show name is its own
 * title, and an entry resumed from Continue watching names the show.
 */
function kickerFor(params: StreamsParams): string {
  const show = params.showName ?? ''
  const episode = params.episodeLabel ?? ''
  const redundant = !show || show === params.title
  if (!episode) return redundant ? '' : show
  return redundant ? episode : `${episode} · ${show}`
}

/** Opens the player on a finished download; the Downloads page plays through this too. */
export function playDownload(push: ReturnType<typeof useNav>['push'], item: DownloadView) {
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
