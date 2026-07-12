import { useEffect, useImperativeHandle, useMemo, useRef } from 'react'
import { StyleSheet } from 'react-native'
import { LibVlcPlayerView } from 'expo-libvlc-player'
import type { LibVlcPlayerViewRef, MediaInfo, MediaTracks, Slave } from 'expo-libvlc-player'
import type { PlayerTrack, PlayerVideoProps } from './PlayerVideo.types'

const COVER_SUBTITLE_SAFE_MARGIN = 160

export default function PlayerVideoVlc({
  ref,
  uri,
  paused,
  fitMode,
  playbackRate,
  subtitleDelayMs,
  subtitleScalePercent,
  audioTrack,
  textTrack,
  subtitleUri,
  onLoad,
  onTracks,
  onProgress,
  onBuffering,
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

  const sentSlaves = useRef<Slave[]>([])
  const pendingSlaveUri = useRef<string | null>(null)
  const slaveTrackIds = useRef(new Map<string, number>())
  const knownSpuIds = useRef<Set<number>>(new Set())

  if (subtitleUri && !sentSlaves.current.some((slave) => slave.source === subtitleUri)) {
    sentSlaves.current = [...sentSlaves.current, { source: subtitleUri, type: 'subtitle', selected: true }]
    pendingSlaveUri.current = subtitleUri
  }
  const slaves = sentSlaves.current
  const spuTrack = subtitleUri !== undefined ? slaveTrackIds.current.get(subtitleUri) : textTrack
  const tracks = useMemo(() => ({ audio: audioTrack, subtitle: spuTrack }), [audioTrack, spuTrack])
  const subtitleMargin = fitMode === 'cover' ? COVER_SUBTITLE_SAFE_MARGIN : 0
  const options = useMemo(
    () => [`:sub-text-scale=${subtitleScalePercent}`, `:sub-margin=${subtitleMargin}`],
    [subtitleMargin, subtitleScalePercent],
  )

  const latestTracks = useRef<{ audio: PlayerTrack[]; text: PlayerTrack[] }>({ audio: [], text: [] })
  const progress = useRef({ position: 0, durationSec: 0, currentTimeSec: 0 })
  const errored = useRef(false)
  const playbackConfigKey = `${fitMode}-${subtitleScalePercent}`
  const previousPlaybackConfig = useRef(playbackConfigKey)
  const restartPosition = useRef<number | null>(null)
  const restarting = useRef(false)

  if (previousPlaybackConfig.current !== playbackConfigKey) {
    previousPlaybackConfig.current = playbackConfigKey
    restartPosition.current = progress.current.position
    restarting.current = true
  }

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
    onBuffering(false)
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
      options={options}
      tracks={tracks}
      slaves={slaves}
      contentFit={fitMode}
      rate={playbackRate}
      subtitleDelayMs={subtitleDelayMs}
      pictureInPicture
      onFirstPlay={handleFirstPlay}
      onESAdded={handleTracksChanged}
      onBuffering={({ progress: value }) => onBuffering(value < 100)}
      onPlaying={() => onBuffering(false)}
      onTimeChanged={({ value }) => {
        progress.current.currentTimeSec = value / 1000
        if (progress.current.durationSec <= 0 && progress.current.position > 0.001) {
          progress.current.durationSec = progress.current.currentTimeSec / progress.current.position
        }
        onProgress({ ...progress.current })
      }}
      onPositionChanged={({ value }) => {
        progress.current.position = value
        onProgress({ ...progress.current })
      }}
      onEncounteredError={() => {
        errored.current = true
        onError()
      }}
      onStopped={() => {
        if (!errored.current && !restarting.current) onEnd()
      }}
    />
  )
}

const styles = StyleSheet.create({
  video: { flex: 1 },
})
