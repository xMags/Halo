import { HaloApiError, type MagnetPhase, type MagnetRequest } from '@halo/core'
import { useEffect, useRef, useState } from 'react'
import { getAccountClient } from '../api'
import { useCapabilities } from '../capabilities'
import { forgetReadyMagnetOtherThan, rememberReadyMagnet } from '../magnets/magnetStore'

/*
 * A pasted magnet, asked about on the account's server. Only its info hash
 * and display name leave the app, never the link or its trackers. While the
 * server is still fetching it, it is asked again every 3 seconds, but only
 * while its card is on screen (the hook lives on the Search page, and leaving
 * it stops the asking) and for at most 10 minutes from the paste; pasting it
 * again asks anew. Cancel forgets the request on the server.
 */

const POLL_MS = 3_000
const POLL_LIMIT_MS = 10 * 60_000

export interface MagnetTracking {
  phase: MagnetPhase
  request: MagnetRequest | null
  cachedOnly: boolean
  cancel: () => void
  retry: () => void
}

/** `hash` is the classifier's 40 lower-case hex, or null when no magnet is pasted. */
export function useMagnet(hash: string | null, name: string): MagnetTracking {
  const { capabilities, settled } = useCapabilities()
  const [phase, setPhase] = useState<MagnetPhase>('checking')
  const [request, setRequest] = useState<MagnetRequest | null>(null)
  const [attempt, setAttempt] = useState(0)
  const requestRef = useRef<MagnetRequest | null>(null)
  requestRef.current = request
  /** Stops the asking under way, for Cancel. */
  const stopRef = useRef<() => void>(() => undefined)

  useEffect(() => {
    if (!hash) return
    setRequest(null)
    // Until the server says what it offers, the card reads as checking.
    if (!settled) {
      setPhase('checking')
      return
    }
    const client = getAccountClient()
    // Without an account the capabilities read as none: no magnet call is made.
    if (!client || !capabilities.magnets) {
      setPhase('unavailable')
      return
    }
    forgetReadyMagnetOtherThan(hash)
    setPhase('checking')
    const controller = new AbortController()
    const trackedAt = Date.now()
    let timer: number | undefined
    const answered = (next: MagnetRequest) => {
      if (controller.signal.aborted) return
      setRequest(next)
      setPhase('answered')
      if (next.state === 'ready') rememberReadyMagnet(client, hash, next)
      // Long enough: the card keeps its last answer.
      if (next.state === 'downloading' && Date.now() - trackedAt < POLL_LIMIT_MS) {
        timer = window.setTimeout(() => void poll(next.id), POLL_MS)
      }
    }
    const failed = (error: unknown, adding: boolean) => {
      if (controller.signal.aborted) return
      setRequest(null)
      // The server's 404 on adding one: magnets are not available to this account.
      setPhase(adding && error instanceof HaloApiError && error.status === 404 ? 'unavailable' : 'failed')
    }
    const poll = async (id: string) => {
      try {
        answered(await client.getMagnet(id, { signal: controller.signal }))
      } catch (error) {
        failed(error, false)
      }
    }
    void client
      .addMagnet(hash, name || null, { signal: controller.signal })
      .then(answered, (error: unknown) => failed(error, true))
    const stop = () => {
      controller.abort()
      window.clearTimeout(timer)
    }
    stopRef.current = stop
    return stop
  }, [hash, name, settled, capabilities.magnets, attempt])

  const cancel = () => {
    const current = requestRef.current
    const client = getAccountClient()
    // Nothing more is asked about it; the server forgets the request and its torrent.
    stopRef.current()
    setRequest(null)
    setPhase('stopped')
    if (current && client) void client.forgetMagnet(current.id).catch(() => undefined)
  }

  return {
    phase,
    request,
    cachedOnly: capabilities.magnetsCachedOnly,
    cancel,
    retry: () => setAttempt((value) => value + 1),
  }
}
