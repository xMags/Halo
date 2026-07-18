import { useEffect, useImperativeHandle, useMemo, useRef } from 'react'
import { Platform, StyleSheet } from 'react-native'
import { LibVlcPlayerView } from 'expo-libvlc-player'
import type { LibVlcPlayerViewRef, MediaInfo, MediaTracks, Slave } from 'expo-libvlc-player'
import type { SubtitleOutline } from '@halo/core'
import type { PlayerTrack, PlayerVideoProps } from './PlayerVideo.types'

/**
 * VLC resolves `--freetype-font` through the platform font provider. On iOS
 * that provider is CoreText, which sees the app-registered bundled fonts
 * (assets/fonts via the expo-font plugin), so the synced family names resolve
 * as-is. Android's libvlc provider only parses /system/etc/fonts.xml — app
 * fonts are unreachable in the prebuilt binary — so the standard families map
 * to the nearest system generic instead of falling back to the default sans.
 * iOS has no "condensed sans" family VLC can resolve, so its default stays
 * VLC's own.
 */
const DEFAULT_FONT = Platform.OS === 'android' ? 'sans-serif-condensed' : undefined
const ANDROID_FAMILY_FALLBACKS: Record<string, string> = {
  Inter: 'sans-serif',
  'Source Serif 4': 'serif',
  'JetBrains Mono': 'monospace',
}

function resolveFontFamily(family: string | undefined): string | undefined {
  if (family === undefined) return DEFAULT_FONT
  if (Platform.OS !== 'android') return family
  return ANDROID_FAMILY_FALLBACKS[family] ?? family
}

/** VLC freetype-outline-thickness presets (0=None, 2=Thin, 4=Normal, 6=Thick). */
const OUTLINE_THICKNESS: Record<SubtitleOutline, number> = {
  none: 0,
  thin: 2,
  normal: 4,
  thick: 6,
}

export default function PlayerVideo({
  ref,
  uri,
  paused,
  fitMode,
  playbackRate,
  subtitleDelayMs,
  subtitleScalePercent,
  subtitleFontFamily,
  subtitleOutline,
  subtitleShadow,
  audioTrack,
  textTrack,
  subtitleUri,
  onLoad,
  onTracks,
  onProgress,
  onBuffering,
  onBackground,
  onError,
  onEnd,
}: PlayerVideoProps) {
  const playerRef = useRef<LibVlcPlayerViewRef>(null)

  useImperativeHandle(ref, () => ({
    seek: (fraction: number) => void playerRef.current?.seek(fraction, 'position'),
    startPictureInPicture: async () => {
      await playerRef.current?.startPictureInPicture()
    },
  }))

  useEffect(() => {
    if (paused) void playerRef.current?.pause()
    else void playerRef.current?.play()
  }, [paused])

  // External subtitles are libvlc "slaves": added once (the native side dedups
  // re-sends by value), auto-selected on add via `selected`, and re-selected
  // later through the spu track id they received. The slave→track-id link is
  // recovered by diffing subtitle track ids across ESAdded events; when one
  // event adds several tracks at once we take the highest new id — slaves are
  // registered after a media's embedded tracks, so they get the later ids.
  const sentSlaves = useRef<Slave[]>([])
  const pendingSlaveUri = useRef<string | null>(null)
  const slaveTrackIds = useRef(new Map<string, number>())
  const knownSpuIds = useRef<Set<number>>(new Set())

  const latestTracks = useRef<{ audio: PlayerTrack[]; text: PlayerTrack[] }>({ audio: [], text: [] })
  const progress = useRef({ position: 0, durationSec: 0, currentTimeSec: 0 })
  const errored = useRef(false)

  // Subtitle scale and font are creation-time VLC options: changing either has
  // to rebuild the native player (the `key` below), then seek back to where
  // playback was. `autoplay={!paused}` keeps a paused player paused across the
  // rebuild — there is no declarative paused prop and the [paused] effect
  // won't re-fire.
  const fontFamily = resolveFontFamily(subtitleFontFamily)
  const playbackConfigKey = `${subtitleScalePercent}|${fontFamily ?? ''}|${subtitleOutline}|${subtitleShadow}`
  const previousPlaybackConfig = useRef(playbackConfigKey)
  const restartPosition = useRef<number | null>(null)
  const restarting = useRef(false)

  if (previousPlaybackConfig.current !== playbackConfigKey) {
    previousPlaybackConfig.current = playbackConfigKey
    restartPosition.current = progress.current.position
    restarting.current = true
    // The new native instance assigns its own track ids: drop the old
    // instance's slave bookkeeping and re-learn the active slave's id from
    // scratch, exactly like a first mount. Stale slaves from earlier
    // selections are not re-sent — only the active one matters.
    sentSlaves.current = []
    slaveTrackIds.current = new Map()
    knownSpuIds.current = new Set()
    pendingSlaveUri.current = null
  }

  if (subtitleUri && !sentSlaves.current.some((slave) => slave.source === subtitleUri)) {
    sentSlaves.current = [...sentSlaves.current, { source: subtitleUri, type: 'subtitle', selected: true }]
    pendingSlaveUri.current = subtitleUri
  }
  const slaves = sentSlaves.current

  // While a freshly-added slave is pending its track id, `selected: true` on
  // the slave itself does the selecting and this resolves to undefined.
  const spuTrack = subtitleUri !== undefined ? slaveTrackIds.current.get(subtitleUri) : textTrack
  const tracks = useMemo(() => ({ audio: audioTrack, subtitle: spuTrack }), [audioTrack, spuTrack])

  // These are libvlc instance options (`--…` CLI form), not per-media options:
  // the freetype text renderer and sub-text-scale are read from the LibVLC /
  // VLCLibrary instance config, so passing them per-media (addOption) is
  // silently ignored. The `instanceOptions` prop routes them to the instance
  // constructor. Booleans use the `--no-…` form — `--freetype-bold=0` is
  // rejected by libvlc's CLI parser and aborts instance creation.
  const instanceOptions = useMemo(() => {
    const opts = [
      `--sub-text-scale=${subtitleScalePercent}`,
      '--no-freetype-bold',
      // Styling mirrors VLC's stock freetype defaults, which is the cross-player
      // consensus readable look (mpv/VLC/Stremio all lean on a black outline; none
      // use a background box). Legibility is carried by the outline; the shadow is
      // the subtle extra VLC ships (and Stremio users request).
      '--freetype-color=16777215', // white
      '--freetype-opacity=255',
      '--freetype-outline-color=0', // black
      '--freetype-outline-opacity=255',
      `--freetype-outline-thickness=${OUTLINE_THICKNESS[subtitleOutline]}`,
      '--freetype-shadow-color=0', // black
      // shadow-distance is a FRACTION of glyph height (0.0–1.0), not pixels — the
      // =2 bug threw it a full line away and read as a duplicate. 0.06 = VLC default.
      `--freetype-shadow-opacity=${subtitleShadow ? 128 : 0}`,
      '--freetype-shadow-distance=0.06',
      '--freetype-background-opacity=0', // no box
    ]
    if (fontFamily) opts.unshift(`--freetype-font=${fontFamily}`)
    return opts
  }, [fontFamily, subtitleScalePercent, subtitleOutline, subtitleShadow])

  const handleTracksChanged = (media: MediaTracks) => {
    const spuIds = media.subtitle.filter((track) => track.id >= 0).map((track) => track.id)
    if (pendingSlaveUri.current) {
      const fresh = spuIds.filter((id) => !knownSpuIds.current.has(id))
      if (fresh.length > 0) {
        slaveTrackIds.current.set(pendingSlaveUri.current, Math.max(...fresh))
        pendingSlaveUri.current = null
      }
    }
    knownSpuIds.current = new Set(spuIds)

    const slaveIds = new Set(slaveTrackIds.current.values())
    latestTracks.current = {
      audio: media.audio.filter((track) => track.id >= 0),
      // Keep real embedded tracks only: drop the "Disable" sentinel (-1) and
      // slave tracks, which the screen manages as external subtitles.
      text: media.subtitle.filter((track) => track.id >= 0 && !slaveIds.has(track.id)),
    }
    onTracks(latestTracks.current.audio, latestTracks.current.text)
  }

  const handleFirstPlay = (info: MediaInfo) => {
    progress.current.durationSec = info.length / 1000
    const resumeAt = restartPosition.current
    if (resumeAt !== null && resumeAt > 0.001) {
      void playerRef.current?.seek(resumeAt, 'position')
    }
    restartPosition.current = null
    restarting.current = false
    onBuffering(100)
    onLoad({
      durationSec: progress.current.durationSec,
      audioTracks: latestTracks.current.audio,
      textTracks: latestTracks.current.text,
    })
  }

  return (
    <LibVlcPlayerView
      key={`vlc-${playbackConfigKey}`}
      ref={playerRef}
      style={styles.video}
      source={uri}
      instanceOptions={instanceOptions}
      tracks={tracks}
      slaves={slaves}
      contentFit={fitMode}
      rate={playbackRate}
      subtitleDelayMs={subtitleDelayMs}
      autoplay={!paused}
      pictureInPicture
      onFirstPlay={handleFirstPlay}
      onESAdded={handleTracksChanged}
      // Rounded so the flood of fractional cache-progress events collapses to
      // ≤100 distinct values (identical state values skip the re-render).
      onBuffering={({ progress: value }) => onBuffering(Math.round(value))}
      onPlaying={() => onBuffering(100)}
      onBackground={onBackground}
      // Time and position arrive as separate events each tick; the ref absorbs
      // both and only the position event emits, halving parent re-renders.
      onTimeChanged={({ value }) => {
        progress.current.currentTimeSec = value / 1000
        // Some media never report a length; derive one so seeking still works.
        if (progress.current.durationSec <= 0 && progress.current.position > 0.001) {
          progress.current.durationSec = progress.current.currentTimeSec / progress.current.position
        }
      }}
      onPositionChanged={({ value }) => {
        progress.current.position = value
        onProgress({ ...progress.current })
      }}
      onEncounteredError={() => {
        errored.current = true
        onError()
      }}
      // libvlc has no end event here: EndReached triggers stop(), which emits
      // Stopped — also emitted after an error or a config rebuild, hence the
      // guards.
      onStopped={() => {
        if (!errored.current && !restarting.current) onEnd()
      }}
    />
  )
}

const styles = StyleSheet.create({
  video: { flex: 1 },
})
