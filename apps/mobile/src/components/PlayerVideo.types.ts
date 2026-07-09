import type { Ref } from 'react'

export interface PlayerTrack {
  id: number
  name: string
}

export interface PlayerLoadInfo {
  durationSec: number
  audioTracks: PlayerTrack[]
  textTracks: PlayerTrack[]
}

export interface PlayerProgress {
  /** Playback position as a fraction 0..1. */
  position: number
  durationSec: number
  currentTimeSec: number
}

export interface PlayerVideoHandle {
  /** Seek to a fraction of the media duration (0..1). */
  seek(fraction: number): void
}

/**
 * Platform-neutral contract for the VLC-backed video surface.
 * iOS renders via react-native-vlc-media-player (VLCKit), Android via
 * expo-libvlc-player (libvlc-all) — see PlayerVideo.ios.tsx /
 * PlayerVideo.android.tsx. All times are seconds and track lists contain
 * real tracks only; each implementation owns its library's unit and
 * sentinel-track quirks.
 */
export interface PlayerVideoProps {
  ref?: Ref<PlayerVideoHandle>
  /** Percent-encoded stream URL or local file:// URI. */
  uri: string
  paused: boolean
  /** Embedded audio track id; `undefined` keeps the player default. */
  audioTrack?: number
  /** Embedded subtitle track id; `-1` disables, `undefined` keeps default. */
  textTrack?: number
  /** Local file:// URI of an external subtitle to load and select. */
  subtitleUri?: string
  /** Fires once when the media is ready; track lists may still grow after. */
  onLoad(info: PlayerLoadInfo): void
  /** Fires whenever the track lists change — network streams add tracks late. */
  onTracks(audioTracks: PlayerTrack[], textTracks: PlayerTrack[]): void
  onProgress(progress: PlayerProgress): void
  onError(): void
  onEnd(): void
}
