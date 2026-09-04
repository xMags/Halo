import {
  languageLabel,
  languageMatches,
  type MetaVideo,
  type NextEpisodeResult,
  type Subtitle,
  type WatchState,
} from '@halo/core'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { getClient } from '../api'
import { ArtImage } from '../components/ArtImage'
import { Icon } from '../components/Icon'
import { TitleBar } from '../components/TitleBar'
import { Toggle } from '../components/Toggle'
import { getPlaybackFiles, nextDownloadedEpisode, useDownloads } from '../downloads'
import { formatClock } from '../format'
import {
  mpvCmd,
  mpvGet,
  mpvObserve,
  mpvSet,
  mpvUnobserveAll,
  onMpvEndFile,
  onMpvEvent,
  onMpvProp,
} from '../mpv'
import { useNav, type PlayerParams } from '../nav'
import {
  resolvePlayerShortcut,
  shouldRunUpNextCountdown,
  upNextCancelAction,
  videoTopMarginRatio,
} from '../playerLogic'
import { setLocalPrefs, useLocalPrefs } from '../localPrefs'
import { useAddonSubtitles, useReportWatchState } from '../queries'
import { useSettings, useSettingsLoaded, useUpdateSettings } from '../settings'
import { getSubtitleChoice, rememberSubtitleChoice } from '../subtitleMemory'
import {
  MPV_DEFAULT_SUB_FONT,
  OUTLINE_BORDER,
  SUBTITLE_FONTS,
  SUBTITLE_OUTLINES,
  SUBTITLE_SCALE_DEFAULT,
  SUBTITLE_SCALE_MAX,
  SUBTITLE_SCALE_MIN,
  SUBTITLE_SCALE_STEP,
} from '../subtitleStyle'
import { TITLE_BAR_HEIGHT } from '../theme'
import type { WindowFullscreenController } from '../window'

/** Watch-state cadence and thresholds — identical to mobile's player. */
const REPORT_INTERVAL_MS = 15_000
const WATCHED_THRESHOLD = 0.9
const CONTROLS_HIDE_DELAY_MS = 3_000
const NEXT_EPISODE_TIMEOUT_MS = 15_000
const UP_NEXT_COUNTDOWN_SEC = 8
const VIDEO_CLICK_DELAY_MS = 220

const SUB_DELAY_STEP_MS = 50
const SUB_DELAY_LIMIT_MS = 5_000
const AUDIO_DELAY_STEP_MS = 50
const AUDIO_DELAY_LIMIT_MS = 5_000

// A new keyed Player waits for the previous mount's native teardown. This
// keeps autoplay replacement from interleaving stop/unobserve with the next
// observe/load sequence on the one shared mpv handle.
let previousPlayerTeardown: Promise<void> = Promise.resolve()

/** Rates offered by the speed tab; mpv corrects pitch up to 2×. */
const SPEEDS: ReadonlyArray<{ rate: number; note: string }> = [
  { rate: 0.5, note: 'half speed' },
  { rate: 0.75, note: 'slower' },
  { rate: 1, note: 'normal' },
  { rate: 1.25, note: 'faster' },
  { rate: 1.5, note: 'brisk' },
  { rate: 2, note: 'double' },
]

const KEY_HINTS = 'SPACE PLAY · ←/→ 10 S · F FULL SCREEN · Z FILL · ESC EXIT'

type RailTab = 'audio' | 'subtitles' | 'speed'
/** The two halves of the subtitles tab: which track, and how it looks. */
type SubTab = 'tracks' | 'appearance'

interface SubtitleLanguageGroup {
  lang: string
  label: string
  variants: Subtitle[]
}

function isInteractivePlayerTarget(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return false
  if (target instanceof HTMLElement && target.isContentEditable) return true
  return target.closest('button, input, select, textarea, a, [role="slider"]') !== null
}

/**
 * One row per language, preferred language first then alphabetical. Addons
 * return dozens of same-language variants ranked by their own scoring (hash
 * matches first), so variant order within a language is preserved — the first
 * one is the addon's best guess.
 */
function groupSubtitlesByLanguage(subs: Subtitle[], preferredLang?: string): SubtitleLanguageGroup[] {
  const groups = new Map<string, Subtitle[]>()
  for (const sub of subs) {
    const existing = groups.get(sub.lang)
    if (existing) existing.push(sub)
    else groups.set(sub.lang, [sub])
  }
  const preferred = (lang: string) => (preferredLang ? languageMatches(lang, preferredLang) : false)
  return [...groups.entries()]
    .map(([lang, variants]) => ({ lang, label: languageLabel(lang), variants }))
    .sort(
      (a, b) =>
        Number(preferred(b.lang)) - Number(preferred(a.lang)) || a.label.localeCompare(b.label),
    )
}

interface MpvTrack {
  id: number
  type: string
  title?: string
  lang?: string
  codec?: string
  selected?: boolean
  external?: boolean
  'demux-channel-count'?: number
  /** Source URL/path for external tracks — the identity `sub-add` dedupe keys on. */
  'external-filename'?: string
}

/** Technical badges in the top bar, read from mpv once the file is loaded. */
async function readVideoTags(): Promise<string[]> {
  const [height, format, pixelFormat, primaries, gamma] = await Promise.all([
    mpvGet('video-params/h'),
    mpvGet('video-format'),
    mpvGet('video-params/pixelformat'),
    mpvGet('video-params/primaries'),
    mpvGet('video-params/gamma'),
  ])
  const tags: string[] = []

  const lines = Number(height)
  if (Number.isFinite(lines) && lines > 0) tags.push(`${Math.round(lines)}p`)

  if (format) {
    const codec = format.toUpperCase()
    const tenBit = /10/.test(pixelFormat ?? '')
    tags.push(tenBit ? `${codec} 10-BIT` : codec)
  }

  // bt.2020 primaries with a PQ or HLG transfer is the definition of an HDR
  // presentation; anything else is SDR regardless of what the file claims.
  if (primaries === 'bt.2020') {
    if (gamma === 'pq') tags.push('HDR10')
    else if (gamma === 'hlg') tags.push('HLG')
  }
  return tags
}

function trackLabel(track: MpvTrack): string {
  return track.title ?? (track.lang ? languageLabel(track.lang) : `Track ${track.id}`)
}

/**
 * The note under a track's name. The codec is deliberately absent: it is shown
 * as the row's right-hand badge, and printing it in both places spelt the same
 * fact twice on every row.
 */
function trackDetail(track: MpvTrack): string {
  return [
    track.lang && track.title ? languageLabel(track.lang) : null,
    track['demux-channel-count'] ? `${track['demux-channel-count']} ch` : null,
  ]
    .filter(Boolean)
    .join(' · ')
}

interface PlayerProps extends PlayerParams {
  windowFullscreen: WindowFullscreenController
}

export function Player({ windowFullscreen, ...params }: PlayerProps) {
  const { pop, replace, openSheet } = useNav()
  const queryClient = useQueryClient()
  const report = useReportWatchState()
  const settings = useSettings()
  const settingsLoaded = useSettingsLoaded()
  const updateSettings = useUpdateSettings()
  const { downloads } = useDownloads()
  const prefs = useLocalPrefs()

  const [position, setPosition] = useState(0)
  const [duration, setDuration] = useState(0)
  const [bufferedTo, setBufferedTo] = useState(0)
  const [paused, setPaused] = useState(false)
  const [buffering, setBuffering] = useState(false)
  const [volume, setVolume] = useState(100)
  const [muted, setMuted] = useState(false)
  const [speed, setSpeed] = useState(1)
  const [tracks, setTracks] = useState<MpvTrack[]>([])
  const [videoTags, setVideoTags] = useState<string[]>([])
  const [fileLoaded, setFileLoaded] = useState(false)
  const [railTab, setRailTab] = useState<RailTab | null>(null)
  const [subTab, setSubTab] = useState<SubTab>('tracks')
  /** mpv `panscan`: 1 crops the picture to fill the window, 0 letterboxes. */
  const [fill, setFill] = useState(false)
  const [controlsVisible, setControlsVisible] = useState(true)
  const { fullscreen, setFullscreen, toggleFullscreen: toggleNativeFullscreen } = windowFullscreen
  const [playerError, setPlayerError] = useState<string | null>(null)
  const [controlNotice, setControlNotice] = useState<string | null>(null)
  const [endReached, setEndReached] = useState(false)
  /** Non-null while the user drags the scrubber; committed as one seek on release. */
  const [dragValue, setDragValue] = useState<number | null>(null)
  /** Session-only subtitle sync offset (mobile parity: never synced). */
  const [subtitleDelayMs, setSubtitleDelayMs] = useState(0)
  /** Session-only audio sync offset, same reasoning as the subtitle one. */
  const [audioDelayMs, setAudioDelayMs] = useState(0)
  /** `${addonId}:${lang}` of the language row whose variants are expanded. */
  const [expandedLang, setExpandedLang] = useState<string | null>(null)
  /** Addon id of the currently active external sub (mpv can't tell us which). */
  const [activeExternalId, setActiveExternalId] = useState<string | null>(null)

  const subTracks = useMemo(() => tracks.filter((t) => t.type === 'sub'), [tracks])
  const audioTracks = useMemo(() => tracks.filter((t) => t.type === 'audio'), [tracks])

  const subs = useAddonSubtitles({
    type: params.type,
    videoId: params.videoId,
    streamUrl: params.url,
    filename: params.filename,
    videoSize: params.videoSize,
    enabled: params.sourceKind !== 'download',
  })

  // Live values in refs so the report interval never resets on ticks.
  const progressRef = useRef({ positionSec: 0, durationSec: 0 })
  const videoClickTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const reportNow = useCallback(() => {
    const { positionSec, durationSec: total } = progressRef.current
    // Too short / barely started — not worth a history row (mobile parity).
    if (total < 60 || positionSec < 5) return
    const state: WatchState = {
      videoId: params.videoId,
      itemId: params.itemId,
      positionSec: Math.floor(positionSec),
      durationSec: Math.floor(total),
      watched: positionSec / total >= WATCHED_THRESHOLD,
      // Denormalized display fields — show name over episode title for series.
      name: params.showName ?? params.title,
      ...(params.poster ? { poster: params.poster } : {}),
      updatedAt: Date.now(),
    }
    report.mutate([state])
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params.videoId, params.itemId])

  const resumeAppliedRef = useRef(false)
  /** Read inside the file-loaded handler, which must not depend on prefs. */
  const resumeRef = useRef(prefs.resumePlayback)
  resumeRef.current = prefs.resumePlayback
  /** Prior progress for this video, resolved BEFORE loadfile so the file-loaded handler can't race the fetch. */
  const priorStateRef = useRef<WatchState | null>(null)
  /** End-of-file behavior; a ref because the observers mount before the next-episode prefetch resolves. */
  const onEndRef = useRef<() => void>(() => {})

  const refreshTracks = useCallback(async () => {
    const raw = await mpvGet('track-list').catch(() => null)
    if (!raw) return
    try {
      setTracks(JSON.parse(raw) as MpvTrack[])
    } catch {
      // Unparseable track list — the rail just shows external subs.
    }
  }, [])

  // mpv wiring: observers + event listeners + loadfile, torn down on unmount.
  useEffect(() => {
    document.body.classList.add('player-active')
    let disposed = false
    let localSubtitlePath: string | undefined
    const unlisteners: Array<() => void> = []
    const priorTeardown = previousPlayerTeardown

    const keepListener = async (listener: Promise<() => void>): Promise<boolean> => {
      const unlisten = await listener
      if (disposed) {
        unlisten()
        return false
      }
      unlisteners.push(unlisten)
      return true
    }

    const setup = async () => {
      await priorTeardown
      if (disposed) return

      if (
        !(await keepListener(
          onMpvProp(({ name, value }) => {
            if (name === 'time-pos' && typeof value === 'number') {
              progressRef.current.positionSec = value
              setPosition(value)
            } else if (name === 'duration' && typeof value === 'number') {
              progressRef.current.durationSec = value
              setDuration(value)
            } else if (name === 'demuxer-cache-time' && typeof value === 'number') {
              setBufferedTo(value)
            } else if (name === 'pause' && typeof value === 'boolean') {
              setPaused(value)
            } else if (name === 'paused-for-cache' && typeof value === 'boolean') {
              setBuffering(value)
            } else if (name === 'volume' && typeof value === 'number') {
              setVolume(value)
            } else if (name === 'mute' && typeof value === 'boolean') {
              setMuted(value)
            } else if (name === 'speed' && typeof value === 'number') {
              setSpeed(value)
            }
          }),
        ))
      ) {
        return
      }
      if (
        !(await keepListener(
          onMpvEvent((kind) => {
            if (kind === 'file-loaded') {
              setFileLoaded(true)
              setPlayerError(null)
              void readVideoTags().then(setVideoTags).catch(() => undefined)
              if (localSubtitlePath) {
                void mpvCmd('sub-add', localSubtitlePath)
                  .then(() => {
                    if (params.subtitleLang) {
                      rememberSubtitleChoice(params.videoId, params.itemId, {
                        kind: 'downloaded',
                        lang: params.subtitleLang,
                      })
                    }
                    return refreshTracks()
                  })
                  .catch(() => undefined)
              } else {
                void refreshTracks()
              }
              // Resume once per mount: prior unfinished position wins (mobile
              // parity). Duration comes from mpv directly — the observed
              // `duration` prop event can land after file-loaded.
              if (!resumeAppliedRef.current) {
                resumeAppliedRef.current = true
                void (async () => {
                  const prior = priorStateRef.current
                  if (!resumeRef.current) return
                  if (!prior || prior.watched || prior.positionSec <= 30) return
                  const total = Number((await mpvGet('duration').catch(() => null)) ?? 0)
                  if (total > 0 && prior.positionSec / total < 0.95) {
                    await mpvCmd('seek', prior.positionSec, 'absolute')
                  }
                })()
              }
            }
          }),
        ))
      ) {
        return
      }
      if (
        !(await keepListener(
          onMpvEndFile(({ reason, error }) => {
            if (reason === 'eof') {
              onEndRef.current()
              return
            }
            if (reason === 'error') {
              setBuffering(false)
              setPlayerError(error ? `Playback failed: ${error}.` : 'Playback failed for this source.')
            }
          }),
        ))
      ) {
        return
      }

      const observations = [
        ['time-pos', 'double'],
        ['duration', 'double'],
        ['pause', 'flag'],
        ['paused-for-cache', 'flag'],
        ['volume', 'double'],
        ['mute', 'flag'],
        ['speed', 'double'],
        // End of the demuxer's cached range, in absolute time.
        ['demuxer-cache-time', 'double'],
      ] as const
      for (const [name, format] of observations) {
        await mpvObserve(name, format)
        if (disposed) return
      }

      // Best-effort: an unreachable server must not block playback.
      const states = await queryClient
        .ensureQueryData({ queryKey: ['watchStates'], queryFn: () => getClient().getWatchStates() })
        .catch(() => null)
      priorStateRef.current = states?.find((s) => s.videoId === params.videoId) ?? null

      if (disposed) return
      // Leftover external sub tracks can survive on the shared mpv handle (a
      // dev reload skips unmount cleanup, and loadfile isn't guaranteed to
      // drop them) — sweep so every mount starts with a clean track list.
      const staleRaw = await mpvGet('track-list').catch(() => null)
      if (staleRaw) {
        try {
          for (const t of JSON.parse(staleRaw) as MpvTrack[]) {
            if (t.type === 'sub' && t.external) await mpvCmd('sub-remove', t.id)
          }
        } catch {
          // Unparseable list — loadfile resets most state anyway.
        }
      }
      let sourceUrl = params.url
      if (params.sourceKind === 'download' && params.downloadId) {
        const files = await getPlaybackFiles(params.downloadId)
        sourceUrl = files.video_path
        localSubtitlePath = files.subtitle_path
      }
      await mpvSet('pause', 'no')
      if (disposed) return
      await mpvCmd('loadfile', sourceUrl)
    }
    void setup().catch(() => {
      if (!disposed) setPlayerError('The player could not start this source.')
    })

    const interval = setInterval(reportNow, REPORT_INTERVAL_MS)

    return () => {
      disposed = true
      clearInterval(interval)
      reportNow()
      for (const unlisten of unlisteners) unlisten()
      previousPlayerTeardown = (async () => {
        await mpvUnobserveAll().catch(() => undefined)
        await mpvCmd('stop').catch(() => undefined)
      })()
      document.body.classList.remove('player-active')
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params.url, params.videoId, params.sourceKind, params.downloadId])

  useEffect(() => {
    const syncVideoMargin = () => {
      const ratio = videoTopMarginRatio(fullscreen, window.innerHeight, TITLE_BAR_HEIGHT)
      void mpvSet('video-margin-ratio-top', String(ratio))
    }
    syncVideoMargin()
    window.addEventListener('resize', syncVideoMargin)
    return () => window.removeEventListener('resize', syncVideoMargin)
  }, [fullscreen])

  // Controls auto-hide while playing. An open rail, a pause, or a scrub in
  // progress pins them: all three are states the user is acting inside.
  const hideTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const pinned = railTab !== null || paused || dragValue !== null
  const poke = useCallback(() => {
    setControlsVisible(true)
    if (hideTimer.current) clearTimeout(hideTimer.current)
    hideTimer.current = setTimeout(() => setControlsVisible(false), CONTROLS_HIDE_DELAY_MS)
  }, [])
  useEffect(() => {
    poke()
    return () => {
      if (hideTimer.current) clearTimeout(hideTimer.current)
      if (videoClickTimer.current) clearTimeout(videoClickTimer.current)
    }
  }, [poke])
  const chromeVisible = controlsVisible || pinned

  const togglePause = useCallback(() => void mpvCmd('cycle', 'pause'), [])
  const seekBy = useCallback((secs: number) => void mpvCmd('seek', secs, 'relative'), [])
  const seekTo = useCallback((secs: number) => void mpvCmd('seek', secs, 'absolute'), [])
  const leaveFullscreenThen = useCallback(
    (navigate: () => void) => {
      void (async () => {
        if (fullscreen) await setFullscreen(false)
        navigate()
      })()
    },
    [fullscreen, setFullscreen],
  )
  const back = useCallback(() => leaveFullscreenThen(pop), [leaveFullscreenThen, pop])
  const toggleFullscreen = useCallback(() => {
    void toggleNativeFullscreen()
  }, [toggleNativeFullscreen])

  // ── Autoplay next episode ────────────────────────────────────────────────
  const autoplayEnabled = settings.autoplayNextEpisode ?? true
  const [nextEpisode, setNextEpisode] = useState<NextEpisodeResult | null>(null)
  const [upNextVisible, setUpNextVisible] = useState(false)
  const [upNextSeconds, setUpNextSeconds] = useState(UP_NEXT_COUNTDOWN_SEC)
  // The countdown, "Play now", and "Cancel" can race — whichever navigation
  // fires first wins, the rest become no-ops.
  const advanceFiredRef = useRef(false)
  const advanceOnce = (go: () => void) => {
    if (advanceFiredRef.current) return
    advanceFiredRef.current = true
    go()
  }

  // Prefetched at playback start, Stremio-style: by the time the credits roll
  // the next episode and its binge-matched stream are already known, so the
  // handoff needs no addon round-trip. Waits for settings so a persisted
  // autoplay-off is honored before any request goes out.
  useEffect(() => {
    if (!settingsLoaded || !autoplayEnabled || params.type !== 'series' || !params.metaId) return
    if (params.sourceKind === 'download') return
    let cancelled = false
    getClient()
      .getNextEpisode(
        {
          type: params.type,
          metaId: params.metaId,
          videoId: params.videoId,
          addonId: params.addonId,
          bingeGroup: params.bingeGroup,
        },
        { signal: AbortSignal.timeout(NEXT_EPISODE_TIMEOUT_MS) },
      )
      .then((result) => {
        if (!cancelled) setNextEpisode(result)
      })
      .catch(() => undefined) // Best-effort — end-of-file falls back to exiting.
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settingsLoaded, autoplayEnabled])

  const tagFor = (video: MetaVideo) =>
    video.season != null && video.episode != null
      ? `S${String(video.season).padStart(2, '0')}E${String(video.episode).padStart(2, '0')}`
      : null
  const nextEpisodeTitle = (video: MetaVideo) => {
    const show = params.showName ?? params.title
    const tag = tagFor(video)
    return tag ? `${show} — ${tag}` : (video.title ?? video.name ?? show)
  }

  const nextOfflineDownload = nextDownloadedEpisode(
    params.videoId,
    downloads.map((entry) => ({
      entry,
      videoId: entry.media.video_id,
      itemId: entry.media.item_id,
      episodeLabel: entry.media.episode_label,
      status: entry.status,
    })),
  )?.entry

  const playNextOfflineEpisode = () => {
    const entry = nextOfflineDownload
    if (!entry) return
    advanceOnce(() =>
      replace({
        name: 'player',
        sourceKind: 'download',
        downloadId: entry.job_id,
        ...(entry.subtitle_lang ? { subtitleLang: entry.subtitle_lang } : {}),
        url: '',
        videoId: entry.media.video_id,
        itemId: entry.media.item_id,
        type: entry.media.media_type,
        title: entry.media.title,
        ...(entry.media.meta_id ? { metaId: entry.media.meta_id } : {}),
        ...(entry.media.show_name ? { showName: entry.media.show_name } : {}),
        ...(entry.media.episode_label ? { episodeLabel: entry.media.episode_label } : {}),
        ...(entry.media.poster ? { poster: entry.media.poster } : {}),
      }),
    )
  }

  const playNextEpisode = () => {
    const video = nextEpisode?.video
    const stream = nextEpisode?.stream
    if (!video || !stream?.url) return
    advanceOnce(() =>
      replace({
        name: 'player',
        url: stream.url!,
        videoId: video.id,
        itemId: params.itemId,
        type: params.type,
        title: nextEpisodeTitle(video),
        metaId: params.metaId!,
        ...(params.showName ? { showName: params.showName } : {}),
        ...(tagFor(video) ? { episodeLabel: tagFor(video)! } : {}),
        ...(params.poster ? { poster: params.poster } : {}),
        ...(params.addonId ? { addonId: params.addonId } : {}),
        ...(stream.behaviorHints?.bingeGroup ? { bingeGroup: stream.behaviorHints.bingeGroup } : {}),
        ...(stream.behaviorHints?.filename ? { filename: stream.behaviorHints.filename } : {}),
        ...(stream.behaviorHints?.videoSize ? { videoSize: stream.behaviorHints.videoSize } : {}),
      }),
    )
  }

  /**
   * No binge match: leave the player and raise the sources sheet for the next
   * episode over whatever screen sent us here, so closing it lands somewhere
   * sensible instead of on a dead player.
   */
  const openNextEpisodePicker = (video: MetaVideo) => {
    const tag = tagFor(video)
    advanceOnce(() => {
      leaveFullscreenThen(() => {
        pop()
        openSheet({
          type: params.type,
          videoId: video.id,
          itemId: params.itemId,
          title: nextEpisodeTitle(video),
          ...(params.metaId ? { metaId: params.metaId } : {}),
          ...(params.showName ? { showName: params.showName } : {}),
          ...(tag ? { episodeLabel: tag } : {}),
          ...(params.poster ? { poster: params.poster } : {}),
        })
      })
    })
  }

  useEffect(() => {
    if (!shouldRunUpNextCountdown(upNextVisible, endReached)) return
    const interval = setInterval(() => setUpNextSeconds((s) => s - 1), 1_000)
    return () => clearInterval(interval)
  }, [upNextVisible, endReached])

  useEffect(() => {
    if (shouldRunUpNextCountdown(upNextVisible, endReached) && upNextSeconds <= 0) {
      playNextEpisode()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [upNextVisible, endReached, upNextSeconds])

  onEndRef.current = () => {
    reportNow()
    if (autoplayEnabled && params.sourceKind === 'download' && nextOfflineDownload) {
      playNextOfflineEpisode()
      return
    }
    if (autoplayEnabled && nextEpisode?.video && nextEpisode.stream?.url) {
      setEndReached(true)
      setRailTab(null)
      setUpNextSeconds(UP_NEXT_COUNTDOWN_SEC)
      setUpNextVisible(true)
      return
    }
    if (autoplayEnabled && nextEpisode?.video) {
      openNextEpisodePicker(nextEpisode.video)
      return
    }
    back()
  }

  // Desktop staples: space, arrows, F, Esc — the set the on-screen hints name.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const shortcut = resolvePlayerShortcut({
        code: e.code,
        ctrlKey: e.ctrlKey,
        altKey: e.altKey,
        shiftKey: e.shiftKey,
        metaKey: e.metaKey,
        repeat: e.repeat,
        interactiveTarget: isInteractivePlayerTarget(e.target),
      })
      if (shortcut === null) return
      e.preventDefault()
      poke()
      if (shortcut === 'toggle-pause') togglePause()
      else if (shortcut === 'seek-back') seekBy(-10)
      else if (shortcut === 'seek-forward') seekBy(10)
      else if (shortcut === 'toggle-fullscreen') toggleFullscreen()
      else if (shortcut === 'toggle-fill') setFill((value) => !value)
      else if (shortcut === 'escape') {
        // Peel one layer at a time: the rail covers the chrome, and full
        // screen is the state the badge tells you Esc will leave.
        if (railTab !== null) setRailTab(null)
        else if (fullscreen) toggleFullscreen()
        else back()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [togglePause, seekBy, toggleFullscreen, back, poke, railTab, fullscreen])

  const selectEmbeddedSub = async (id: number | 'no'): Promise<boolean> => {
    setControlNotice(null)
    try {
      await mpvSet('sid', String(id))
      await refreshTracks()
      return true
    } catch {
      setControlNotice('Could not change the subtitle track. Please try again.')
      return false
    }
  }
  /** Sub ids already handed to `sub-add` this mount — covers the window where
   *  a re-click lands before the track list refresh reports the new track. */
  const addedExternalIdsRef = useRef(new Set<string>())
  const addExternalSub = async (sub: Subtitle, remember: boolean = false): Promise<boolean> => {
    setControlNotice(null)
    // `sub-add` creates a NEW track every call — re-selecting an already-added
    // sub must switch `sid` to the existing track instead. Identity is the
    // addon's sub id carried in the track title (external tracks aren't shown
    // by title anywhere): OpenSubtitles mints fresh URLs on every fetch, so
    // the URL is NOT a stable key.
    const key = String(sub.id)
    const existing = tracks.find((t) => t.type === 'sub' && t.external && t.title === key)
    if (existing) {
      try {
        await mpvSet('sid', String(existing.id))
        setActiveExternalId(sub.id)
        if (remember) {
          rememberSubtitleChoice(params.videoId, params.itemId, {
            kind: 'external',
            lang: sub.lang,
            subId: sub.id,
          })
        }
        await refreshTracks()
        return true
      } catch {
        setControlNotice('Could not activate this subtitle. Please try another variant.')
        return false
      }
    }
    if (addedExternalIdsRef.current.has(key)) return true
    addedExternalIdsRef.current.add(key)
    // sub-add with `select` loads and activates in one step; only valid after
    // file-loaded (rc=-12 before), which holds — the rail opens during playback.
    try {
      await mpvCmd('sub-add', sub.url, 'select', key, sub.lang)
      setActiveExternalId(sub.id)
      if (remember) {
        rememberSubtitleChoice(params.videoId, params.itemId, {
          kind: 'external',
          lang: sub.lang,
          subId: sub.id,
        })
      }
      await refreshTracks()
      return true
    } catch {
      addedExternalIdsRef.current.delete(key)
      setControlNotice('Could not load this subtitle. You can retry it or choose another variant.')
      return false
    }
  }

  // Rail click handlers — these record the choice for restore next time; the
  // auto-apply cascade below deliberately never writes to memory.
  const chooseOff = () => {
    void selectEmbeddedSub('no').then((selected) => {
      if (!selected) return
      rememberSubtitleChoice(params.videoId, params.itemId, { kind: 'off' })
      setActiveExternalId(null)
    })
  }
  const chooseEmbedded = (track: MpvTrack) => {
    void selectEmbeddedSub(track.id).then((selected) => {
      if (!selected) return
      rememberSubtitleChoice(params.videoId, params.itemId, {
        kind: 'embedded',
        lang: track.lang,
        trackName: track.title,
      })
      if (!track.external) setActiveExternalId(null)
    })
  }
  const chooseExternal = (sub: Subtitle) => {
    void addExternalSub(sub, true)
  }

  // ── Preferred-language defaults (applied once per mount) ─────────────────
  const audioLangAppliedRef = useRef(false)
  useEffect(() => {
    if (audioLangAppliedRef.current || !fileLoaded || !settingsLoaded) return
    const pref = settings.preferredAudioLang
    if (!pref) return
    const match = tracks.find((t) => t.type === 'audio' && t.lang && languageMatches(t.lang, pref))
    if (!match) return
    audioLangAppliedRef.current = true
    void mpvSet('aid', String(match.id)).then(refreshTracks)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tracks, fileLoaded, settingsLoaded, settings.preferredAudioLang])

  // Subtitle default cascade, applied once per mount: the remembered explicit
  // choice for this video (exact) or item (language carryover) wins; the
  // synced language preference is the fallback. mpv's own default-track pick
  // stays when neither yields a match. Auto-application never writes back to
  // memory — only user clicks record (see the choose* handlers).
  const subDefaultAppliedRef = useRef(false)
  useEffect(() => {
    if (subDefaultAppliedRef.current || !fileLoaded || !settingsLoaded) return

    const externals = subs.data?.groups.flatMap((g) => g.subtitles) ?? []
    const applyEmbedded = (track: MpvTrack) => {
      subDefaultAppliedRef.current = true
      void selectEmbeddedSub(track.id).then((selected) => {
        if (!selected) subDefaultAppliedRef.current = false
      })
    }
    const applyExternal = (sub: Subtitle) => {
      subDefaultAppliedRef.current = true
      void addExternalSub(sub).then((selected) => {
        if (!selected) subDefaultAppliedRef.current = false
      })
    }
    const embeddedByLang = (lang: string) =>
      subTracks.find((t) => !t.external && t.lang && languageMatches(t.lang, lang))
    const externalByLang = (lang: string) => externals.find((s) => languageMatches(s.lang, lang))

    const remembered = getSubtitleChoice(params.videoId, params.itemId)
    if (remembered?.kind === 'off') {
      subDefaultAppliedRef.current = true
      void selectEmbeddedSub('no').then((selected) => {
        if (!selected) subDefaultAppliedRef.current = false
      })
      return
    }
    if (remembered?.kind === 'downloaded' && params.sourceKind === 'download') {
      subDefaultAppliedRef.current = true
      return
    }
    if (remembered?.kind === 'embedded') {
      const track =
        (remembered.trackName &&
          subTracks.find((t) => !t.external && t.title === remembered.trackName)) ||
        (remembered.lang && embeddedByLang(remembered.lang))
      if (track) return applyEmbedded(track)
      // Different file than the one the choice was made on — carry the
      // language over to external results before giving up.
      if (!subs.data) return // fan-out pending — don't conclude "no match" yet
      const external = remembered.lang && externalByLang(remembered.lang)
      if (external) return applyExternal(external)
    }
    if (remembered?.kind === 'external') {
      if (!subs.data) return
      const sub =
        (remembered.subId && externals.find((s) => s.id === remembered.subId)) ||
        (remembered.lang && externalByLang(remembered.lang))
      if (sub) return applyExternal(sub)
    }

    const pref = settings.preferredSubtitleLang
    if (!pref) return
    const embedded = embeddedByLang(pref)
    if (embedded) return applyEmbedded(embedded)
    if (!subs.data) return
    const external = externalByLang(pref)
    if (external) applyExternal(external)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [subTracks, subs.data, fileLoaded, settingsLoaded, settings.preferredSubtitleLang])

  // Styling maps straight onto mpv's runtime sub properties — no player
  // rebuild, unlike VLC's creation-time options on mobile. The mpv handle
  // outlives player mounts, so reapplying on mount also clears stale values.
  useEffect(() => {
    if (!settingsLoaded) return
    void mpvSet('sub-scale', String((settings.subtitleScalePercent ?? 100) / 100))
    void mpvSet('sub-font', settings.subtitleFontFamily ?? MPV_DEFAULT_SUB_FONT)
    void mpvSet('sub-border-size', String(OUTLINE_BORDER[settings.subtitleOutline ?? 'normal']))
    void mpvSet('sub-shadow-offset', String((settings.subtitleShadow ?? true) ? 2 : 0))
  }, [
    settingsLoaded,
    settings.subtitleScalePercent,
    settings.subtitleFontFamily,
    settings.subtitleOutline,
    settings.subtitleShadow,
  ])

  // Device-local, so they live outside the synced settings blob: hardware
  // decoding depends on this machine's GPU, and track styling is a libass
  // rendering choice this client makes on its own.
  useEffect(() => {
    void mpvSet('hwdec', prefs.hardwareDecoding ? 'auto-safe' : 'no')
  }, [prefs.hardwareDecoding])

  // `no` leaves an ASS track's authored styling alone; `force` makes the
  // settings above win over it. Plain-text tracks follow the settings either way.
  useEffect(() => {
    void mpvSet('sub-ass-override', prefs.subtitleTrackStyling ? 'no' : 'force')
  }, [prefs.subtitleTrackStyling])

  useEffect(() => {
    void mpvSet('panscan', fill ? '1' : '0')
  }, [fill])

  useEffect(() => {
    void mpvSet('sub-delay', String(subtitleDelayMs / 1000))
  }, [subtitleDelayMs])

  useEffect(() => {
    void mpvSet('audio-delay', String(audioDelayMs / 1000))
  }, [audioDelayMs])

  const activeSub = subTracks.find((t) => t.selected)
  const activeAudio = audioTracks.find((t) => t.selected)
  const activeExternalSub = useMemo(
    () =>
      (subs.data?.groups ?? [])
        .flatMap((g) => g.subtitles)
        .find((s) => s.id === activeExternalId),
    [subs.data, activeExternalId],
  )

  const subGroups = useMemo(
    () =>
      (subs.data?.groups ?? [])
        .filter((g) => g.subtitles.length > 0)
        .map((g) => ({
          addonId: g.addonId,
          addonName: g.addonName,
          languages: groupSubtitlesByLanguage(g.subtitles, settings.preferredSubtitleLang),
        })),
    [subs.data, settings.preferredSubtitleLang],
  )

  const shown = dragValue ?? Math.min(position, duration || position)
  const total = Math.max(duration, 1)
  const subtitleChipValue = activeExternalSub
    ? languageLabel(activeExternalSub.lang)
    : activeSub
      ? trackLabel(activeSub)
      : 'Off'

  const titleLine = [
    params.showName ?? params.title,
    params.showName && params.episodeLabel ? `${params.episodeLabel} ${params.title}` : null,
  ]
    .filter(Boolean)
    .join(' · ')
  const sourceLine =
    params.sourceKind === 'download'
      ? ['ON DISK', ...videoTags].join(' · ')
      : videoTags.join(' · ')
  const speedLabel = `${speed % 1 === 0 ? speed : speed.toFixed(2).replace(/0$/, '')}×`
  const hashLabel = subs.data?.hashMatched ? 'HASH MATCH' : 'NAME MATCH'

  return (
    <>
      {!fullscreen && <TitleBar />}
      <div
        className={`player-root ${fullscreen ? '' : 'player-root-windowed'}`}
        style={{ cursor: chromeVisible ? 'default' : 'none' }}
        onMouseMove={poke}
        onDoubleClick={(e) => {
          if (e.target !== e.currentTarget) return
          if (videoClickTimer.current) clearTimeout(videoClickTimer.current)
          videoClickTimer.current = null
          toggleFullscreen()
        }}
        onClick={(e) => {
          if (e.target !== e.currentTarget) return
          // A video-area click while the panel is open dismisses it — pausing
          // would read as a misclick.
          if (railTab !== null) {
            setRailTab(null)
            return
          }
          if (videoClickTimer.current) clearTimeout(videoClickTimer.current)
          videoClickTimer.current = setTimeout(() => {
            videoClickTimer.current = null
            togglePause()
          }, VIDEO_CLICK_DELAY_MS)
        }}
      >
        <div className={`player-scrim-top player-chrome ${chromeVisible ? '' : 'player-hidden'}`} />
        <div
          className={`player-scrim-bottom player-chrome ${chromeVisible ? '' : 'player-hidden'}`}
        />

        {playerError && (
          <div className="player-error-card" role="alert">
            <Icon name="warning" size={22} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="player-error-title">Playback stopped</div>
              <div className="player-error-copy">{playerError}</div>
            </div>
            <button type="button" className="btn-accent h36" onClick={back}>
              Choose another source
            </button>
          </div>
        )}
        {controlNotice && (
          <div className="player-notice" role="status">
            <span>{controlNotice}</span>
            <button type="button" className="btn h30" onClick={() => setControlNotice(null)}>
              Dismiss
            </button>
          </div>
        )}
        {buffering && !playerError && (
          <div className="player-notice">
            <span className="spinner" /> Buffering…
          </div>
        )}
        {paused && !buffering && !playerError && <div className="paused-chip">Paused</div>}

        {/* ── Top bar ───────────────────────────────────────────────────── */}
        <div className={`player-top player-chrome ${chromeVisible ? '' : 'player-hidden'}`}>
          <button type="button" className="player-back" title="Back" onClick={back}>
            <Icon name="back" size={16} />
          </button>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
            <div className="player-title ellipsis">{titleLine}</div>
            {sourceLine && <div className="player-source ellipsis">{sourceLine}</div>}
          </div>
          <div className="spacer" />
          {fullscreen && <div className="player-tag">FULL SCREEN · ESC TO EXIT</div>}
        </div>

        {/* ── Bottom chrome ─────────────────────────────────────────────── */}
        <div className={`player-bottom player-chrome ${chromeVisible ? '' : 'player-hidden'}`}>
          <div className="pchips">
            <PlayerChip
              kicker="SUBTITLES"
              value={subtitleChipValue}
              on={railTab === 'subtitles'}
              onClick={() => setRailTab(railTab === 'subtitles' ? null : 'subtitles')}
            />
            <PlayerChip
              kicker="AUDIO"
              value={activeAudio ? trackLabel(activeAudio) : 'Default'}
              on={railTab === 'audio'}
              onClick={() => setRailTab(railTab === 'audio' ? null : 'audio')}
            />
            <PlayerChip
              kicker="SPEED"
              value={speedLabel}
              on={railTab === 'speed'}
              onClick={() => setRailTab(railTab === 'speed' ? null : 'speed')}
            />
            {nextEpisode?.video && (
              <PlayerChip
                kicker="UP NEXT"
                value={
                  tagFor(nextEpisode.video)
                    ? `${tagFor(nextEpisode.video)} ${nextEpisode.video.title ?? ''}`.trim()
                    : 'Next episode'
                }
                on={upNextVisible}
                onClick={() => setUpNextVisible((visible) => !visible)}
              />
            )}
            <div className="spacer" />
            <div className="pkeys">{KEY_HINTS}</div>
          </div>

          <Scrubber
            position={shown}
            duration={total}
            bufferedTo={bufferedTo}
            onPreview={setDragValue}
            onCommit={(value) => {
              seekTo(value)
              setPosition(value)
              setDragValue(null)
            }}
          />

          <div className="transport">
            <button type="button" className="pplay" title="Play or pause" onClick={togglePause}>
              <Icon name={paused ? 'play' : 'pause'} size={16} />
            </button>
            <button
              type="button"
              className="pround"
              style={{ marginLeft: 14 }}
              title="Back 10 seconds"
              onClick={() => seekBy(-10)}
            >
              <Icon name="replay10" size={16} />
            </button>
            <button
              type="button"
              className="pround"
              style={{ marginLeft: 10 }}
              title="Forward 10 seconds"
              onClick={() => seekBy(10)}
            >
              <Icon name="forward10" size={16} />
            </button>
            <div className="ptime">
              <span className="ptime-now">{formatClock(shown)}</span>
              <span className="ptime-total">/</span>
              <span className="ptime-total">{formatClock(duration)}</span>
            </div>

            <div className="spacer" />

            <div className="pvol">
              <button
                type="button"
                style={{ display: 'flex', color: 'inherit' }}
                title={muted ? 'Unmute' : 'Mute'}
                onClick={() => void mpvCmd('cycle', 'mute')}
              >
                <Icon name={muted || volume === 0 ? 'volumeOff' : 'volume'} size={16} />
              </button>
              <input
                type="range"
                min={0}
                max={100}
                value={muted ? 0 : Math.round(volume)}
                aria-label="Volume"
                onChange={(event) => {
                  const next = Number(event.target.value)
                  if (muted && next > 0) void mpvSet('mute', 'no')
                  void mpvSet('volume', String(next))
                }}
              />
              <span className="pvol-value">{muted ? 0 : Math.round(volume)}%</span>
            </div>
            <button
              type="button"
              className="psquare"
              style={{ marginLeft: 10 }}
              title={fill ? 'Fit the whole picture' : 'Crop to fill the window'}
              onClick={() => setFill((value) => !value)}
            >
              <Icon name={fill ? 'fitFill' : 'fitContain'} size={15} />
            </button>
            <button
              type="button"
              className="psquare"
              style={{ marginLeft: 10 }}
              title={fullscreen ? 'Leave full screen' : 'Full screen'}
              onClick={() => void toggleFullscreen()}
            >
              <Icon name={fullscreen ? 'exitFullscreen' : 'enterFullscreen'} size={15} />
            </button>
          </div>
        </div>

        {/* ── Up next ───────────────────────────────────────────────────── */}
        {upNextVisible && nextEpisode?.video && (
          <div className="upnext-card">
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
              <div className="prail-kicker">UP NEXT</div>
              <div className="mono" style={{ color: 'rgba(255,255,255,.5)' }}>
                {endReached ? `IN ${Math.max(upNextSeconds, 0)} S` : 'READY'}
              </div>
              <div className="spacer" />
              <button
                type="button"
                className="prail-close"
                style={{ width: 22, height: 22 }}
                title="Dismiss"
                onClick={() => {
                  if (upNextCancelAction(endReached) === 'exit') advanceOnce(back)
                  else setUpNextVisible(false)
                }}
              >
                <Icon name="x" size={11} />
              </button>
            </div>
            <div style={{ display: 'flex', gap: 12, marginTop: 12 }}>
              <div
                className="art"
                style={{
                  width: 104,
                  height: 59,
                  flex: '0 0 auto',
                  borderRadius: 6,
                  border: '1px solid rgba(255,255,255,.1)',
                }}
              >
                <ArtImage src={nextEpisode.video.thumbnail} />
              </div>
              <div style={{ minWidth: 0 }}>
                <div style={{ fontSize: 15, fontWeight: 600 }}>
                  {tagFor(nextEpisode.video) ?? 'Next'}
                </div>
                <div
                  className="ellipsis"
                  style={{ marginTop: 3, fontSize: 13, color: 'rgba(255,255,255,.5)' }}
                >
                  {nextEpisode.video.title ?? nextEpisode.video.name ?? ''}
                </div>
              </div>
            </div>
            <div className="upnext-progress">
              <div
                style={{
                  width: `${Math.max(0, 100 - (upNextSeconds / UP_NEXT_COUNTDOWN_SEC) * 100)}%`,
                }}
              />
            </div>
            <div className="upnext-actions">
              <button
                type="button"
                className="psquare"
                style={{ width: 'auto', padding: '0 14px', height: 34, fontSize: 15 }}
                onClick={() => {
                  if (upNextCancelAction(endReached) === 'exit') advanceOnce(back)
                  else setUpNextVisible(false)
                }}
              >
                {endReached ? 'Exit' : 'Close'}
              </button>
              <button
                type="button"
                className="btn-accent h34"
                onClick={() => {
                  if (nextEpisode.stream?.url) playNextEpisode()
                  else openNextEpisodePicker(nextEpisode.video!)
                }}
              >
                <Icon name="play" size={13} />
                <span>{nextEpisode.stream?.url ? 'Play now' : 'Choose source'}</span>
              </button>
            </div>
          </div>
        )}

        {/* ── Playback panel ────────────────────────────────────────────── */}
        {railTab !== null && (
          <div style={{ position: 'absolute', inset: 0 }}>
            <div className="prail-scrim" onClick={() => setRailTab(null)} />
            <div className="prail" onMouseMove={poke}>
              <div className="prail-head">
                <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 3 }}>
                  <div className="prail-title">Playback</div>
                  <div className="prail-hint">Applies live: nothing reloads</div>
                </div>
                <button
                  type="button"
                  className="prail-close"
                  title="Close panel"
                  onClick={() => setRailTab(null)}
                >
                  <Icon name="x" size={13} />
                </button>
              </div>

              <div className="ptray ptray-3">
                {(['audio', 'subtitles', 'speed'] as const).map((tab) => (
                  <button
                    key={tab}
                    type="button"
                    className={`ptray-btn ${railTab === tab ? 'ptray-btn-active' : ''}`}
                    onClick={() => setRailTab(tab)}
                  >
                    {tab.charAt(0).toUpperCase() + tab.slice(1)}
                  </button>
                ))}
              </div>

              <div className="prail-body">
                {railTab === 'audio' && (
                  <>
                    <div className="prail-kicker" style={{ marginBottom: 10 }}>
                      IN THIS FILE
                    </div>
                    {audioTracks.length === 0 && (
                      <div className="prail-hint">This file reports no audio tracks yet.</div>
                    )}
                    {audioTracks.map((track) => (
                      <PlayerTrackRow
                        key={track.id}
                        title={trackLabel(track)}
                        note={trackDetail(track)}
                        badge={track.codec?.toUpperCase()}
                        active={!!track.selected}
                        onClick={() => void mpvSet('aid', String(track.id)).then(refreshTracks)}
                      />
                    ))}
                    <div style={{ marginTop: 14 }}>
                      <PlayerStepper
                        title="Audio delay"
                        note={`${AUDIO_DELAY_STEP_MS} ms steps · tap the value to reset`}
                        valueMs={audioDelayMs}
                        onChange={setAudioDelayMs}
                        step={AUDIO_DELAY_STEP_MS}
                        limit={AUDIO_DELAY_LIMIT_MS}
                      />
                    </div>
                  </>
                )}

                {railTab === 'subtitles' && (
                  <>
                    <div className="ptray ptray-2">
                      {(['tracks', 'appearance'] as const).map((tab) => (
                        <button
                          key={tab}
                          type="button"
                          className={`ptray-btn ${subTab === tab ? 'ptray-btn-active' : ''}`}
                          onClick={() => setSubTab(tab)}
                        >
                          {tab === 'tracks' ? 'Tracks' : 'Appearance'}
                        </button>
                      ))}
                    </div>

                    {subTab === 'tracks' && (
                      <>
                        {subs.data && !subs.data.hashMatched && (
                          <div className="prail-warn">
                            Couldn&apos;t fingerprint this stream — addon results may be off-sync.
                          </div>
                        )}
                        <div className="prail-kicker" style={{ marginBottom: 10 }}>
                          IN THIS FILE
                        </div>
                        <PlayerTrackRow
                          title="Off"
                          note="No subtitles"
                          active={!activeSub}
                          onClick={chooseOff}
                        />
                        {/* Embedded tracks only — external subs live in the
                            addon list below, which knows their variant
                            identity. Rendering mpv's external tracks here too
                            showed anonymous duplicate rows. */}
                        {subTracks
                          .filter((track) => !track.external)
                          .map((track) => (
                            <PlayerTrackRow
                              key={track.id}
                              title={trackLabel(track)}
                              note={trackDetail(track)}
                              badge={track.codec?.toUpperCase()}
                              active={!!track.selected}
                              onClick={() => chooseEmbedded(track)}
                            />
                          ))}

                        <div className="prail-kicker" style={{ margin: '15px 0 10px' }}>
                          FROM ADDONS
                        </div>
                        {subs.isLoading && (
                          <div className="prail-hint">
                            <span className="spinner" /> Searching addons…
                          </div>
                        )}
                        {subs.data && subGroups.length === 0 && !subs.isLoading && (
                          <div className="prail-hint">No external subtitles found.</div>
                        )}
                        {subGroups.map((group) => (
                          <div key={group.addonId}>
                            {group.languages.map(({ lang, label, variants }) => {
                              const rowKey = `${group.addonId}:${lang}`
                              const activeIndex = variants.findIndex((v) => v.id === activeExternalId)
                              return (
                                <div key={rowKey}>
                                  <PlayerTrackRow
                                    title={label}
                                    note={`${group.addonName}${
                                      variants.length > 1
                                        ? activeIndex >= 0
                                          ? ` · variant ${activeIndex + 1} of ${variants.length}`
                                          : ` · ${variants.length} variants`
                                        : ''
                                    }`}
                                    badge={hashLabel}
                                    badgeGood={subs.data?.hashMatched}
                                    active={activeIndex >= 0}
                                    // The count expands the rest for
                                    // out-of-sync cases; the row itself takes
                                    // the addon's own best pick.
                                    onBadgeClick={
                                      variants.length > 1
                                        ? () =>
                                            setExpandedLang(expandedLang === rowKey ? null : rowKey)
                                        : undefined
                                    }
                                    onClick={() => chooseExternal(variants[0]!)}
                                  />
                                  {expandedLang === rowKey &&
                                    variants.map((sub, index) => (
                                      <PlayerTrackRow
                                        key={sub.id}
                                        title={`Variant ${index + 1}`}
                                        note={sub.id}
                                        active={sub.id === activeExternalId}
                                        indent
                                        onClick={() => chooseExternal(sub)}
                                      />
                                    ))}
                                </div>
                              )
                            })}
                          </div>
                        ))}
                      </>
                    )}

                    {subTab === 'appearance' && (
                      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                        <div className="pcard">
                          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                            <span className="spacer pcard-title">Size</span>
                            <span className="mono mono-b" style={{ color: '#fff', fontSize: 14 }}>
                              {settings.subtitleScalePercent ?? SUBTITLE_SCALE_DEFAULT}%
                            </span>
                          </div>
                          <div className="pcard-note" style={{ marginTop: 3 }}>
                            mpv sub-scale, live
                          </div>
                          <input
                            type="range"
                            style={{ marginTop: 8, accentColor: '#60CDFF' }}
                            min={SUBTITLE_SCALE_MIN}
                            max={SUBTITLE_SCALE_MAX}
                            step={SUBTITLE_SCALE_STEP}
                            aria-label="Subtitle size"
                            value={settings.subtitleScalePercent ?? SUBTITLE_SCALE_DEFAULT}
                            onChange={(event) =>
                              updateSettings.mutate({
                                subtitleScalePercent: Number(event.target.value),
                              })
                            }
                          />
                          <div className="prail-ticks">
                            <span>{SUBTITLE_SCALE_MIN}</span>
                            <span>100</span>
                            <span>{SUBTITLE_SCALE_MAX}</span>
                          </div>
                        </div>

                        <PlayerStepper
                          title="Delay"
                          note="Shifts the track against the picture"
                          valueMs={subtitleDelayMs}
                          onChange={setSubtitleDelayMs}
                          step={SUB_DELAY_STEP_MS}
                          limit={SUB_DELAY_LIMIT_MS}
                        />

                        <div className="pcard">
                          <div className="pcard-title">Outline</div>
                          <div className="ptray ptray-4" style={{ marginTop: 8 }}>
                            {SUBTITLE_OUTLINES.map((option) => (
                              <button
                                key={option.key}
                                type="button"
                                className={`ptray-btn ${
                                  (settings.subtitleOutline ?? 'normal') === option.key
                                    ? 'ptray-btn-active'
                                    : ''
                                }`}
                                style={{ fontSize: 14 }}
                                onClick={() =>
                                  updateSettings.mutate({ subtitleOutline: option.key })
                                }
                              >
                                {option.label}
                              </button>
                            ))}
                          </div>
                          <div
                            style={{ display: 'flex', alignItems: 'center', gap: 12, marginTop: 14 }}
                          >
                            <span className="spacer pcard-title">Shadow</span>
                            <Toggle
                              label="Subtitle shadow"
                              on={settings.subtitleShadow ?? true}
                              onChange={(next) => updateSettings.mutate({ subtitleShadow: next })}
                            />
                          </div>
                        </div>

                        <div className="pcard">
                          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                            <span className="spacer pcard-title">Track styling</span>
                            <Toggle
                              label="Keep a styled track's own look"
                              on={prefs.subtitleTrackStyling}
                              onChange={(next) => setLocalPrefs({ subtitleTrackStyling: next })}
                            />
                          </div>
                          <div className="pcard-note" style={{ marginTop: 3 }}>
                            On, a styled track keeps its own look. A plain text track always follows
                            the settings here.
                          </div>
                          <div className="ptray ptray-4" style={{ marginTop: 12 }}>
                            {SUBTITLE_FONTS.map((font) => (
                              <button
                                key={font.label}
                                type="button"
                                className={`ptray-btn ${
                                  (settings.subtitleFontFamily ?? '') === (font.family ?? '')
                                    ? 'ptray-btn-active'
                                    : ''
                                }`}
                                style={{ fontSize: 14 }}
                                onClick={() =>
                                  updateSettings.mutate({ subtitleFontFamily: font.family })
                                }
                              >
                                {font.label}
                              </button>
                            ))}
                          </div>
                        </div>
                      </div>
                    )}
                  </>
                )}

                {railTab === 'speed' && (
                  <div className="pspeed-grid">
                    {SPEEDS.map((entry) => (
                      <button
                        key={entry.rate}
                        type="button"
                        className={`pspeed ${
                          Math.abs(speed - entry.rate) < 0.01 ? 'pspeed-active' : ''
                        }`}
                        onClick={() => void mpvSet('speed', String(entry.rate))}
                      >
                        <span className="pspeed-rate">{entry.rate}×</span>
                        <span className="pspeed-note">{entry.note}</span>
                      </button>
                    ))}
                  </div>
                )}
              </div>
            </div>
          </div>
        )}
      </div>
    </>
  )
}

/** One of the four state chips above the seek bar. */
function PlayerChip({
  kicker,
  value,
  on,
  onClick,
}: {
  kicker: string
  value: string
  on: boolean
  onClick: () => void
}) {
  return (
    <button type="button" className={`pchip ${on ? 'pchip-on' : ''}`} onClick={onClick}>
      <span className="pchip-kicker">{kicker}</span>
      <span className="pchip-value ellipsis">{value}</span>
    </button>
  )
}

/**
 * The seek bar. Dragging previews without seeking and commits once on release,
 * so a scrub across a remote stream issues one seek rather than dozens.
 */
function Scrubber({
  position,
  duration,
  bufferedTo,
  onPreview,
  onCommit,
}: {
  position: number
  duration: number
  bufferedTo: number
  onPreview: (value: number | null) => void
  onCommit: (value: number) => void
}) {
  const track = useRef<HTMLDivElement>(null)
  const [hoverAt, setHoverAt] = useState<number | null>(null)

  const valueAt = (clientX: number): number => {
    const el = track.current
    if (!el) return position
    const rect = el.getBoundingClientRect()
    const ratio = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width))
    return ratio * duration
  }
  const percent = (value: number) => `${Math.min(100, Math.max(0, (value / duration) * 100))}%`

  return (
    <div
      className="seek"
      role="slider"
      tabIndex={0}
      aria-label="Seek"
      aria-valuenow={Math.round(position)}
      aria-valuemin={0}
      aria-valuemax={Math.round(duration)}
      onKeyDown={(event) => {
        if (event.key === 'ArrowLeft') onCommit(Math.max(0, position - 10))
        else if (event.key === 'ArrowRight') onCommit(Math.min(duration, position + 10))
        else return
        event.preventDefault()
      }}
    >
      <div
        className="seek-track"
        ref={track}
        onPointerDown={(event) => {
          event.currentTarget.setPointerCapture(event.pointerId)
          onPreview(valueAt(event.clientX))
        }}
        onPointerMove={(event) => {
          setHoverAt(valueAt(event.clientX))
          if (event.currentTarget.hasPointerCapture(event.pointerId)) {
            onPreview(valueAt(event.clientX))
          }
        }}
        onPointerUp={(event) => {
          event.currentTarget.releasePointerCapture(event.pointerId)
          onCommit(valueAt(event.clientX))
        }}
        onPointerLeave={() => setHoverAt(null)}
      >
        {hoverAt !== null && (
          <div className="seek-bubble" style={{ left: percent(hoverAt) }}>
            {formatClock(hoverAt)}
          </div>
        )}
        <div className="seek-buffered" style={{ width: percent(bufferedTo) }} />
        <div className="seek-fill" style={{ width: percent(position) }} />
        <div className="seek-knob" style={{ left: percent(position) }} />
      </div>
    </div>
  )
}

/** A track row in the panel: selection dot, title, note, codec or match badge. */
function PlayerTrackRow({
  title,
  note,
  badge,
  badgeGood,
  active,
  indent,
  onClick,
  onBadgeClick,
}: {
  title: string
  note?: string
  badge?: string
  /** Green badge: this subtitle was matched by file hash, not by name. */
  badgeGood?: boolean
  active: boolean
  indent?: boolean
  onClick: () => void
  onBadgeClick?: () => void
}) {
  return (
    <div style={{ display: 'flex', alignItems: 'center' }}>
      <button
        type="button"
        className={`ptrack ${active ? 'ptrack-active' : ''}`}
        style={indent ? { paddingLeft: 28 } : undefined}
        onClick={onClick}
      >
        <span className="ptrack-dot" />
        <span style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
          <span className="ptrack-title ellipsis">{title}</span>
          {note && <span className="ptrack-note ellipsis">{note}</span>}
        </span>
        {badge && (
          <span className={`ptrack-codec ${badgeGood ? 'ptrack-match-hash' : ''}`}>{badge}</span>
        )}
      </button>
      {/* Variants sit behind their own control: the row takes the addon's best
          pick, and this only matters when that pick is out of sync. */}
      {onBadgeClick && (
        <button
          type="button"
          className="prail-close"
          style={{ width: 26, height: 26, flex: '0 0 26px' }}
          title="Show every variant"
          onClick={onBadgeClick}
        >
          <Icon name="chevronDown" size={12} />
        </button>
      )}
    </div>
  )
}

/** The ± delay control: a hairline frame around a mono readout. */
function PlayerStepper({
  title,
  note,
  valueMs,
  onChange,
  step,
  limit,
}: {
  title: string
  note: string
  valueMs: number
  onChange: (value: number) => void
  step: number
  limit: number
}) {
  const clamp = (value: number) => Math.max(-limit, Math.min(limit, value))
  const seconds = valueMs / 1000
  return (
    <div className="pcard" style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
      <span style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 3 }}>
        <span className="pcard-title">{title}</span>
        <span className="pcard-note">{note}</span>
      </span>
      <span className="pstepper">
        <button
          type="button"
          className="pstepper-btn"
          title={`Minus ${step} ms`}
          onClick={() => onChange(clamp(valueMs - step))}
        >
          −
        </button>
        <button
          type="button"
          className="pstepper-value"
          title="Reset"
          onClick={() => onChange(0)}
        >
          {`${seconds > 0 ? '+' : ''}${seconds.toFixed(2)} s`}
        </button>
        <button
          type="button"
          className="pstepper-btn"
          title={`Plus ${step} ms`}
          onClick={() => onChange(clamp(valueMs + step))}
        >
          +
        </button>
      </span>
    </div>
  )
}
