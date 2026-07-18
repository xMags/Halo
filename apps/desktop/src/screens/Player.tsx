import {
  languageMatches,
  type MetaVideo,
  type NextEpisodeResult,
  type WatchState,
} from '@halo/core'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  mpvCmd,
  mpvGet,
  mpvObserve,
  mpvSet,
  mpvUnobserveAll,
  onMpvEvent,
  onMpvProp,
} from '../mpv'
import { useQueryClient } from '@tanstack/react-query'
import { getClient } from '../api'
import { useNav, type PlayerParams } from '../nav'
import { sortSubtitlesByPreference, useAddonSubtitles, useReportWatchState } from '../queries'
import { useSettings, useSettingsLoaded } from '../settings'

/** Watch-state cadence and thresholds — identical to mobile's player. */
const REPORT_INTERVAL_MS = 15_000
const WATCHED_THRESHOLD = 0.9
const CONTROLS_HIDE_DELAY_MS = 3_000
const NEXT_EPISODE_TIMEOUT_MS = 15_000
const UP_NEXT_COUNTDOWN_SEC = 5

interface MpvTrack {
  id: number
  type: string
  title?: string
  lang?: string
  selected?: boolean
  external?: boolean
}

export function Player(params: PlayerParams) {
  const { pop, replace } = useNav()
  const queryClient = useQueryClient()
  const report = useReportWatchState()
  const settings = useSettings()
  const settingsLoaded = useSettingsLoaded()

  const [position, setPosition] = useState(0)
  const [duration, setDuration] = useState(0)
  const [paused, setPaused] = useState(false)
  const [buffering, setBuffering] = useState(false)
  const [volume, setVolume] = useState(100)
  const [tracks, setTracks] = useState<MpvTrack[]>([])
  const [fileLoaded, setFileLoaded] = useState(false)
  const [subPanelOpen, setSubPanelOpen] = useState(false)
  const [controlsVisible, setControlsVisible] = useState(true)
  const [fullscreen, setFullscreen] = useState(false)
  const [playerError, setPlayerError] = useState<string | null>(null)
  /** Non-null while the user drags the seekbar; committed as one seek on release. */
  const [dragValue, setDragValue] = useState<number | null>(null)

  const subTracks = useMemo(() => tracks.filter((t) => t.type === 'sub'), [tracks])

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
      // Unparseable track list — panel just shows external subs.
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
      await mpvObserve('eof-reached', 'flag')

      unlisteners.push(
        await onMpvProp(({ name, value }) => {
          if (name === 'time-pos' && typeof value === 'number') {
            progressRef.current.positionSec = value
            setPosition(value)
          } else if (name === 'duration' && typeof value === 'number') {
            progressRef.current.durationSec = value
            setDuration(value)
          } else if (name === 'pause' && typeof value === 'boolean') {
            setPaused(value)
          } else if (name === 'paused-for-cache' && typeof value === 'boolean') {
            setBuffering(value)
          } else if (name === 'volume' && typeof value === 'number') {
            setVolume(value)
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

  // Controls auto-hide while playing.
  const hideTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
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

  const episodeTag = (video: MetaVideo) =>
    video.season != null && video.episode != null ? `S${video.season}E${video.episode}` : null
  const nextEpisodeTitle = (video: MetaVideo) => {
    const show = params.showName ?? params.title
    const tag = episodeTag(video)
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
        ...(episodeTag(video) ? { episodeLabel: episodeTag(video)! } : {}),
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
    const tag = episodeTag(video)
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

  // Desktop staples: space, arrows, F, Esc.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement) return
      poke()
      if (e.code === 'Space') togglePause()
      else if (e.code === 'ArrowLeft') seekBy(-10)
      else if (e.code === 'ArrowRight') seekBy(10)
      else if (e.code === 'KeyF') void toggleFullscreen()
      else if (e.code === 'Escape') back()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [togglePause, seekBy, toggleFullscreen, back, poke])

  const selectEmbeddedSub = (id: number | 'no') => {
    void mpvSet('sid', String(id)).then(refreshTracks)
  }
  const addExternalSub = (url: string, lang: string) => {
    // sub-add with `select` loads and activates in one step; only valid after
    // file-loaded (rc=-12 before), which holds — the panel opens during playback.
    void mpvCmd('sub-add', url, 'select', lang, lang).then(refreshTracks)
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

  // Subtitles: an embedded track matching the preference wins; otherwise the
  // best external match once the addon fan-out lands. mpv's own default-track
  // pick stays if the user has no preference.
  const subLangAppliedRef = useRef(false)
  useEffect(() => {
    if (subLangAppliedRef.current || !fileLoaded || !settingsLoaded) return
    const pref = settings.preferredSubtitleLang
    if (!pref) return
    const embedded = subTracks.find((t) => !t.external && t.lang && languageMatches(t.lang, pref))
    if (embedded) {
      subLangAppliedRef.current = true
      void mpvSet('sid', String(embedded.id)).then(refreshTracks)
      return
    }
    if (!subs.data) return // fan-out pending — don't conclude "no match" yet
    const external = subs.data.groups
      .flatMap((g) => g.subtitles)
      .find((s) => languageMatches(s.lang, pref))
    if (!external) return
    subLangAppliedRef.current = true
    addExternalSub(external.url, external.lang)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [subTracks, subs.data, fileLoaded, settingsLoaded, settings.preferredSubtitleLang])

  const activeSub = subTracks.find((t) => t.selected)
  const fmt = (s: number) =>
    `${Math.floor(s / 3600) > 0 ? `${Math.floor(s / 3600)}:` : ''}${String(Math.floor((s % 3600) / 60)).padStart(2, '0')}:${String(Math.floor(s % 60)).padStart(2, '0')}`

  const subGroups = useMemo(
    () =>
      (subs.data?.groups ?? [])
        .map((g) => ({
          ...g,
          subtitles: sortSubtitlesByPreference(g.subtitles, settings.preferredSubtitleLang),
        }))
        .filter((g) => g.subtitles.length > 0),
    [subs.data, settings.preferredSubtitleLang],
  )

  return (
    <div
      style={{ height: '100%', position: 'relative', cursor: controlsVisible ? 'default' : 'none' }}
      onMouseMove={poke}
      onDoubleClick={() => void toggleFullscreen()}
      onClick={(e) => {
        if (e.target === e.currentTarget) togglePause()
      }}
    >
      {playerError && (
        <div className="player-pill" style={{ top: 72 }}>
          {playerError}
        </div>
      )}
      {buffering && <div className="player-pill">Buffering…</div>}

      <div className={`player-top ${controlsVisible ? '' : 'player-hidden'}`}>
        <button className="btn btn-glass" type="button" onClick={back}>
          ← Back
        </button>
        <div>
          <div className="t-callout">{params.showName ?? params.title}</div>
          {params.episodeLabel && (
            <div className="t-caption">
              {params.episodeLabel} · {params.title}
            </div>
          )}
        </div>
      </div>

      <div className={`player-bottom ${controlsVisible ? '' : 'player-hidden'}`}>
        <input
          type="range"
          className="seekbar"
          min={0}
          max={Math.max(duration, 1)}
          step={1}
          value={dragValue ?? Math.min(position, duration)}
          onChange={(e) => setDragValue(Number(e.target.value))}
          onPointerUp={() => {
            if (dragValue !== null) {
              seekTo(dragValue)
              setPosition(dragValue)
              setDragValue(null)
            }
          }}
        />
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 8 }}>
          <button className="btn btn-glass" type="button" onClick={togglePause}>
            {paused ? '▶' : '⏸'}
          </button>
          <button className="btn btn-glass" type="button" onClick={() => seekBy(-10)}>
            −10s
          </button>
          <button className="btn btn-glass" type="button" onClick={() => seekBy(10)}>
            +10s
          </button>
          <span className="t-caption" style={{ minWidth: 110 }}>
            {fmt(position)} / {fmt(duration)}
          </span>
          <span style={{ flex: 1 }} />
          <input
            type="range"
            className="seekbar"
            style={{ width: 110 }}
            min={0}
            max={100}
            value={Math.min(volume, 100)}
            onChange={(e) => void mpvSet('volume', e.target.value)}
            title="Volume"
          />
          <button
            className="btn btn-glass"
            type="button"
            onClick={() => setSubPanelOpen((v) => !v)}
            style={activeSub ? { borderColor: 'var(--accent)' } : undefined}
          >
            Subtitles
          </button>
          <button className="btn btn-glass" type="button" onClick={() => void toggleFullscreen()}>
            {fullscreen ? 'Exit full screen' : 'Full screen'}
          </button>
        </div>
      </div>

      {subPanelOpen && (
        <div className="sub-panel" onMouseMove={poke}>
          <div className="t-heading" style={{ marginBottom: 8 }}>
            Subtitles
          </div>
          {subs.data && !subs.data.hashMatched && (
            <div className="t-caption" style={{ color: 'var(--gold)', marginBottom: 8 }}>
              Couldn&apos;t fingerprint this stream — results may be inaccurate.
            </div>
          )}
          <button
            type="button"
            className="sub-row"
            style={!activeSub ? { color: 'var(--accent)' } : undefined}
            onClick={() => selectEmbeddedSub('no')}
          >
            Off
          </button>
          {subTracks.map((t) => (
            <button
              key={t.id}
              type="button"
              className="sub-row"
              style={t.selected ? { color: 'var(--accent)' } : undefined}
              onClick={() => selectEmbeddedSub(t.id)}
            >
              {t.title ?? t.lang ?? `Track ${t.id}`}
              {t.lang && t.title ? ` (${t.lang})` : ''}
              {t.external ? ' · external' : ''}
            </button>
          ))}
          {subGroups.map((group) => (
            <div key={group.addonId} style={{ marginTop: 10 }}>
              <div className="t-overline">{group.addonName}</div>
              {group.subtitles.slice(0, 25).map((sub) => (
                <button
                  key={sub.id}
                  type="button"
                  className="sub-row"
                  onClick={() => addExternalSub(sub.url, sub.lang)}
                >
                  {sub.lang} · {sub.id}
                </button>
              ))}
            </div>
          ))}
          {subs.isLoading && <div className="t-caption">Searching addons…</div>}
          {subs.data && subGroups.length === 0 && !subs.isLoading && (
            <div className="t-caption">No external subtitles found.</div>
          )}
        </div>
      )}

      {upNextVisible && nextEpisode?.video && (
        <div className="upnext-overlay">
          <div className="t-overline">Up next</div>
          <div className="t-title" style={{ textAlign: 'center', maxWidth: 560 }}>
            {nextEpisodeTitle(nextEpisode.video)}
          </div>
          {(nextEpisode.video.title ?? nextEpisode.video.name) && (
            <div className="t-caption">{nextEpisode.video.title ?? nextEpisode.video.name}</div>
          )}
          <div className="t-caption" style={{ marginTop: 4 }}>
            Playing in {Math.max(upNextSeconds, 0)} s
          </div>
          <div style={{ display: 'flex', gap: 12, marginTop: 14 }}>
            <button className="btn btn-glass" type="button" onClick={() => advanceOnce(back)}>
              Cancel
            </button>
            <button className="btn btn-primary" type="button" onClick={playNextEpisode}>
              ▶ Play now
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
