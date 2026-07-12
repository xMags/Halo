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
  startPictureInPicture(): Promise<void>
}

export type VideoFitMode = 'cover' | 'contain'

/**
 * Platform-neutral contract for the VLC-backed video surface.
 * Both platforms render through expo-libvlc-player, backed by VLCKit on iOS
 * and libvlc-all on Android. All times are seconds and track lists contain
 * real tracks only.
 */
export interface PlayerVideoProps {
  ref?: Ref<PlayerVideoHandle>
  /** Percent-encoded stream URL or local file:// URI. */
  uri: string
  paused: boolean
  fitMode: VideoFitMode
  playbackRate: number
  subtitleDelayMs: number
  subtitleScalePercent: number
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
  onBuffering(buffering: boolean): void
  onError(): void
  onEnd(): void
}
