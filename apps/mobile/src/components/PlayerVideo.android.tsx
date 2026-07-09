import { useEffect, useImperativeHandle, useMemo, useRef } from 'react'
import { StyleSheet } from 'react-native'
import { LibVlcPlayerView } from 'expo-libvlc-player'
import type { LibVlcPlayerViewRef, MediaInfo, MediaTracks, Slave } from 'expo-libvlc-player'
import type { PlayerTrack, PlayerVideoProps } from './PlayerVideo.types'

// Module-level so the array identity is stable across renders — a changed
// `options` prop makes the native side rebuild the whole player.
const VLC_OPTIONS = ['--sub-text-scale=100']

export default function PlayerVideo({
  ref,
  uri,
  paused,
  audioTrack,
  textTrack,
  subtitleUri,
  onLoad,
  onTracks,
  onProgress,
  onError,
  onEnd,
}: PlayerVideoProps) {
  const playerRef = useRef<LibVlcPlayerViewRef>(null)

  useImperativeHandle(ref, () => ({
    seek: (fraction: number) => void playerRef.current?.seek(fraction, 'position'),
  }))

  // autoplay starts playback; afterwards the paused prop drives play/pause.
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

  if (subtitleUri && !sentSlaves.current.some((s) => s.source === subtitleUri)) {
    sentSlaves.current = [...sentSlaves.current, { source: subtitleUri, type: 'subtitle', selected: true }]
    pendingSlaveUri.current = subtitleUri
  }
  const slaves = sentSlaves.current

  // While a freshly-added slave is pending its track id, `selected: true` on
  // the slave itself does the selecting and this resolves to undefined.
  const spuTrack = subtitleUri !== undefined ? slaveTrackIds.current.get(subtitleUri) : textTrack
  const tracks = useMemo(() => ({ audio: audioTrack, subtitle: spuTrack }), [audioTrack, spuTrack])

  const latestTracks = useRef<{ audio: PlayerTrack[]; text: PlayerTrack[] }>({ audio: [], text: [] })
  const progress = useRef({ position: 0, durationSec: 0, currentTimeSec: 0 })
  const errored = useRef(false)

  const handleTracksChanged = (media: MediaTracks) => {
    const spuIds = media.subtitle.filter((t) => t.id >= 0).map((t) => t.id)
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
      audio: media.audio,
      // Keep real embedded tracks only: drop the "Disable" sentinel (-1) and
      // slave tracks, which the screen manages as external subtitles.
      text: media.subtitle.filter((t) => t.id >= 0 && !slaveIds.has(t.id)),
    }
    onTracks(latestTracks.current.audio, latestTracks.current.text)
  }

  const handleFirstPlay = (info: MediaInfo) => {
    progress.current.durationSec = info.length / 1000
    onLoad({
      durationSec: progress.current.durationSec,
      audioTracks: latestTracks.current.audio,
      textTracks: latestTracks.current.text,
    })
  }

  return (
    <LibVlcPlayerView
      ref={playerRef}
      style={styles.video}
      source={uri}
      options={VLC_OPTIONS}
      tracks={tracks}
      slaves={slaves}
      contentFit="contain"
      onFirstPlay={handleFirstPlay}
      onESAdded={handleTracksChanged}
      onTimeChanged={({ value }) => {
        progress.current.currentTimeSec = value / 1000
        // Some media never report a length; derive one so seeking still works.
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
      // libvlc has no end event here: EndReached triggers stop(), which emits
      // Stopped — also emitted after an error, hence the guard.
      onStopped={() => {
        if (!errored.current) onEnd()
      }}
    />
  )
}

const styles = StyleSheet.create({
  video: {
    flex: 1,
  },
})
