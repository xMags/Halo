import { useCallback, useRef, useState } from 'react'
import { readAudioSession, setAudioSessionVolume } from '../../audioSession'
import { mpvSet } from '../../mpv'

/**
 * The player's volume, carried by Halo's Windows mixer session once mpv has
 * opened audio (mpv's own gain then stays at 100), and by mpv's gain before
 * that or when Windows offers no session. `sync` adopts the session's level
 * for this file; the player calls it whenever playback (re)starts until it
 * takes, since the session only exists once audio is flowing.
 */
export function useAppVolume(mpvVolume: number) {
  const [sessionVolume, setSessionVolume] = useState<number | null>(null)
  const sessionActive = useRef(false)
  const syncing = useRef(false)

  const sync = useCallback(async () => {
    if (sessionActive.current || syncing.current) return
    syncing.current = true
    try {
      const session = await readAudioSession().catch(() => null)
      if (!session) return
      sessionActive.current = true
      await mpvSet('volume', '100').catch(() => undefined)
      setSessionVolume(session.muted ? 0 : Math.round(session.volume * 100))
    } finally {
      syncing.current = false
    }
  }, [])

  /** `muted` is mpv's own mute, which any level above zero lifts. */
  const change = useCallback(async (next: number, muted: boolean) => {
    const level = Math.min(100, Math.max(0, Math.round(next)))
    if (muted && level > 0) await mpvSet('mute', 'no').catch(() => undefined)
    if (sessionActive.current) {
      const applied = await setAudioSessionVolume(level / 100, level > 0).catch(() => false)
      if (applied) {
        setSessionVolume(level)
        return
      }
    }
    await mpvSet('volume', String(level)).catch(() => undefined)
  }, [])

  return { volume: sessionVolume ?? mpvVolume, sync, change }
}
