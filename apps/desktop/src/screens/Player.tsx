import {
  languageLabel,
  languageMatches,
  type MetaVideo,
  type NextEpisodeResult,
  type Subtitle,
  type WatchState,
} from '@halo/core'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { getClient } from '../api'
import { Icon } from '../components/Icon'
import { Slider } from '../components/Slider'
import { WindowButtons } from '../components/WindowButtons'
import { formatClock } from '../format'
import {
  mpvCmd,
  mpvGet,
  mpvObserve,
  mpvSet,
  mpvUnobserveAll,
  onMpvEvent,
  onMpvProp,
} from '../mpv'
import { useNav, type PlayerParams } from '../nav'
import { useAddonSubtitles, useReportWatchState } from '../queries'
import { useSettings, useSettingsLoaded, useUpdateSettings } from '../settings'
import { getSubtitleChoice, rememberSubtitleChoice } from '../subtitleMemory'
import {
  MPV_DEFAULT_SUB_FONT,
  OUTLINE_BORDER,
  SUBTITLE_FONTS,
  SUBTITLE_SCALE_DEFAULT,
  SUBTITLE_SCALE_MAX,
  SUBTITLE_SCALE_MIN,
  SUBTITLE_SCALE_STEP,
} from '../subtitleStyle'

/** Watch-state cadence and thresholds — identical to mobile's player. */
const REPORT_INTERVAL_MS = 15_000
const WATCHED_THRESHOLD = 0.9
const CONTROLS_HIDE_DELAY_MS = 3_000
const NEXT_EPISODE_TIMEOUT_MS = 15_000
const UP_NEXT_COUNTDOWN_SEC = 8

const SUB_DELAY_STEP_MS = 50
const SUB_DELAY_LIMIT_MS = 5_000
const AUDIO_DELAY_STEP_MS = 50
const AUDIO_DELAY_LIMIT_MS = 5_000

/** Rates offered by the speed tab; mpv corrects pitch up to 2×. */
const SPEEDS = [0.75, 1, 1.25, 1.5, 1.75, 2] as const

const KEY_HINTS = 'SPACE PLAY · ←/→ 10 S · F FULL SCREEN · ESC EXIT'

type RailTab = 'audio' | 'subtitles' | 'speed'

interface SubtitleLanguageGroup {
  lang: string
  label: string
  variants: Subtitle[]
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

function trackDetail(track: MpvTrack): string {
  return [
    track.lang && track.title ? languageLabel(track.lang) : null,
    track.codec?.toUpperCase(),
    track['demux-channel-count'] ? `${track['demux-channel-count']} ch` : null,
  ]
    .filter(Boolean)
    .join(' · ')
}

export function Player(params: PlayerParams) {
  const { pop, replace } = useNav()
  const queryClient = useQueryClient()
  const report = useReportWatchState()
  const settings = useSettings()
  const settingsLoaded = useSettingsLoaded()
  const updateSettings = useUpdateSettings()

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
  const [controlsVisible, setControlsVisible] = useState(true)
  const [fullscreen, setFullscreen] = useState(false)
  const [playerError, setPlayerError] = useState<string | null>(null)
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
  })

  // Live values in refs so the report interval never resets on ticks.
  const progressRef = useRef({ positionSec: 0, durationSec: 0 })
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
    const unlisteners: Array<() => void> = []

    ;(async () => {
      await mpvObserve('time-pos', 'double')
      await mpvObserve('duration', 'double')
      await mpvObserve('pause', 'flag')
      await mpvObserve('paused-for-cache', 'flag')
      await mpvObserve('volume', 'double')
      await mpvObserve('mute', 'flag')
      await mpvObserve('speed', 'double')
      // End of the demuxer's cached range, in absolute time — what the
      // scrubber's buffered fill is drawn from.
      await mpvObserve('demuxer-cache-time', 'double')
      await mpvObserve('eof-reached', 'flag')

      unlisteners.push(
        await onMpvProp(({ name, value }) => {
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
          } else if (name === 'eof-reached' && value === true) {
            // Ref, not closure — the autoplay decision needs the prefetched
            // next-episode state, which lands long after these observers mount.
            onEndRef.current()
          }
        }),
        await onMpvEvent((kind) => {
          if (kind === 'file-loaded') {
            setFileLoaded(true)
            void refreshTracks()
            void readVideoTags().then(setVideoTags).catch(() => undefined)
            // Resume once per mount: prior unfinished position wins (mobile
            // parity). Duration comes from mpv directly — the observed
            // `duration` prop event can land after file-loaded.
            if (!resumeAppliedRef.current) {
              resumeAppliedRef.current = true
              void (async () => {
                const prior = priorStateRef.current
                if (!prior || prior.watched || prior.positionSec <= 30) return
                const total = Number((await mpvGet('duration').catch(() => null)) ?? 0)
                if (total > 0 && prior.positionSec / total < 0.95) {
                  await mpvCmd('seek', prior.positionSec, 'absolute')
                }
              })()
            }
          }
        }),
      )

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
      await mpvSet('pause', 'no')
      await mpvCmd('loadfile', params.url).catch((e) => setPlayerError(String(e)))
    })()

    const interval = setInterval(reportNow, REPORT_INTERVAL_MS)

    return () => {
      disposed = true
      clearInterval(interval)
      reportNow()
      for (const unlisten of unlisteners) unlisten()
      void mpvUnobserveAll()
      void mpvCmd('stop')
      document.body.classList.remove('player-active')
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params.url, params.videoId])

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
    }
  }, [poke])
  const chromeVisible = controlsVisible || pinned

  const togglePause = useCallback(() => void mpvCmd('cycle', 'pause'), [])
  const seekBy = useCallback((secs: number) => void mpvCmd('seek', secs, 'relative'), [])
  const seekTo = useCallback((secs: number) => void mpvCmd('seek', secs, 'absolute'), [])
  const back = useCallback(() => {
    if (fullscreen) void getCurrentWindow().setFullscreen(false)
    pop()
  }, [pop, fullscreen])
  const toggleFullscreen = useCallback(async () => {
    const next = !fullscreen
    setFullscreen(next)
    await getCurrentWindow().setFullscreen(next)
  }, [fullscreen])

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

  /** No binge match: land on the next episode's stream picker instead. */
  const openNextEpisodePicker = (video: MetaVideo) => {
    const tag = tagFor(video)
    advanceOnce(() => {
      if (fullscreen) void getCurrentWindow().setFullscreen(false)
      replace({
        name: 'streams',
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
  }

  useEffect(() => {
    if (!upNextVisible) return
    const interval = setInterval(() => setUpNextSeconds((s) => s - 1), 1_000)
    return () => clearInterval(interval)
  }, [upNextVisible])

  useEffect(() => {
    if (upNextVisible && upNextSeconds <= 0) playNextEpisode()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [upNextVisible, upNextSeconds])

  onEndRef.current = () => {
    reportNow()
    if (autoplayEnabled && nextEpisode?.video && nextEpisode.stream?.url) {
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
      if (e.target instanceof HTMLInputElement) return
      poke()
      if (e.code === 'Space') togglePause()
      else if (e.code === 'ArrowLeft') seekBy(-10)
      else if (e.code === 'ArrowRight') seekBy(10)
      else if (e.code === 'KeyF') void toggleFullscreen()
      else if (e.code === 'Escape') {
        // Peel one layer at a time: the rail covers the chrome, and full
        // screen is the state the badge tells you Esc will leave.
        if (railTab !== null) setRailTab(null)
        else if (fullscreen) void toggleFullscreen()
        else back()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [togglePause, seekBy, toggleFullscreen, back, poke, railTab, fullscreen])

  const selectEmbeddedSub = (id: number | 'no') => {
    void mpvSet('sid', String(id)).then(refreshTracks)
  }
  /** Sub ids already handed to `sub-add` this mount — covers the window where
   *  a re-click lands before the track list refresh reports the new track. */
  const addedExternalIdsRef = useRef(new Set<string>())
  const addExternalSub = (sub: Subtitle) => {
    setActiveExternalId(sub.id)
    // `sub-add` creates a NEW track every call — re-selecting an already-added
    // sub must switch `sid` to the existing track instead. Identity is the
    // addon's sub id carried in the track title (external tracks aren't shown
    // by title anywhere): OpenSubtitles mints fresh URLs on every fetch, so
    // the URL is NOT a stable key.
    const key = String(sub.id)
    const existing = tracks.find((t) => t.type === 'sub' && t.external && t.title === key)
    if (existing) {
      void mpvSet('sid', String(existing.id)).then(refreshTracks)
      return
    }
    if (addedExternalIdsRef.current.has(key)) return
    addedExternalIdsRef.current.add(key)
    // sub-add with `select` loads and activates in one step; only valid after
    // file-loaded (rc=-12 before), which holds — the rail opens during playback.
    void mpvCmd('sub-add', sub.url, 'select', key, sub.lang).then(refreshTracks)
  }

  // Rail click handlers — these record the choice for restore next time; the
  // auto-apply cascade below deliberately never writes to memory.
  const chooseOff = () => {
    rememberSubtitleChoice(params.videoId, params.itemId, { kind: 'off' })
    setActiveExternalId(null)
    selectEmbeddedSub('no')
  }
  const chooseEmbedded = (track: MpvTrack) => {
    rememberSubtitleChoice(params.videoId, params.itemId, {
      kind: 'embedded',
      lang: track.lang,
      trackName: track.title,
    })
    if (!track.external) setActiveExternalId(null)
    selectEmbeddedSub(track.id)
  }
  const chooseExternal = (sub: Subtitle) => {
    rememberSubtitleChoice(params.videoId, params.itemId, {
      kind: 'external',
      lang: sub.lang,
      subId: sub.id,
    })
    addExternalSub(sub)
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
      void mpvSet('sid', String(track.id)).then(refreshTracks)
    }
    const applyExternal = (sub: Subtitle) => {
      subDefaultAppliedRef.current = true
      addExternalSub(sub)
    }
    const embeddedByLang = (lang: string) =>
      subTracks.find((t) => !t.external && t.lang && languageMatches(t.lang, lang))
    const externalByLang = (lang: string) => externals.find((s) => languageMatches(s.lang, lang))

    const remembered = getSubtitleChoice(params.videoId, params.itemId)
    if (remembered?.kind === 'off') {
      subDefaultAppliedRef.current = true
      void mpvSet('sid', 'no')
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

  return (
    <div
      className="player-root"
      style={{ cursor: chromeVisible ? 'default' : 'none' }}
      onMouseMove={poke}
      onDoubleClick={() => void toggleFullscreen()}
      onClick={(e) => {
        if (e.target !== e.currentTarget) return
        // A video-area click while the rail is open dismisses it — pausing
        // would read as a misclick.
        if (railTab !== null) setRailTab(null)
        else togglePause()
      }}
    >
      <div className={`player-scrim-top player-chrome ${chromeVisible ? '' : 'player-hidden'}`} />
      <div className={`player-scrim-bottom player-chrome ${chromeVisible ? '' : 'player-hidden'}`} />

      {playerError && <div className="player-notice error-text">{playerError}</div>}
      {buffering && !playerError && (
        <div className="player-notice">
          <span className="spinner" /> Buffering…
        </div>
      )}

      {paused && !buffering && (
        <div className="paused-badge">
          <Icon name="pause" size={22} />
          <div>
            <div style={{ fontSize: 13, fontWeight: 700 }}>Paused</div>
            <div style={{ font: '400 10px/1 var(--mono)', color: 'var(--text-muted)', marginTop: 2 }}>
              SPACE TO RESUME
            </div>
          </div>
        </div>
      )}

      {/* ── Top bar ─────────────────────────────────────────────────────── */}
      <div
        className={`player-top player-chrome ${fullscreen ? 'player-top-fs' : ''} ${chromeVisible ? '' : 'player-hidden'}`}
      >
        <button type="button" className="player-back" title="Back" onClick={back}>
          <Icon name="chevronLeft" size={20} />
        </button>
        <div style={{ minWidth: 0 }}>
          <div className="player-title ellipsis">{params.showName ?? params.title}</div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 3 }}>
            {params.episodeLabel && <span className="player-ep">{params.episodeLabel}</span>}
            <span className="ellipsis" style={{ fontSize: 12, color: 'var(--text-muted)' }}>
              {params.title}
            </span>
          </div>
        </div>
        {videoTags.length > 0 && (
          <div className="player-tags">
            {videoTags.map((tag) => (
              <div key={tag} className="player-tag">
                {tag}
              </div>
            ))}
          </div>
        )}
        <div className="spacer" />
        {fullscreen ? (
          <div className="fs-badge">
            <span style={{ width: 6, height: 6, borderRadius: 999, background: 'var(--accent)' }} />
            <span style={{ font: '700 9.5px/1 var(--mono)', letterSpacing: '0.12em', color: 'var(--text-2)' }}>
              FULL SCREEN
            </span>
            <span style={{ font: '400 9.5px/1 var(--mono)', color: '#565e70' }}>ESC TO EXIT</span>
          </div>
        ) : (
          <WindowButtons />
        )}
      </div>

      {/* ── Bottom chrome ───────────────────────────────────────────────── */}
      <div
        className={`player-bottom player-chrome ${fullscreen ? 'player-bottom-fs' : ''} ${chromeVisible ? '' : 'player-hidden'}`}
      >
        <div className="state-chips">
          <StateChip
            kicker="SUBTITLES"
            value={subtitleChipValue}
            open={railTab === 'subtitles'}
            onClick={() => setRailTab(railTab === 'subtitles' ? null : 'subtitles')}
          />
          <StateChip
            kicker="AUDIO"
            value={activeAudio ? trackLabel(activeAudio) : 'Default'}
            open={railTab === 'audio'}
            onClick={() => setRailTab(railTab === 'audio' ? null : 'audio')}
          />
          <StateChip
            kicker="SPEED"
            value={`${speed % 1 === 0 ? speed : speed.toFixed(2).replace(/0$/, '')}×`}
            open={railTab === 'speed'}
            onClick={() => setRailTab(railTab === 'speed' ? null : 'speed')}
          />
          {nextEpisode?.video && (
            <StateChip
              kicker="UP NEXT"
              value={tagFor(nextEpisode.video) ?? 'Next episode'}
              open={upNextVisible}
              onClick={() => setUpNextVisible((v) => !v)}
            />
          )}
          <div className="spacer" />
          <div className="key-hints">{KEY_HINTS}</div>
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
          <button type="button" className="pbtn pbtn-play" title="Play / pause" onClick={togglePause}>
            <Icon name={paused ? 'play' : 'pause'} size={18} />
          </button>
          <button type="button" className="pbtn" title="Back 10 s" onClick={() => seekBy(-10)}>
            <Icon name="replay" size={17} />
          </button>
          <button type="button" className="pbtn" title="Forward 10 s" onClick={() => seekBy(10)}>
            <Icon name="forward" size={17} />
          </button>
          <div className="ptime">{formatClock(shown)}</div>
          <div className="ptotal">/ {formatClock(duration)}</div>

          <div className="spacer" />

          <div className="vol-group">
            <button
              type="button"
              className="pbtn"
              style={{ border: 'none', background: 'transparent', width: 24, height: 24 }}
              title={muted ? 'Unmute' : 'Mute'}
              onClick={() => void mpvCmd('cycle', 'mute')}
            >
              <Icon name={muted || volume === 0 ? 'volumeOff' : 'volume'} size={17} />
            </button>
            <VolumeBar
              value={muted ? 0 : volume}
              onChange={(next) => {
                if (muted && next > 0) void mpvSet('mute', 'no')
                void mpvSet('volume', String(next))
              }}
            />
            <div className="vol-value">{muted ? 0 : Math.round(volume)}</div>
          </div>

          <button
            type="button"
            className="pbtn pbtn-square"
            title={fullscreen ? 'Leave full screen' : 'Full screen'}
            onClick={() => void toggleFullscreen()}
          >
            <Icon name={fullscreen ? 'exitFullscreen' : 'enterFullscreen'} size={16} />
          </button>
        </div>
      </div>

      {/* ── Up next ─────────────────────────────────────────────────────── */}
      {upNextVisible && nextEpisode?.video && (
        <div className="upnext-card">
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
            <div className="kicker kicker-accent" style={{ fontSize: 10 }}>
              UP NEXT
            </div>
            <div className="meta-mono" style={{ color: 'var(--text-muted)' }}>
              in {Math.max(upNextSeconds, 0)} s
            </div>
            <div className="spacer" />
            <button
              type="button"
              className="icon-btn"
              style={{ width: 22, height: 22 }}
              title="Dismiss"
              onClick={() => setUpNextVisible(false)}
            >
              <Icon name="x" size={11} />
            </button>
          </div>
          <div style={{ display: 'flex', gap: 12, marginTop: 12 }}>
            <div
              className="art art-wide"
              style={{ width: 104, height: 59, flex: '0 0 auto', borderRadius: 8, border: '1px solid rgba(255,255,255,.1)' }}
            >
              {nextEpisode.video.thumbnail && (
                <img src={nextEpisode.video.thumbnail} alt="" draggable={false} />
              )}
            </div>
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: 14, fontWeight: 700 }}>
                {tagFor(nextEpisode.video) ?? 'Next'}
              </div>
              <div className="ellipsis" style={{ marginTop: 3, fontSize: 12, color: 'var(--text-muted)' }}>
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
              className="btn-glass"
              onClick={() => {
                setUpNextVisible(false)
                advanceOnce(back)
              }}
            >
              Cancel
            </button>
            <button type="button" className="btn-primary" onClick={playNextEpisode}>
              <Icon name="play" size={12} />
              Play now
            </button>
          </div>
        </div>
      )}

      {/* ── Right rail ──────────────────────────────────────────────────── */}
      {railTab !== null && (
        <>
          <div className="prail-scrim" onClick={() => setRailTab(null)} />
          <div className="prail" onMouseMove={poke}>
            <div className="prail-head">
              <div style={{ flex: 1 }}>
                <div className="pane-title">Playback</div>
                <div style={{ marginTop: 3, fontSize: 11.5, color: 'var(--text-muted)' }}>
                  Applies live — nothing reloads
                </div>
              </div>
              <button type="button" className="prail-close" title="Close" onClick={() => setRailTab(null)}>
                <Icon name="x" size={14} />
              </button>
            </div>
            <div className="prail-tabs">
              {(['audio', 'subtitles', 'speed'] as const).map((tab) => (
                <button
                  key={tab}
                  type="button"
                  className={`prail-tab ${railTab === tab ? 'prail-tab-active' : ''}`}
                  onClick={() => setRailTab(tab)}
                >
                  {tab.charAt(0).toUpperCase() + tab.slice(1)}
                </button>
              ))}
            </div>

            <div className="prail-body no-bar">
              {railTab === 'audio' && (
                <>
                  <div className="kicker">TRACKS</div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 2, marginTop: 10 }}>
                    {audioTracks.length === 0 && (
                      <div className="body-copy" style={{ fontSize: 12 }}>
                        This file reports no audio tracks yet.
                      </div>
                    )}
                    {audioTracks.map((track) => (
                      <TrackRow
                        key={track.id}
                        label={trackLabel(track)}
                        detail={trackDetail(track)}
                        active={!!track.selected}
                        onClick={() => void mpvSet('aid', String(track.id)).then(refreshTracks)}
                      />
                    ))}
                  </div>
                  <div className="prail-divider" />
                  <Stepper
                    title="Audio delay"
                    hint={`${AUDIO_DELAY_STEP_MS} ms steps · tap to reset`}
                    valueMs={audioDelayMs}
                    onChange={setAudioDelayMs}
                    step={AUDIO_DELAY_STEP_MS}
                    limit={AUDIO_DELAY_LIMIT_MS}
                  />
                </>
              )}

              {railTab === 'subtitles' && (
                <>
                  {subs.data && !subs.data.hashMatched && (
                    <div className="body-copy" style={{ color: 'var(--warning)', fontSize: 11.5, marginBottom: 10 }}>
                      Couldn&apos;t fingerprint this stream — addon results may be off-sync.
                    </div>
                  )}

                  <div className="kicker">IN THIS FILE</div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 2, marginTop: 10 }}>
                    <TrackRow
                      label="Off"
                      detail="No subtitles"
                      active={!activeSub}
                      onClick={chooseOff}
                    />
                    {/* Embedded tracks only — external subs live in the addon
                        list below, which knows their variant identity.
                        Rendering mpv's external tracks here too showed
                        anonymous duplicate rows. */}
                    {subTracks
                      .filter((t) => !t.external)
                      .map((track) => (
                        <TrackRow
                          key={track.id}
                          label={trackLabel(track)}
                          detail={trackDetail(track)}
                          badge={track.codec?.toUpperCase()}
                          active={!!track.selected}
                          onClick={() => chooseEmbedded(track)}
                        />
                      ))}
                  </div>

                  <div className="kicker" style={{ margin: '18px 0 10px' }}>
                    FROM ADDONS
                  </div>
                  {subs.isLoading && (
                    <div className="body-copy" style={{ fontSize: 12 }}>
                      <span className="spinner" /> Searching addons…
                    </div>
                  )}
                  {subs.data && subGroups.length === 0 && !subs.isLoading && (
                    <div className="body-copy" style={{ fontSize: 12 }}>
                      No external subtitles found.
                    </div>
                  )}
                  {subGroups.map((group) => (
                    <div key={group.addonId} style={{ marginBottom: 8 }}>
                      <div className="track-detail" style={{ padding: '6px 10px 2px' }}>
                        {group.addonName}
                      </div>
                      {group.languages.map(({ lang, label, variants }) => {
                        const rowKey = `${group.addonId}:${lang}`
                        const activeIndex = variants.findIndex((v) => v.id === activeExternalId)
                        return (
                          <div key={rowKey}>
                            <TrackRow
                              label={label}
                              detail={
                                activeIndex >= 0 && variants.length > 1
                                  ? `Variant ${activeIndex + 1} of ${variants.length}`
                                  : variants.length > 1
                                    ? `${variants.length} variants`
                                    : 'Addon subtitle'
                              }
                              badge={variants.length > 1 ? String(variants.length) : undefined}
                              // The count badge expands the rest for
                              // out-of-sync cases; the row itself takes the
                              // addon's own best pick.
                              onBadgeClick={
                                variants.length > 1
                                  ? () => setExpandedLang(expandedLang === rowKey ? null : rowKey)
                                  : undefined
                              }
                              active={activeIndex >= 0}
                              onClick={() => chooseExternal(variants[0]!)}
                            />
                            {expandedLang === rowKey &&
                              variants.map((sub, index) => (
                                <TrackRow
                                  key={sub.id}
                                  label={`Variant ${index + 1}`}
                                  detail={sub.id}
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

                  <div className="prail-divider" />
                  <div className="kicker">APPEARANCE</div>
                  <div className="prail-card">
                    <div style={{ display: 'flex', alignItems: 'baseline', gap: 10 }}>
                      <div style={{ fontSize: 13, fontWeight: 700 }}>Size</div>
                      <div className="spacer" style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                        mpv sub-scale, live
                      </div>
                      <div style={{ font: '700 12.5px/1 var(--mono)' }}>
                        {settings.subtitleScalePercent ?? SUBTITLE_SCALE_DEFAULT}%
                      </div>
                    </div>
                    <Slider
                      label="Subtitle size"
                      value={settings.subtitleScalePercent ?? SUBTITLE_SCALE_DEFAULT}
                      min={SUBTITLE_SCALE_MIN}
                      max={SUBTITLE_SCALE_MAX}
                      step={SUBTITLE_SCALE_STEP}
                      onChange={(value) => updateSettings.mutate({ subtitleScalePercent: value })}
                    />
                  </div>
                  <Stepper
                    title="Delay"
                    hint={`${SUB_DELAY_STEP_MS} ms steps · tap to reset`}
                    valueMs={subtitleDelayMs}
                    onChange={setSubtitleDelayMs}
                    step={SUB_DELAY_STEP_MS}
                    limit={SUB_DELAY_LIMIT_MS}
                  />
                  <div className="prail-card">
                    <div style={{ fontSize: 13, fontWeight: 700 }}>Font</div>
                    <div style={{ display: 'flex', gap: 5, marginTop: 10 }}>
                      {SUBTITLE_FONTS.map((font) => (
                        <button
                          key={font.label}
                          type="button"
                          className={`pill-sm ${
                            (settings.subtitleFontFamily ?? '') === (font.family ?? '')
                              ? 'pill-sm-active'
                              : ''
                          }`}
                          onClick={() => updateSettings.mutate({ subtitleFontFamily: font.family })}
                        >
                          {font.label}
                        </button>
                      ))}
                    </div>
                  </div>
                </>
              )}

              {railTab === 'speed' && (
                <>
                  <div className="kicker">SPEED</div>
                  <div className="speed-grid">
                    {SPEEDS.map((rate) => (
                      <button
                        key={rate}
                        type="button"
                        className={`speed-cell ${Math.abs(speed - rate) < 0.01 ? 'speed-cell-active' : ''}`}
                        onClick={() => void mpvSet('speed', String(rate))}
                      >
                        <div className="speed-rate">{rate}×</div>
                        <div className="speed-detail">{rate === 1 ? 'Normal' : `${rate * 100}%`}</div>
                      </button>
                    ))}
                  </div>
                  <div className="prail-note">
                    Pitch is corrected up to 2×. Subtitle timing follows the rate automatically.
                  </div>
                </>
              )}
            </div>
          </div>
        </>
      )}
    </div>
  )
}

function StateChip({
  kicker,
  value,
  open,
  onClick,
}: {
  kicker: string
  value: string
  open: boolean
  onClick: () => void
}) {
  return (
    <button type="button" className={`state-chip ${open ? 'state-chip-open' : ''}`} onClick={onClick}>
      <span className="chip-kicker">{kicker}</span>
      <span className="chip-value ellipsis">{value}</span>
    </button>
  )
}

/**
 * The 6 px transport bar: buffered fill from mpv's demuxer cache, played fill,
 * and a knob that previews a position while dragging and commits one seek on
 * release — scrubbing live would issue a seek per pixel.
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
  const dragging = useRef(false)

  const valueAt = (clientX: number): number | null => {
    const el = track.current
    if (!el) return null
    const rect = el.getBoundingClientRect()
    const ratio = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width))
    return ratio * duration
  }

  const percent = (value: number) => `${Math.min(100, Math.max(0, (value / duration) * 100))}%`

  return (
    <div
      className="scrub"
      ref={track}
      onPointerDown={(e) => {
        const value = valueAt(e.clientX)
        if (value === null) return
        dragging.current = true
        e.currentTarget.setPointerCapture(e.pointerId)
        onPreview(value)
      }}
      onPointerMove={(e) => {
        const value = valueAt(e.clientX)
        setHoverAt(value)
        if (dragging.current && value !== null) onPreview(value)
      }}
      onPointerUp={(e) => {
        const value = valueAt(e.clientX)
        dragging.current = false
        e.currentTarget.releasePointerCapture(e.pointerId)
        if (value !== null) onCommit(value)
      }}
      onPointerLeave={() => setHoverAt(null)}
    >
      {hoverAt !== null && (
        <div className="scrub-bubble" style={{ left: percent(hoverAt) }}>
          {formatClock(hoverAt)}
        </div>
      )}
      <div className="scrub-track">
        <div className="scrub-buffered" style={{ width: percent(bufferedTo) }} />
        <div className="scrub-played" style={{ width: percent(position) }} />
        <div className="scrub-knob" style={{ left: percent(position) }} />
      </div>
    </div>
  )
}

function VolumeBar({ value, onChange }: { value: number; onChange: (value: number) => void }) {
  const track = useRef<HTMLDivElement>(null)
  const dragging = useRef(false)

  const apply = (clientX: number) => {
    const el = track.current
    if (!el) return
    const rect = el.getBoundingClientRect()
    const ratio = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width))
    onChange(Math.round(ratio * 100))
  }

  return (
    <div
      className="vol-track"
      ref={track}
      onPointerDown={(e) => {
        dragging.current = true
        e.currentTarget.setPointerCapture(e.pointerId)
        apply(e.clientX)
      }}
      onPointerMove={(e) => {
        if (dragging.current) apply(e.clientX)
      }}
      onPointerUp={(e) => {
        dragging.current = false
        e.currentTarget.releasePointerCapture(e.pointerId)
      }}
    >
      <div className="vol-fill" style={{ width: `${value}%` }} />
      <div className="vol-knob" style={{ left: `${value}%` }} />
    </div>
  )
}

function TrackRow({
  label,
  detail,
  badge,
  active,
  indent,
  onClick,
  onBadgeClick,
}: {
  label: string
  detail?: string
  badge?: string
  active: boolean
  indent?: boolean
  onClick: () => void
  onBadgeClick?: () => void
}) {
  return (
    <div style={{ display: 'flex', alignItems: 'center' }}>
      <button
        type="button"
        className={`track-row ${active ? 'track-row-active' : ''}`}
        style={indent ? { paddingLeft: 24 } : undefined}
        onClick={onClick}
      >
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="track-label ellipsis">{label}</div>
          {detail && <div className="track-detail ellipsis">{detail}</div>}
        </div>
        {badge && !onBadgeClick && <div className="badge badge-outline">{badge}</div>}
        <div className={`track-dot ${active ? 'track-dot-on' : ''}`} />
      </button>
      {badge && onBadgeClick && (
        <button
          type="button"
          className="icon-btn"
          style={{ width: 26, height: 26, marginLeft: -34, marginRight: 8 }}
          title="Other variants"
          onClick={onBadgeClick}
        >
          <span style={{ font: '700 10px/1 var(--mono)' }}>{badge}</span>
        </button>
      )}
    </div>
  )
}

/** ±step millisecond offset control; clicking the value resets it to zero. */
function Stepper({
  title,
  hint,
  valueMs,
  onChange,
  step,
  limit,
}: {
  title: string
  hint: string
  valueMs: number
  onChange: (value: number) => void
  step: number
  limit: number
}) {
  return (
    <div className="prail-card" style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
      <div style={{ flex: 1 }}>
        <div style={{ fontSize: 13, fontWeight: 700 }}>{title}</div>
        <div style={{ marginTop: 2, fontSize: 11, color: 'var(--text-muted)' }}>{hint}</div>
      </div>
      <div className="stepper">
        <button
          type="button"
          className="stepper-btn"
          aria-label={`${title} earlier`}
          onClick={() => onChange(Math.max(-limit, valueMs - step))}
        >
          −
        </button>
        <button type="button" className="stepper-value" title="Reset" onClick={() => onChange(0)}>
          {valueMs > 0 ? '+' : ''}
          {valueMs} ms
        </button>
        <button
          type="button"
          className="stepper-btn"
          aria-label={`${title} later`}
          onClick={() => onChange(Math.min(limit, valueMs + step))}
        >
          +
        </button>
      </div>
    </div>
  )
}
