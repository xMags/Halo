import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ActivityIndicator, AppState, Platform, Pressable, StyleSheet, Text, View } from 'react-native'
import Slider from '@react-native-community/slider'
import { Ionicons } from '@expo/vector-icons'
import { useLocalSearchParams, useRouter } from 'expo-router'
import { useSafeAreaInsets } from 'react-native-safe-area-context'
import { BlurView } from 'expo-blur'
import { LinearGradient } from 'expo-linear-gradient'
import { NavigationBar } from 'expo-navigation-bar'
import { setStatusBarHidden } from 'expo-status-bar'
import * as FileSystem from 'expo-file-system/legacy'
import LibVlcPlayerModule from 'expo-libvlc-player'
import { languageLabel, languageMatches, type Subtitle, type WatchState } from '@halo/core'
import { sortSubtitlesByPreference, useAddonSubtitles, useReportWatchState, useWatchStates } from '@/queries'
import { useSettings, useUpdateSettings } from '@/settings'
import { colors, radius, spacing } from '@/theme'
import { SelectSheet, type SelectOption } from '@/components/SelectSheet'
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
const BUFFERING_MESSAGE_DELAY_MS = 5_000
const PLAYBACK_RATES = [0.5, 0.75, 1, 1.25, 1.5, 2] as const
const SUBTITLE_SCALES = [75, 100, 125, 150] as const

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
  const [playbackRate, setPlaybackRate] = useState(1)
  const [fitMode, setFitMode] = useState<VideoFitMode>('cover')
  const [subtitleDelayMs, setSubtitleDelayMs] = useState(0)
  const [subtitleScalePercent, setSubtitleScalePercent] = useState(100)
  const [buffering, setBuffering] = useState(false)
  const [bufferingStalled, setBufferingStalled] = useState(false)
  const [locked, setLocked] = useState(false)
  const [unlockVisible, setUnlockVisible] = useState(false)
  const [sliderActive, setSliderActive] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const controlsHideTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const noticeTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const unlockHideTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const settings = useSettings()
  const updateSettings = useUpdateSettings()

  useEffect(() => {
    if (settings.videoFitMode) setFitMode(settings.videoFitMode)
    if (settings.subtitleScalePercent) setSubtitleScalePercent(settings.subtitleScalePercent)
  }, [settings.subtitleScalePercent, settings.videoFitMode])

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

  const showNotice = useCallback((message: string) => {
    if (noticeTimerRef.current !== null) clearTimeout(noticeTimerRef.current)
    setNotice(message)
    noticeTimerRef.current = setTimeout(() => {
      noticeTimerRef.current = null
      setNotice(null)
    }, 1_200)
  }, [])

  useEffect(() => () => {
    if (noticeTimerRef.current !== null) clearTimeout(noticeTimerRef.current)
  }, [])

  const cancelUnlockHide = useCallback(() => {
    if (unlockHideTimerRef.current === null) return
    clearTimeout(unlockHideTimerRef.current)
    unlockHideTimerRef.current = null
  }, [])

  const revealUnlock = useCallback(() => {
    cancelUnlockHide()
    setUnlockVisible(true)
    unlockHideTimerRef.current = setTimeout(() => {
      unlockHideTimerRef.current = null
      setUnlockVisible(false)
    }, CONTROLS_HIDE_DELAY_MS)
  }, [cancelUnlockHide])

  useEffect(() => {
    if (locked) revealUnlock()
    else {
      cancelUnlockHide()
      setUnlockVisible(false)
    }
    return cancelUnlockHide
  }, [cancelUnlockHide, locked, revealUnlock])

  const cancelControlsHide = useCallback(() => {
    if (controlsHideTimerRef.current === null) return
    clearTimeout(controlsHideTimerRef.current)
    controlsHideTimerRef.current = null
  }, [])

  const armControlsHide = useCallback(() => {
    cancelControlsHide()
    if (!loaded || paused || error || locked || sliderActive || audioSheetOpen || subsSheetOpen || speedSheetOpen) return
    controlsHideTimerRef.current = setTimeout(() => {
      controlsHideTimerRef.current = null
      setControlsVisible(false)
    }, CONTROLS_HIDE_DELAY_MS)
  }, [audioSheetOpen, cancelControlsHide, error, loaded, locked, paused, sliderActive, speedSheetOpen, subsSheetOpen])

  useEffect(() => {
    if (controlsVisible) armControlsHide()
    else cancelControlsHide()
    return cancelControlsHide
  }, [armControlsHide, cancelControlsHide, controlsVisible])

  const toggleControls = useCallback(() => {
    if (locked) return
    if (controlsVisible) {
      cancelControlsHide()
      setControlsVisible(false)
      return
    }
    setControlsVisible(true)
    armControlsHide()
  }, [armControlsHide, cancelControlsHide, controlsVisible, locked])

  useEffect(() => {
    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'active' || !loaded || paused || error) return
      void playerRef.current?.startPictureInPicture().catch(() => undefined)
    })
    return () => subscription.remove()
  }, [error, loaded, paused])

  // Local files hash from disk, remote streams via range requests — either way
  // the addon gets videoHash/videoSize for exact-match results.
  const { data: externalSubs } = useAddonSubtitles({
    type: params.type,
    videoId: params.videoId,
    streamUrl: isLocal ? undefined : params.uri,
    localFileUri: isLocal ? params.uri : undefined,
    filename: params.filename,
    videoSize: params.videoSize ? Number(params.videoSize) : undefined,
  })
  const sortedSubs = useMemo(
    () => sortSubtitlesByPreference(externalSubs ?? [], settings.preferredSubtitleLang),
    [externalSubs, settings.preferredSubtitleLang],
  )

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
    if (autoSubApplied.current || !loaded || externalSubs === undefined) return
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
    const index = sortedSubs.findIndex((s) => languageMatches(s.lang, settings.preferredSubtitleLang!))
    if (index >= 0) void selectExternalSub(sortedSubs[index]!, `external:${index}`)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loaded, externalSubs, textTracks, settings.preferredSubtitleLang])

  const selectExternalSub = async (sub: Subtitle, key: string) => {
    try {
      // ASS/SRT are handed to VLC untouched — it renders both natively
      // (ASS with full styling); converting would strip that.
      const extMatch = sub.url.match(/\.(ass|ssa|srt|vtt|sub)(\?|$)/i)
      const ext = extMatch ? extMatch[1]!.toLowerCase() : 'srt'
      const target = `${FileSystem.cacheDirectory}sub-${params.videoId.replace(/[^a-zA-Z0-9._-]/g, '_')}-${key}.${ext}`
      const result = await FileSystem.downloadAsync(sub.url, target)
      setSubtitleUri(result.uri)
      setTextTrack(undefined)
      setActiveExternalSub(key)
    } catch {
      // Selection quietly failing is worse than a visible no-op; show error text.
      setActiveExternalSub(null)
    }
  }

  const subtitleOptions: SelectOption[] = [
    { key: 'off', label: 'Off', selected: textTrack === undefined && activeExternalSub === null },
    ...(params.subtitleUri
      ? [{ key: 'downloaded', label: 'Downloaded subtitle', selected: activeExternalSub === 'downloaded' }]
      : []),
    ...textTracks.map((t) => ({
      key: `embedded:${t.id}`,
      label: t.name,
      detail: 'Embedded',
      selected: textTrack === t.id,
    })),
    ...sortedSubs.map((sub, index) => ({
      key: `external:${index}`,
      label: languageLabel(sub.lang),
      detail: 'OpenSubtitles',
      selected: activeExternalSub === `external:${index}`,
    })),
  ]

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
    } else if (key.startsWith('external:')) {
      const index = Number(key.slice('external:'.length))
      const sub = sortedSubs[index]
      if (sub) void selectExternalSub(sub, key)
    }
  }

  const seekBy = (deltaSec: number) => {
    if (durationSec === 0) return
    const fraction = Math.max(0, Math.min(1, position + deltaSec / durationSec))
    playerRef.current?.seek(fraction)
    armControlsHide()
  }

  const applyFitMode = (mode: VideoFitMode, announce = true) => {
    if (mode !== fitMode) {
      setFitMode(mode)
      updateSettings.mutate({ videoFitMode: mode })
    }
    if (announce) showNotice(mode === 'cover' ? 'Fill screen' : 'Fit to screen')
    armControlsHide()
  }

  const toggleFitMode = () => applyFitMode(fitMode === 'cover' ? 'contain' : 'cover')

  const selectSubtitleScale = (scale: number) => {
    setSubtitleScalePercent(scale)
    updateSettings.mutate({ subtitleScalePercent: scale })
    showNotice(`Subtitle size ${scale}%`)
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
  const pipSupported = LibVlcPlayerModule.isPictureInPictureSupported()

  return (
    <View style={styles.container}>
      <View style={styles.videoArea}>
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
          audioTrack={audioTrack}
          textTrack={textTrack}
          subtitleUri={subtitleUri}
          onLoad={onLoad}
          onTracks={onTracks}
          onProgress={onProgress}
          onBuffering={setBuffering}
          onError={() => setError(true)}
          onEnd={() => {
            reportNow()
            router.back()
          }}
        />
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
          <View style={styles.bufferingOverlay} pointerEvents="none">
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
              {pipSupported ? (
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
      <SelectSheet
        visible={subsSheetOpen}
        title="Subtitles"
        description="Track, timing and appearance"
        presentation="side"
        options={subtitleOptions}
        onSelect={onSubtitleSelect}
        onClose={() => setSubsSheetOpen(false)}
        footer={
          <View style={styles.subtitleTools}>
            <Text style={styles.toolsHeading}>TIMING & APPEARANCE</Text>
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
              <View style={styles.scaleOptions}>
                {SUBTITLE_SCALES.map((scale) => (
                  <Pressable
                    key={scale}
                    style={[styles.scaleButton, scale === subtitleScalePercent && styles.scaleButtonSelected]}
                    onPress={() => selectSubtitleScale(scale)}
                  >
                    <Text style={[styles.scaleText, scale === subtitleScalePercent && styles.scaleTextSelected]}>{scale}</Text>
                  </Pressable>
                ))}
              </View>
            </View>
          </View>
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
          setPlaybackRate(Number(key))
          showNotice(`${key}× speed`)
        }}
        onClose={() => setSpeedSheetOpen(false)}
      />
    </View>
  )
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
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.md,
  },
  bufferingOverlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.sm,
  },
  bufferingText: {
    color: colors.text,
    fontSize: 13,
    fontWeight: '600',
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.pill,
    backgroundColor: 'rgba(0,0,0,0.72)',
  },
  notice: {
    position: 'absolute',
    top: '18%',
    alignSelf: 'center',
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderRadius: radius.pill,
    backgroundColor: 'rgba(5,7,12,0.82)',
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
    backgroundColor: 'rgba(5,7,12,0.78)',
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'rgba(255,255,255,0.2)',
  },
  lockedTouchSurface: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
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
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
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
  subtitleTools: { gap: spacing.sm, paddingBottom: spacing.xs },
  toolsHeading: { color: colors.textDim, fontSize: 10, fontWeight: '800', letterSpacing: 1.1 },
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
  scaleOptions: { flexDirection: 'row', gap: spacing.xs },
  scaleButton: {
    minWidth: 42,
    alignItems: 'center',
    paddingHorizontal: spacing.xs,
    paddingVertical: 8,
    borderRadius: radius.pill,
    backgroundColor: 'rgba(255,255,255,0.08)',
  },
  scaleButtonSelected: { backgroundColor: colors.accent },
  scaleText: { color: colors.textDim, fontSize: 12, fontWeight: '700' },
  scaleTextSelected: { color: colors.onPrimary },
})
