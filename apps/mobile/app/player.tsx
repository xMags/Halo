import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ActivityIndicator, Platform, Pressable, StyleSheet, Text, useWindowDimensions, View } from 'react-native'
import Slider from '@react-native-community/slider'
import { Ionicons } from '@expo/vector-icons'
import { useLocalSearchParams, useRouter } from 'expo-router'
import { useSafeAreaInsets } from 'react-native-safe-area-context'
import { BlurView } from 'expo-blur'
import { LinearGradient } from 'expo-linear-gradient'
import { NavigationBar } from 'expo-navigation-bar'
import { setStatusBarHidden } from 'expo-status-bar'
import LibVlcPlayerModule from 'expo-libvlc-player'
import {
  LANGUAGE_OPTIONS,
  languageLabel,
  languageMatches,
  type Subtitle,
  type SubtitleOutline,
  type WatchState,
} from '@halo/core'
import { sortSubtitlesByPreference, useAddonSubtitles, useReportWatchState, useWatchStates } from '@/queries'
import { getDownload } from '@/downloads'
import { ensureLocalSubtitle, listLocalSubtitles, subtitleFileName } from '@/subtitleFiles'
import { useSettings, useSettingsLoaded, useUpdateSettings } from '@/settings'
import { clamp01 } from '@/format'
import { colors, radius, spacing } from '@/theme'
import { SelectSheet } from '@/components/SelectSheet'
import { SubtitlesSheet, type SubtitleLanguageGroup, type SubtitleVariant } from '@/components/SubtitlesSheet'
import { PlayerGestureLayer } from '@/components/PlayerGestureLayer'
import PlayerVideo from '@/components/PlayerVideo'
import type {
  PlayerLoadInfo,
  PlayerProgress,
  PlayerTrack,
  PlayerVideoHandle,
  VideoFitMode,
} from '@/components/PlayerVideo.types'

const REPORT_INTERVAL_MS = 15_000
const WATCHED_THRESHOLD = 0.9
const CONTROLS_HIDE_DELAY_MS = 3_000
const NOTICE_HIDE_DELAY_MS = 1_200
const BUFFERING_MESSAGE_DELAY_MS = 5_000
const PLAYBACK_RATES = [0.5, 0.75, 1, 1.25, 1.5, 2] as const
const SUBTITLE_SCALES = [75, 100, 125, 150] as const

const SUBTITLE_OUTLINES: ReadonlyArray<{ key: SubtitleOutline; label: string }> = [
  { key: 'none', label: 'None' },
  { key: 'thin', label: 'Thin' },
  { key: 'normal', label: 'Normal' },
  { key: 'thick', label: 'Thick' },
]

// Device PiP support never changes at runtime; ask the native module once.
const PIP_SUPPORTED = LibVlcPlayerModule.isPictureInPictureSupported()

/**
 * VLC's `:freetype-font` resolves against the platform font provider, so the
 * choices are platform family names. `family: undefined` = platform default
 * (sans-serif-condensed on Android, VLC's own default on iOS).
 */
const SUBTITLE_FONTS: ReadonlyArray<{ label: string; family?: string }> =
  Platform.OS === 'ios'
    ? [
        { label: 'Default' },
        { label: 'Helvetica', family: 'Helvetica Neue' },
        { label: 'Avenir', family: 'Avenir Next' },
        { label: 'Georgia', family: 'Georgia' },
        { label: 'Menlo', family: 'Menlo' },
      ]
    : [
        { label: 'Default' },
        { label: 'Sans', family: 'sans-serif' },
        { label: 'Serif', family: 'serif' },
        { label: 'Mono', family: 'monospace' },
      ]

export default function PlayerScreen() {
  const params = useLocalSearchParams<{
    uri: string
    videoId: string
    itemId: string
    type: string
    title: string
    subtitleUri?: string
    filename?: string
    videoSize?: string
  }>()
  const router = useRouter()
  const insets = useSafeAreaInsets()
  const playerRef = useRef<PlayerVideoHandle>(null)
  const isLocal = params.uri.startsWith('file://')
  // This screen is landscape-locked (see the root Stack options). Opening it
  // with the phone held portrait mounts mid-forced-rotation, and a VLC player
  // created with portrait bounds keeps its portrait placement — the video
  // renders as a corner strip until a manual rotation. Wait out the flip.
  const { width: windowWidth, height: windowHeight } = useWindowDimensions()
  const isLandscape = windowWidth > windowHeight

  const [paused, setPaused] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [error, setError] = useState(false)
  const [controlsVisible, setControlsVisible] = useState(true)
  const [position, setPosition] = useState(0) // fraction 0..1
  const [durationSec, setDurationSec] = useState(0)
  const [audioTracks, setAudioTracks] = useState<PlayerTrack[]>([])
  const [textTracks, setTextTracks] = useState<PlayerTrack[]>([])
  const [audioTrack, setAudioTrack] = useState<number | undefined>(undefined)
  const [textTrack, setTextTrack] = useState<number | undefined>(undefined)
  const [subtitleUri, setSubtitleUri] = useState<string | undefined>(params.subtitleUri)
  const [activeExternalSub, setActiveExternalSub] = useState<string | null>(
    params.subtitleUri ? 'downloaded' : null,
  )
  const [audioSheetOpen, setAudioSheetOpen] = useState(false)
  const [subsSheetOpen, setSubsSheetOpen] = useState(false)
  const [speedSheetOpen, setSpeedSheetOpen] = useState(false)
  const [subtitleDelayMs, setSubtitleDelayMs] = useState(0)
  const [buffering, setBuffering] = useState(false)
  const [bufferingStalled, setBufferingStalled] = useState(false)
  const [locked, setLocked] = useState(false)
  const [unlockVisible, setUnlockVisible] = useState(false)
  const [sliderActive, setSliderActive] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)

  // Persisted player preferences read straight from settings — the optimistic
  // cache write in useUpdateSettings makes changes apply instantly, and there
  // is no local mirror for a background refetch to fight with. The player
  // mounts only after settings settle (subtitle scale/font are creation-time
  // VLC options; hydrating late would rebuild the native player mid-stream).
  const settings = useSettings()
  const settingsLoaded = useSettingsLoaded()
  const updateSettings = useUpdateSettings()
  const fitMode: VideoFitMode = settings.videoFitMode ?? 'contain'
  const subtitleScalePercent = settings.subtitleScalePercent ?? 100
  const subtitleFontFamily = settings.subtitleFontFamily
  const subtitleOutline = settings.subtitleOutline ?? 'normal'
  const subtitleShadow = settings.subtitleShadow ?? true
  const playbackRate = settings.playbackRate ?? 1

  useEffect(() => {
    setStatusBarHidden(true, 'fade')
    if (Platform.OS === 'android') {
      NavigationBar.setStyle('auto')
      NavigationBar.setHidden(true)
    }
    return () => {
      setStatusBarHidden(false, 'fade')
      if (Platform.OS === 'android') NavigationBar.setHidden(false)
    }
  }, [])

  useEffect(() => {
    if (!buffering) {
      setBufferingStalled(false)
      return
    }
    const timer = setTimeout(() => setBufferingStalled(true), BUFFERING_MESSAGE_DELAY_MS)
    return () => clearTimeout(timer)
  }, [buffering])

  const hideNotice = useCallback(() => setNotice(null), [])
  const noticeTimer = useHideTimer(NOTICE_HIDE_DELAY_MS, hideNotice)
  const showNotice = useCallback(
    (message: string) => {
      setNotice(message)
      noticeTimer.arm()
    },
    [noticeTimer],
  )

  const hideUnlock = useCallback(() => setUnlockVisible(false), [])
  const unlockTimer = useHideTimer(CONTROLS_HIDE_DELAY_MS, hideUnlock)
  const revealUnlock = useCallback(() => {
    setUnlockVisible(true)
    unlockTimer.arm()
  }, [unlockTimer])

  useEffect(() => {
    if (locked) revealUnlock()
    else {
      unlockTimer.cancel()
      setUnlockVisible(false)
    }
    return unlockTimer.cancel
  }, [locked, revealUnlock, unlockTimer])

  const hideControls = useCallback(() => setControlsVisible(false), [])
  const controlsTimer = useHideTimer(CONTROLS_HIDE_DELAY_MS, hideControls)
  const cancelControlsHide = controlsTimer.cancel

  const armControlsHide = useCallback(() => {
    controlsTimer.cancel()
    if (!loaded || paused || error || locked || sliderActive || audioSheetOpen || subsSheetOpen || speedSheetOpen) return
    controlsTimer.arm()
  }, [audioSheetOpen, controlsTimer, error, loaded, locked, paused, sliderActive, speedSheetOpen, subsSheetOpen])

  useEffect(() => {
    if (controlsVisible) armControlsHide()
    else controlsTimer.cancel()
    return controlsTimer.cancel
  }, [armControlsHide, controlsTimer, controlsVisible])

  const toggleControls = useCallback(() => {
    if (locked) return
    if (controlsVisible) {
      controlsTimer.cancel()
      setControlsVisible(false)
      return
    }
    setControlsVisible(true)
    armControlsHide()
  }, [armControlsHide, controlsTimer, controlsVisible, locked])

  // Local files hash from disk, remote streams via range requests — either way
  // the addon gets videoHash/videoSize for exact-match results.
  const { data: subtitleGroups } = useAddonSubtitles({
    type: params.type,
    videoId: params.videoId,
    streamUrl: isLocal ? undefined : params.uri,
    localFileUri: isLocal ? params.uri : undefined,
    filename: params.filename,
    videoSize: params.videoSize ? Number(params.videoSize) : undefined,
  })
  const sortedSubs = useMemo(
    () =>
      sortSubtitlesByPreference(
        (subtitleGroups ?? []).flatMap((g) => g.subtitles),
        settings.preferredSubtitleLang,
      ),
    [subtitleGroups, settings.preferredSubtitleLang],
  )
  /** Selection keys are stable per (video, sub) — they double as filenames. */
  const externalByKey = useMemo(() => {
    const map = new Map<string, Subtitle>()
    for (const group of subtitleGroups ?? []) {
      for (const sub of group.subtitles) map.set(`external:${subtitleFileName(params.videoId, sub)}`, sub)
    }
    return map
  }, [subtitleGroups, params.videoId])

  // Which subtitle files are already on device — drives the "local" markers.
  const [localSubs, setLocalSubs] = useState<Set<string>>(new Set())
  useEffect(() => {
    let cancelled = false
    void listLocalSubtitles(params.videoId).then((names) => {
      if (!cancelled) setLocalSubs(names)
    })
    return () => {
      cancelled = true
    }
  }, [params.videoId])

  const { data: watchStates } = useWatchStates()
  const report = useReportWatchState()

  // Live values in refs so reporting doesn't reset the interval on every tick.
  const progressRef = useRef({ positionSec: 0, durationSec: 0 })
  const reportNow = useCallback(() => {
    const { positionSec, durationSec: total } = progressRef.current
    if (total < 60 || positionSec < 5) return
    const state: WatchState = {
      videoId: params.videoId,
      itemId: params.itemId,
      positionSec: Math.floor(positionSec),
      durationSec: Math.floor(total),
      watched: positionSec / total >= WATCHED_THRESHOLD,
      updatedAt: Date.now(),
    }
    report.mutate([state])
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params.videoId, params.itemId])

  useEffect(() => {
    const interval = setInterval(reportNow, REPORT_INTERVAL_MS)
    return () => {
      clearInterval(interval)
      reportNow()
    }
  }, [reportNow])

  // Tracks can arrive before onLoad and keep growing after it (network
  // streams add elementary streams as they are discovered).
  const audioLangAppliedRef = useRef(false)
  const onTracks = (audio: PlayerTrack[], text: PlayerTrack[]) => {
    setAudioTracks(audio)
    setTextTracks(text)

    // Default audio language: VLC names tracks like "Track 1 - [English]".
    if (!audioLangAppliedRef.current && audio.length > 0 && settings.preferredAudioLang) {
      audioLangAppliedRef.current = true
      const match = audio.find((t) => trackMatchesLanguage(t.name, settings.preferredAudioLang!))
      if (match) setAudioTrack(match.id)
    }
  }

  const resumeAppliedRef = useRef(false)
  const onLoad = (info: PlayerLoadInfo) => {
    setLoaded(true)
    setDurationSec(info.durationSec)

    if (!resumeAppliedRef.current) {
      resumeAppliedRef.current = true
      const prior = (watchStates ?? []).find((s) => s.videoId === params.videoId)
      if (prior && !prior.watched && info.durationSec > 0 && prior.positionSec > 30) {
        const fraction = prior.positionSec / info.durationSec
        if (fraction < 0.95) playerRef.current?.seek(fraction)
      }
    }
  }

  const onProgress = (event: PlayerProgress) => {
    setPosition(event.position)
    setDurationSec(event.durationSec)
    progressRef.current = { positionSec: event.currentTimeSec, durationSec: event.durationSec }
  }

  // Default subtitles: prefer an embedded track in the chosen language, else
  // the best external match. Runs once per playback, and never overrides a
  // subtitle that came bundled with a download.
  const autoSubApplied = useRef(false)
  useEffect(() => {
    if (autoSubApplied.current || !loaded || subtitleGroups === undefined) return
    if (!settings.preferredSubtitleLang || params.subtitleUri) {
      autoSubApplied.current = true
      return
    }
    autoSubApplied.current = true
    const embedded = textTracks.find((t) => trackMatchesLanguage(t.name, settings.preferredSubtitleLang!))
    if (embedded) {
      setTextTrack(embedded.id)
      return
    }
    const match = sortedSubs.find((s) => languageMatches(s.lang, settings.preferredSubtitleLang!))
    if (match) void selectExternalSub(match, `external:${subtitleFileName(params.videoId, match)}`)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loaded, subtitleGroups, textTracks, settings.preferredSubtitleLang])

  const selectExternalSub = async (sub: Subtitle, key: string) => {
    try {
      // Persisted, not cached: a sub picked once stays usable next session and
      // offline; re-selecting an on-disk file skips the network entirely.
      const uri = await ensureLocalSubtitle(params.videoId, sub)
      setLocalSubs((prev) => new Set(prev).add(subtitleFileName(params.videoId, sub)))
      setSubtitleUri(uri)
      setTextTrack(undefined)
      setActiveExternalSub(key)
    } catch {
      // Selection quietly failing is worse than a visible no-op; show error text.
      setActiveExternalSub(null)
    }
  }

  // The download-bundled sub's language is only recorded on the download entry.
  const bundledLang = useMemo(
    () => (params.subtitleUri ? getDownload(params.videoId)?.subtitleLang : undefined),
    [params.subtitleUri, params.videoId],
  )

  const subtitleLanguageGroups: SubtitleLanguageGroup[] = useMemo(() => {
    const groups = new Map<string, SubtitleLanguageGroup>()
    const push = (label: string, variant: SubtitleVariant) => {
      const key = label.toLowerCase()
      const group = groups.get(key) ?? { key, label, variants: [] }
      group.variants.push(variant)
      groups.set(key, group)
    }

    if (params.subtitleUri) {
      push(bundledLang ? languageLabel(bundledLang) : 'Other', {
        key: 'downloaded',
        label: 'Bundled with download',
        detail: 'Downloaded',
        local: true,
        selected: activeExternalSub === 'downloaded',
      })
    }
    for (const track of textTracks) {
      push(embeddedTrackLanguage(track.name) ?? 'Other', {
        key: `embedded:${track.id}`,
        label: track.name,
        detail: 'Embedded',
        selected: textTrack === track.id,
      })
    }
    for (const group of subtitleGroups ?? []) {
      for (const sub of group.subtitles) {
        const fileName = subtitleFileName(params.videoId, sub)
        push(languageLabel(sub.lang), {
          key: `external:${fileName}`,
          label: group.addonName,
          detail: sub.id,
          local: localSubs.has(fileName),
          selected: activeExternalSub === `external:${fileName}`,
        })
      }
    }

    // Preferred language first, then alphabetical; unidentified tracks last.
    const preferredKey = settings.preferredSubtitleLang
      ? languageLabel(settings.preferredSubtitleLang).toLowerCase()
      : undefined
    const rank = (g: SubtitleLanguageGroup) => (g.key === preferredKey ? 0 : g.key === 'other' ? 2 : 1)
    return [...groups.values()].sort((a, b) => rank(a) - rank(b) || a.label.localeCompare(b.label))
  }, [
    params.subtitleUri,
    params.videoId,
    bundledLang,
    textTracks,
    subtitleGroups,
    localSubs,
    activeExternalSub,
    textTrack,
    settings.preferredSubtitleLang,
  ])

  // -1 is VLC's explicit "disable text track" value, undefined = never chosen.
  const subtitlesOff = (textTrack === undefined || textTrack === -1) && activeExternalSub === null

  const onSubtitleSelect = (key: string) => {
    if (key === 'off') {
      setTextTrack(-1)
      setSubtitleUri(undefined)
      setActiveExternalSub(null)
    } else if (key === 'downloaded') {
      setSubtitleUri(params.subtitleUri)
      setTextTrack(undefined)
      setActiveExternalSub('downloaded')
    } else if (key.startsWith('embedded:')) {
      setTextTrack(Number(key.slice('embedded:'.length)))
      setSubtitleUri(undefined)
      setActiveExternalSub(null)
    } else {
      const sub = externalByKey.get(key)
      if (sub) void selectExternalSub(sub, key)
    }
  }

  const seekBy = (deltaSec: number) => {
    if (durationSec === 0) return
    const fraction = clamp01(position + deltaSec / durationSec)
    playerRef.current?.seek(fraction)
    armControlsHide()
  }

  const applyFitMode = (mode: VideoFitMode, announce = true) => {
    if (mode !== fitMode) updateSettings.mutate({ videoFitMode: mode })
    if (announce) showNotice(mode === 'cover' ? 'Fill screen' : 'Fit to screen')
    armControlsHide()
  }

  const toggleFitMode = () => applyFitMode(fitMode === 'cover' ? 'contain' : 'cover')

  const selectSubtitleScale = (scale: number) => {
    updateSettings.mutate({ subtitleScalePercent: scale })
    showNotice(`Subtitle size ${scale}%`)
  }

  const selectSubtitleFont = (font: { label: string; family?: string }) => {
    // undefined clears the preference (back to the platform default).
    updateSettings.mutate({ subtitleFontFamily: font.family })
    showNotice(`Subtitle font: ${font.label}`)
  }

  const selectSubtitleOutline = (outline: SubtitleOutline, label: string) => {
    updateSettings.mutate({ subtitleOutline: outline })
    showNotice(`Subtitle outline: ${label}`)
  }

  const selectSubtitleShadow = (on: boolean) => {
    updateSettings.mutate({ subtitleShadow: on })
    showNotice(`Subtitle shadow ${on ? 'on' : 'off'}`)
  }

  const enterPictureInPicture = () => {
    void playerRef.current?.startPictureInPicture().catch(() => {
      showNotice('Picture-in-Picture is unavailable')
    })
  }

  const lockPlayer = () => {
    cancelControlsHide()
    setControlsVisible(false)
    setLocked(true)
  }

  const unlockPlayer = () => {
    setLocked(false)
    setControlsVisible(true)
  }

  const hPad = Math.max(insets.left, insets.right, spacing.lg)

  return (
    <View style={styles.container}>
      <View style={styles.videoArea}>
        {settingsLoaded && isLandscape ? (
          <PlayerVideo
            ref={playerRef}
            // Remote stream URLs can carry raw spaces; both native players reject
            // those (Android validates with java.net.URI, iOS VLCKit is lenient
            // but Android libVLC is not), so encodeURI them. Local file:// paths
            // are passed untouched — they are already valid/sanitized, and
            // encoding could corrupt the path.
            uri={isLocal ? params.uri : encodeURI(params.uri)}
            paused={paused}
            fitMode={fitMode}
            playbackRate={playbackRate}
            subtitleDelayMs={subtitleDelayMs}
            subtitleScalePercent={subtitleScalePercent}
            subtitleFontFamily={subtitleFontFamily}
            subtitleOutline={subtitleOutline}
            subtitleShadow={subtitleShadow}
            audioTrack={audioTrack}
            textTrack={textTrack}
            subtitleUri={subtitleUri}
            onLoad={onLoad}
            onTracks={onTracks}
            onProgress={onProgress}
            onBuffering={setBuffering}
            // The package fires this from the native app-lifecycle hook only on
            // a real background transition (never for Control Center or call
            // banners, which RN's AppState reports as 'inactive').
            onBackground={() => {
              if (paused || error || !PIP_SUPPORTED) return
              void playerRef.current?.startPictureInPicture().catch(() => undefined)
            }}
            onError={() => setError(true)}
            onEnd={() => {
              reportNow()
              router.back()
            }}
          />
        ) : null}
        <PlayerGestureLayer
          disabled={locked || audioSheetOpen || subsSheetOpen || speedSheetOpen || sliderActive}
          onToggleControls={toggleControls}
          onSeek={seekBy}
          onFitModeChange={(mode) => applyFitMode(mode, false)}
          onInteractionStart={cancelControlsHide}
          onInteractionEnd={armControlsHide}
        />
        {!loaded && !error ? (
          <View style={styles.overlayCenter}>
            <ActivityIndicator size="large" color={colors.accent} />
          </View>
        ) : null}
        {loaded && buffering && !error ? (
          <View style={[styles.overlayCenter, styles.bufferingOverlay]} pointerEvents="none">
            <ActivityIndicator size="large" color={colors.accent} />
            {bufferingStalled ? <Text style={styles.bufferingText}>Still loading this stream…</Text> : null}
          </View>
        ) : null}
        {error ? (
          <View style={styles.overlayCenter}>
            <Text style={styles.errorText}>Playback failed — the source may be dead.</Text>
            <Pressable style={styles.errorBack} onPress={() => router.back()}>
              <Text style={styles.errorBackText}>Pick another source</Text>
            </Pressable>
          </View>
        ) : null}
      </View>

      {notice ? (
        <View style={styles.notice} pointerEvents="none">
          <Text style={styles.noticeText}>{notice}</Text>
        </View>
      ) : null}

      {locked ? (
        <Pressable
          accessibilityLabel="Show unlock control"
          style={styles.lockedTouchSurface}
          onPress={revealUnlock}
        />
      ) : null}

      {locked && unlockVisible ? (
        <Pressable style={[styles.unlockButton, { left: hPad }]} onPress={unlockPlayer} hitSlop={12}>
          <Ionicons name="lock-closed" size={20} color={colors.text} />
          <Text style={styles.unlockText}>Unlock controls</Text>
        </Pressable>
      ) : null}

      {controlsVisible && !error && !locked ? (
        <View style={styles.controls} pointerEvents="box-none">
          <LinearGradient
            colors={['rgba(0,0,0,0.65)', 'rgba(0,0,0,0)']}
            style={styles.scrimTop}
            pointerEvents="none"
          />
          <LinearGradient
            colors={['rgba(0,0,0,0)', 'rgba(0,0,0,0.75)']}
            style={styles.scrimBottom}
            pointerEvents="none"
          />

          <View style={[styles.topBar, { paddingLeft: hPad, paddingRight: hPad, paddingTop: insets.top + spacing.sm }]}>
            <Pressable onPress={() => router.back()} hitSlop={8}>
              <Ionicons name="chevron-down" size={28} color={colors.text} />
            </Pressable>
            <Text style={styles.title} numberOfLines={1}>
              {params.title}
            </Text>
            <BlurView intensity={30} tint="dark" style={styles.trackPill}>
              {PIP_SUPPORTED ? (
                <Pressable onPress={enterPictureInPicture} hitSlop={8} style={styles.pillButton}>
                  <Ionicons name="albums-outline" size={20} color={colors.text} />
                </Pressable>
              ) : null}
              <View style={styles.pillDivider} />
              <Pressable onPress={toggleFitMode} hitSlop={8} style={styles.pillButton}>
                <Ionicons name={fitMode === 'cover' ? 'scan-outline' : 'contract-outline'} size={20} color={colors.text} />
              </Pressable>
              <View style={styles.pillDivider} />
              <Pressable onPress={() => setSpeedSheetOpen(true)} hitSlop={8} style={styles.pillButton}>
                <Text style={styles.rateLabel}>{playbackRate}×</Text>
              </Pressable>
              <View style={styles.pillDivider} />
              <Pressable
                onPress={() => setAudioSheetOpen(true)}
                hitSlop={8}
                disabled={audioTracks.length === 0}
                style={styles.pillButton}
              >
                <Ionicons
                  name="musical-notes"
                  size={20}
                  color={audioTracks.length > 0 ? colors.text : colors.textDim}
                />
              </Pressable>
              <View style={styles.pillDivider} />
              <Pressable onPress={() => setSubsSheetOpen(true)} hitSlop={8} style={styles.pillButton}>
                <Ionicons name="chatbox-ellipses" size={20} color={colors.text} />
              </Pressable>
              <View style={styles.pillDivider} />
              <Pressable onPress={lockPlayer} hitSlop={8} style={styles.pillButton}>
                <Ionicons name="lock-open-outline" size={20} color={colors.text} />
              </Pressable>
            </BlurView>
          </View>

          <View style={styles.centerControls}>
            <Pressable onPress={() => seekBy(-10)} hitSlop={12} style={styles.seekButton}>
              <Ionicons name="play-back" size={30} color={colors.text} />
              <Text style={styles.seekLabel}>10</Text>
            </Pressable>
            <Pressable onPress={() => setPaused((p) => !p)} hitSlop={12} style={styles.playPause}>
              <Ionicons name={paused ? 'play' : 'pause'} size={40} color={colors.text} />
            </Pressable>
            <Pressable onPress={() => seekBy(30)} hitSlop={12} style={styles.seekButton}>
              <Ionicons name="play-forward" size={30} color={colors.text} />
              <Text style={styles.seekLabel}>30</Text>
            </Pressable>
          </View>

          <View style={[styles.bottomBar, { paddingLeft: hPad, paddingRight: hPad, paddingBottom: insets.bottom + spacing.sm }]}>
            <Text style={styles.time}>{formatTime(position * durationSec)}</Text>
            <Slider
              style={styles.slider}
              value={position}
              minimumValue={0}
              maximumValue={1}
              minimumTrackTintColor="#ffffff"
              maximumTrackTintColor="rgba(255,255,255,0.3)"
              thumbTintColor="#ffffff"
              onSlidingStart={() => {
                setSliderActive(true)
                cancelControlsHide()
              }}
              onSlidingComplete={(fraction) => {
                playerRef.current?.seek(fraction)
                setSliderActive(false)
                armControlsHide()
              }}
            />
            <Text style={[styles.time, styles.timeTotal]}>{formatTime(durationSec)}</Text>
          </View>
        </View>
      ) : null}

      <SelectSheet
        visible={audioSheetOpen}
        title="Audio"
        description="Language and audio track"
        presentation="side"
        options={audioTracks.map((t) => ({
          key: String(t.id),
          label: t.name,
          selected: audioTrack === t.id,
        }))}
        onSelect={(key) => setAudioTrack(Number(key))}
        onClose={() => setAudioSheetOpen(false)}
      />
      <SubtitlesSheet
        visible={subsSheetOpen}
        title="Subtitles"
        description="Language, variant and appearance"
        groups={subtitleLanguageGroups}
        offSelected={subtitlesOff}
        onSelectOff={() => onSubtitleSelect('off')}
        onSelectVariant={onSubtitleSelect}
        onClose={() => setSubsSheetOpen(false)}
        appearance={
          <>
            <View style={styles.toolRow}>
              <View>
                <Text style={styles.toolLabel}>Subtitle sync</Text>
                <Text style={styles.toolDetail}>Audio timing</Text>
              </View>
              <View style={styles.stepper}>
                <ToolButton icon="remove" onPress={() => setSubtitleDelayMs((value) => Math.max(-5_000, value - 50))} />
                <Pressable style={styles.resetButton} onPress={() => setSubtitleDelayMs(0)}>
                  <Text style={styles.resetText}>{subtitleDelayMs > 0 ? '+' : ''}{subtitleDelayMs} ms</Text>
                </Pressable>
                <ToolButton icon="add" onPress={() => setSubtitleDelayMs((value) => Math.min(5_000, value + 50))} />
              </View>
            </View>
            <View style={styles.toolRow}>
              <View>
                <Text style={styles.toolLabel}>Text size</Text>
                <Text style={styles.toolDetail}>Caption scale</Text>
              </View>
              <View style={styles.chipOptions}>
                {SUBTITLE_SCALES.map((scale) => (
                  <Pressable
                    key={scale}
                    style={[styles.chip, scale === subtitleScalePercent && styles.chipSelected]}
                    onPress={() => selectSubtitleScale(scale)}
                  >
                    <Text style={[styles.chipText, scale === subtitleScalePercent && styles.chipTextSelected]}>{scale}</Text>
                  </Pressable>
                ))}
              </View>
            </View>
            <View style={styles.toolRow}>
              <View>
                <Text style={styles.toolLabel}>Outline</Text>
                <Text style={styles.toolDetail}>Edge thickness</Text>
              </View>
              <View style={styles.chipOptions}>
                {SUBTITLE_OUTLINES.map((o) => (
                  <Pressable
                    key={o.key}
                    style={[styles.chip, subtitleOutline === o.key && styles.chipSelected]}
                    onPress={() => selectSubtitleOutline(o.key, o.label)}
                  >
                    <Text style={[styles.chipText, subtitleOutline === o.key && styles.chipTextSelected]}>{o.label}</Text>
                  </Pressable>
                ))}
              </View>
            </View>
            <View style={styles.toolRow}>
              <View>
                <Text style={styles.toolLabel}>Shadow</Text>
                <Text style={styles.toolDetail}>Drop shadow</Text>
              </View>
              <View style={styles.chipOptions}>
                {[
                  { on: false, label: 'Off' },
                  { on: true, label: 'On' },
                ].map((s) => (
                  <Pressable
                    key={s.label}
                    style={[styles.chip, subtitleShadow === s.on && styles.chipSelected]}
                    onPress={() => selectSubtitleShadow(s.on)}
                  >
                    <Text style={[styles.chipText, subtitleShadow === s.on && styles.chipTextSelected]}>{s.label}</Text>
                  </Pressable>
                ))}
              </View>
            </View>
            <View style={styles.toolColumn}>
              <View>
                <Text style={styles.toolLabel}>Font</Text>
                <Text style={styles.toolDetail}>Caption typeface</Text>
              </View>
              <View style={[styles.chipOptions, styles.chipOptionsWrap]}>
                {SUBTITLE_FONTS.map((font) => {
                  const selected = subtitleFontFamily === font.family
                  return (
                    <Pressable
                      key={font.label}
                      style={[styles.chip, selected && styles.chipSelected]}
                      onPress={() => selectSubtitleFont(font)}
                    >
                      <Text style={[styles.chipText, selected && styles.chipTextSelected]}>{font.label}</Text>
                    </Pressable>
                  )
                })}
              </View>
            </View>
          </>
        }
      />
      <SelectSheet
        visible={speedSheetOpen}
        title="Playback speed"
        description="Adjust playback tempo"
        presentation="side"
        options={PLAYBACK_RATES.map((rate) => ({
          key: String(rate),
          label: `${rate}×`,
          detail: playbackRateDetail(rate),
          selected: playbackRate === rate,
        }))}
        onSelect={(key) => {
          updateSettings.mutate({ playbackRate: Number(key) })
          showNotice(`${key}× speed`)
        }}
        onClose={() => setSpeedSheetOpen(false)}
      />
    </View>
  )
}

/**
 * One re-armable auto-hide timeout: arming replaces any pending run, and the
 * pending run is cleared on unmount. `hide` must be referentially stable.
 */
function useHideTimer(delayMs: number, hide: () => void) {
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const cancel = useCallback(() => {
    if (timer.current === null) return
    clearTimeout(timer.current)
    timer.current = null
  }, [])

  const arm = useCallback(() => {
    cancel()
    timer.current = setTimeout(() => {
      timer.current = null
      hide()
    }, delayMs)
  }, [cancel, delayMs, hide])

  useEffect(() => cancel, [cancel])

  return useMemo(() => ({ arm, cancel }), [arm, cancel])
}

function ToolButton({ icon, onPress }: { icon: 'add' | 'remove'; onPress: () => void }) {
  return (
    <Pressable style={styles.toolButton} onPress={onPress} hitSlop={6}>
      <Ionicons name={icon} size={18} color={colors.text} />
    </Pressable>
  )
}

function playbackRateDetail(rate: number): string {
  if (rate === 1) return 'Normal speed'
  if (rate < 1) return `${Math.round((1 - rate) * 100)}% slower`
  return `${Math.round((rate - 1) * 100)}% faster`
}

/** VLC track names look like "Track 1 - [English]" or just "English". */
function trackMatchesLanguage(trackName: string, code: string): boolean {
  const name = trackName.toLowerCase()
  return name.includes(languageLabel(code).toLowerCase()) || name.includes(`[${code.toLowerCase()}]`)
}

/**
 * Language label for grouping an embedded track, via the same fuzzy name
 * match auto-select trusts. Undefined for names naming no known language
 * (e.g. "Track 1") — those group under "Other".
 */
function embeddedTrackLanguage(trackName: string): string | undefined {
  const match = LANGUAGE_OPTIONS.find(({ code }) => trackMatchesLanguage(trackName, code))
  return match?.label
}

function formatTime(totalSec: number): string {
  if (!Number.isFinite(totalSec) || totalSec <= 0) return '0:00'
  const s = Math.floor(totalSec % 60)
  const m = Math.floor((totalSec / 60) % 60)
  const h = Math.floor(totalSec / 3600)
  const mm = h > 0 ? String(m).padStart(2, '0') : String(m)
  return `${h > 0 ? `${h}:` : ''}${mm}:${String(s).padStart(2, '0')}`
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000',
  },
  videoArea: {
    flex: 1,
  },
  overlayCenter: {
    ...StyleSheet.absoluteFill,
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.md,
  },
  bufferingOverlay: {
    gap: spacing.sm,
  },
  bufferingText: {
    color: colors.text,
    fontSize: 13,
    fontWeight: '600',
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.pill,
    backgroundColor: colors.overlayPill,
  },
  notice: {
    position: 'absolute',
    top: '18%',
    alignSelf: 'center',
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderRadius: radius.pill,
    backgroundColor: colors.overlayPill,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'rgba(255,255,255,0.18)',
  },
  noticeText: { color: colors.text, fontSize: 13, fontWeight: '700' },
  unlockButton: {
    position: 'absolute',
    top: '46%',
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderRadius: radius.pill,
    backgroundColor: colors.overlayPill,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'rgba(255,255,255,0.2)',
  },
  lockedTouchSurface: {
    ...StyleSheet.absoluteFill,
  },
  unlockText: { color: colors.text, fontSize: 13, fontWeight: '700' },
  errorText: {
    color: colors.text,
    fontSize: 15,
  },
  errorBack: {
    backgroundColor: colors.primary,
    borderRadius: radius.sm,
    paddingHorizontal: spacing.md,
    paddingVertical: 8,
  },
  errorBackText: {
    color: colors.onPrimary,
    fontWeight: '700',
  },
  controls: {
    ...StyleSheet.absoluteFill,
    justifyContent: 'space-between',
  },
  scrimTop: { position: 'absolute', top: 0, left: 0, right: 0, height: 120 },
  scrimBottom: { position: 'absolute', bottom: 0, left: 0, right: 0, height: 140 },
  topBar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
  },
  title: {
    flex: 1,
    color: colors.text,
    fontSize: 16,
    fontWeight: '600',
  },
  trackPill: {
    flexDirection: 'row',
    alignItems: 'center',
    borderRadius: radius.pill,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'rgba(255,255,255,0.16)',
    overflow: 'hidden',
  },
  pillButton: { paddingVertical: 8, paddingHorizontal: 14 },
  pillDivider: { width: StyleSheet.hairlineWidth, height: 18, backgroundColor: 'rgba(255,255,255,0.18)' },
  rateLabel: { color: colors.text, fontSize: 12, fontWeight: '800', minWidth: 25, textAlign: 'center' },
  centerControls: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 56,
  },
  seekButton: { alignItems: 'center', justifyContent: 'center' },
  seekLabel: { color: colors.text, fontSize: 11, fontWeight: '700', marginTop: -3 },
  playPause: {
    width: 76,
    height: 76,
    borderRadius: radius.pill,
    backgroundColor: 'rgba(255,255,255,0.16)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  bottomBar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
  },
  slider: {
    flex: 1,
  },
  time: {
    color: colors.text,
    fontSize: 12.5,
    fontWeight: '500',
    fontVariant: ['tabular-nums'],
    minWidth: 44,
  },
  timeTotal: { color: 'rgba(255,255,255,0.7)', textAlign: 'right' },
  toolRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.md,
    padding: spacing.sm,
    borderRadius: radius.md,
    backgroundColor: 'rgba(255,255,255,0.055)',
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'rgba(255,255,255,0.08)',
  },
  toolColumn: {
    gap: spacing.sm,
    padding: spacing.sm,
    borderRadius: radius.md,
    backgroundColor: 'rgba(255,255,255,0.055)',
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'rgba(255,255,255,0.08)',
  },
  toolLabel: { color: colors.text, fontSize: 14, fontWeight: '700' },
  toolDetail: { color: colors.textDim, fontSize: 12, marginTop: 2 },
  stepper: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs },
  toolButton: {
    width: 34,
    height: 34,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.pill,
    backgroundColor: 'rgba(255,255,255,0.1)',
  },
  resetButton: {
    minWidth: 66,
    alignItems: 'center',
    paddingHorizontal: spacing.sm,
    paddingVertical: 8,
    borderRadius: radius.pill,
    backgroundColor: 'rgba(255,255,255,0.07)',
  },
  resetText: { color: colors.text, fontSize: 11.5, fontWeight: '700', fontVariant: ['tabular-nums'] },
  chipOptions: { flexDirection: 'row', flexWrap: 'wrap', flexShrink: 1, justifyContent: 'flex-end', gap: spacing.xs },
  chipOptionsWrap: { justifyContent: 'flex-start' },
  chip: {
    minWidth: 42,
    alignItems: 'center',
    paddingHorizontal: spacing.sm,
    paddingVertical: 8,
    borderRadius: radius.pill,
    backgroundColor: 'rgba(255,255,255,0.08)',
  },
  chipSelected: { backgroundColor: colors.accent },
  chipText: { color: colors.textDim, fontSize: 12, fontWeight: '700' },
  chipTextSelected: { color: colors.onAccent },
})
