import { invoke } from '@tauri-apps/api/core'

/**
 * Halo's own entry in the Windows Volume Mixer (see `audio_session.rs`). The
 * player drives this rather than mpv's software gain, so its slider and the
 * mixer's are one control and Windows remembers the level between launches.
 */

export interface AudioSessionVolume {
  /** 0 to 1. */
  volume: number
  muted: boolean
}

/** Null until mpv has opened its audio output, which creates the session. */
export function readAudioSession(): Promise<AudioSessionVolume | null> {
  return invoke<AudioSessionVolume | null>('audio_session_read')
}

/** False when there is no session to set yet; the caller falls back to mpv. */
export function setAudioSessionVolume(volume: number, unmute: boolean): Promise<boolean> {
  return invoke<boolean>('audio_session_set_volume', { volume, unmute })
}
