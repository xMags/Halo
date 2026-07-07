import { useCallback, useEffect, useRef, useState } from 'react'
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native'
import Slider from '@react-native-community/slider'
import { Ionicons } from '@expo/vector-icons'
import { useLocalSearchParams, useRouter } from 'expo-router'
import * as FileSystem from 'expo-file-system/legacy'
import { VLCPlayer } from 'react-native-vlc-media-player'
import { languageLabel, type Subtitle, type WatchState } from '@halo/core'
import { useAddonSubtitles, useReportWatchState, useWatchStates } from '@/queries'
import { colors, spacing } from '@/theme'
import { SelectSheet, type SelectOption } from '@/components/SelectSheet'

const REPORT_INTERVAL_MS = 15_000
const WATCHED_THRESHOLD = 0.9

interface Track {
  id: number
  name: string
}

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
  const playerRef = useRef<VLCPlayer>(null)
  const isLocal = params.uri.startsWith('file://')

  const [paused, setPaused] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [error, setError] = useState(false)
  const [controlsVisible, setControlsVisible] = useState(true)
  const [position, setPosition] = useState(0) // fraction 0..1
  const [durationSec, setDurationSec] = useState(0)
  const [audioTracks, setAudioTracks] = useState<Track[]>([])
  const [textTracks, setTextTracks] = useState<Track[]>([])
  const [audioTrack, setAudioTrack] = useState<number | undefined>(undefined)
  const [textTrack, setTextTrack] = useState<number | undefined>(undefined)
  const [subtitleUri, setSubtitleUri] = useState<string | undefined>(params.subtitleUri)
  const [activeExternalSub, setActiveExternalSub] = useState<string | null>(
    params.subtitleUri ? 'downloaded' : null,
  )
  const [audioSheetOpen, setAudioSheetOpen] = useState(false)
  const [subsSheetOpen, setSubsSheetOpen] = useState(false)

  // External subtitles only make sense for remote playback — downloads carry
  // their chosen subtitle file with them.
  const { data: externalSubs } = useAddonSubtitles({
    type: params.type,
    videoId: params.videoId,
    streamUrl: isLocal ? undefined : params.uri,
    filename: params.filename,
    videoSize: params.videoSize ? Number(params.videoSize) : undefined,
  })

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

  const resumeAppliedRef = useRef(false)
  const onLoad = (info: {
    duration: number
    audioTracks?: Track[]
    textTracks?: Track[]
  }) => {
    setLoaded(true)
    setDurationSec(normalizeSeconds(info.duration))
    setAudioTracks(info.audioTracks ?? [])
    // VLC reports "Disable" as id -1; keep real tracks only.
    setTextTracks((info.textTracks ?? []).filter((t) => t.id >= 0))

    if (!resumeAppliedRef.current) {
      resumeAppliedRef.current = true
      const prior = (watchStates ?? []).find((s) => s.videoId === params.videoId)
      const total = normalizeSeconds(info.duration)
      if (prior && !prior.watched && total > 0 && prior.positionSec > 30) {
        const fraction = prior.positionSec / total
        if (fraction < 0.95) playerRef.current?.seek(fraction)
      }
    }
  }

  const onProgress = (event: { position: number; duration: number; currentTime: number }) => {
    setPosition(event.position)
    const total = normalizeSeconds(event.duration)
    setDurationSec(total)
    progressRef.current = { positionSec: normalizeSeconds(event.currentTime), durationSec: total }
  }

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
    ...(externalSubs ?? []).map((sub, index) => ({
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
      const sub = (externalSubs ?? [])[index]
      if (sub) void selectExternalSub(sub, key)
    }
  }

  const seekBy = (deltaSec: number) => {
    if (durationSec === 0) return
    const fraction = Math.max(0, Math.min(1, position + deltaSec / durationSec))
    playerRef.current?.seek(fraction)
  }

  return (
    <View style={styles.container}>
      <Pressable style={styles.videoArea} onPress={() => setControlsVisible((v) => !v)}>
        <VLCPlayer
          ref={playerRef}
          style={styles.video}
          source={{ uri: params.uri, initOptions: ['--sub-text-scale=100'] }}
          paused={paused}
          autoplay
          audioTrack={audioTrack}
          textTrack={textTrack}
          subtitleUri={subtitleUri}
          playInBackground
          autoAspectRatio
          resizeMode="contain"
          onLoad={onLoad}
          onProgress={onProgress}
          onError={() => setError(true)}
          onEnd={() => {
            reportNow()
            router.back()
          }}
        />
        {!loaded && !error ? (
          <View style={styles.overlayCenter}>
            <ActivityIndicator size="large" color={colors.accent} />
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
      </Pressable>

      {controlsVisible && !error ? (
        <View style={styles.controls} pointerEvents="box-none">
          <View style={styles.topBar}>
            <Pressable onPress={() => router.back()} hitSlop={8}>
              <Ionicons name="chevron-down" size={26} color={colors.text} />
            </Pressable>
            <Text style={styles.title} numberOfLines={1}>
              {params.title}
            </Text>
            <View style={styles.topActions}>
              <Pressable onPress={() => setAudioSheetOpen(true)} hitSlop={8} disabled={audioTracks.length === 0}>
                <Ionicons
                  name="musical-notes"
                  size={22}
                  color={audioTracks.length > 0 ? colors.text : colors.textDim}
                />
              </Pressable>
              <Pressable onPress={() => setSubsSheetOpen(true)} hitSlop={8}>
                <Ionicons name="chatbox-ellipses" size={22} color={colors.text} />
              </Pressable>
            </View>
          </View>

          <View style={styles.centerControls}>
            <Pressable onPress={() => seekBy(-10)} hitSlop={12}>
              <Ionicons name="play-back" size={34} color={colors.text} />
            </Pressable>
            <Pressable onPress={() => setPaused((p) => !p)} hitSlop={12}>
              <Ionicons name={paused ? 'play' : 'pause'} size={52} color={colors.text} />
            </Pressable>
            <Pressable onPress={() => seekBy(30)} hitSlop={12}>
              <Ionicons name="play-forward" size={34} color={colors.text} />
            </Pressable>
          </View>

          <View style={styles.bottomBar}>
            <Text style={styles.time}>{formatTime(position * durationSec)}</Text>
            <Slider
              style={styles.slider}
              value={position}
              minimumValue={0}
              maximumValue={1}
              minimumTrackTintColor={colors.accent}
              maximumTrackTintColor={colors.surfaceHigh}
              thumbTintColor={colors.accent}
              onSlidingComplete={(fraction) => playerRef.current?.seek(fraction)}
            />
            <Text style={styles.time}>{formatTime(durationSec)}</Text>
          </View>
        </View>
      ) : null}

      <SelectSheet
        visible={audioSheetOpen}
        title="Audio track"
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
        options={subtitleOptions}
        onSelect={onSubtitleSelect}
        onClose={() => setSubsSheetOpen(false)}
      />
    </View>
  )
}

/**
 * The VLC bridge has historically emitted milliseconds while its typings say
 * seconds. Nothing we play is 14+ hours, so values above that are ms.
 */
function normalizeSeconds(value: number): number {
  return value > 50_000 ? value / 1000 : value
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
  video: {
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
  errorText: {
    color: colors.text,
    fontSize: 15,
  },
  errorBack: {
    backgroundColor: colors.accent,
    borderRadius: 8,
    paddingHorizontal: spacing.md,
    paddingVertical: 8,
  },
  errorBackText: {
    color: colors.background,
    fontWeight: '700',
  },
  controls: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(0,0,0,0.35)',
    justifyContent: 'space-between',
  },
  topBar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    paddingHorizontal: spacing.md,
    paddingTop: spacing.md,
  },
  title: {
    flex: 1,
    color: colors.text,
    fontSize: 15,
    fontWeight: '600',
  },
  topActions: {
    flexDirection: 'row',
    gap: spacing.md,
  },
  centerControls: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.xl,
  },
  bottomBar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    paddingHorizontal: spacing.md,
    paddingBottom: spacing.lg,
  },
  slider: {
    flex: 1,
  },
  time: {
    color: colors.text,
    fontSize: 12,
    fontVariant: ['tabular-nums'],
  },
})
