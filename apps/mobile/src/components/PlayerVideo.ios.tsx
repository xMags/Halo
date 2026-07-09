import { useImperativeHandle, useRef } from 'react'
import { VLCPlayer } from 'react-native-vlc-media-player'
import type { PlayerTrack, PlayerVideoProps } from './PlayerVideo.types'

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
  const playerRef = useRef<VLCPlayer>(null)

  useImperativeHandle(ref, () => ({
    seek: (fraction: number) => playerRef.current?.seek(fraction),
  }))

  return (
    <VLCPlayer
      ref={playerRef}
      source={{ uri, initOptions: ['--sub-text-scale=100'] }}
      paused={paused}
      autoplay
      audioTrack={audioTrack}
      textTrack={textTrack}
      subtitleUri={subtitleUri}
      playInBackground
      autoAspectRatio
      resizeMode="contain"
      onLoad={(info: { duration: number; audioTracks?: PlayerTrack[]; textTracks?: PlayerTrack[] }) => {
        const audioTracks = info.audioTracks ?? []
        // VLC reports "Disable" as id -1; keep real subtitle tracks only.
        const textTracks = (info.textTracks ?? []).filter((t) => t.id >= 0)
        onLoad({ durationSec: normalizeSeconds(info.duration), audioTracks, textTracks })
        onTracks(audioTracks, textTracks)
      }}
      onProgress={(event: { position: number; duration: number; currentTime: number }) => {
        onProgress({
          position: event.position,
          durationSec: normalizeSeconds(event.duration),
          currentTimeSec: normalizeSeconds(event.currentTime),
        })
      }}
      onError={onError}
      onEnd={onEnd}
    />
  )
}

/**
 * The VLC bridge has historically emitted milliseconds while its typings say
 * seconds. Nothing we play is 14+ hours, so values above that are ms.
 */
function normalizeSeconds(value: number): number {
  return value > 50_000 ? value / 1000 : value
}
