import { fetch as nativeFetch } from '@tauri-apps/plugin-http'
import { useQuery } from '@tanstack/react-query'
import { getServerUrl } from './api'

/**
 * Reachability and round-trip latency for the configured server, shown at the
 * foot of the navigation rail. `/auth/config` is the probe: it is public,
 * constant-cost, and already the endpoint the app uses to discover auth mode,
 * so a failure here means the same thing a failed sign-in would.
 *
 * This never signs anyone out — that decision belongs to the auth modules, on
 * a definitive rejection only. A red dot here just means "right now, no".
 */

const PROBE_INTERVAL_MS = 30_000
const PROBE_TIMEOUT_MS = 6_000

export type ReachState = 'probing' | 'connected' | 'unreachable'

export interface ServerStatus {
  state: ReachState
  /** Round-trip milliseconds of the last successful probe. */
  latencyMs: number | null
  /** Host without scheme — what the rail and the sign-in screen display. */
  host: string
}

export function useServerStatus(): ServerStatus {
  const serverUrl = getServerUrl()
  const host = serverUrl?.replace(/^https?:\/\//, '') ?? ''

  const { data, isPending } = useQuery({
    queryKey: ['serverStatus', serverUrl],
    enabled: !!serverUrl,
    refetchInterval: PROBE_INTERVAL_MS,
    refetchOnWindowFocus: true,
    retry: false,
    // A stale latency reading is worse than none: keep only the last result.
    gcTime: PROBE_INTERVAL_MS * 2,
    queryFn: async (): Promise<number> => {
      const started = performance.now()
      const response = await nativeFetch(`${serverUrl}/auth/config`, {
        signal: AbortSignal.timeout(PROBE_TIMEOUT_MS),
      })
      if (!response.ok) throw new Error(`probe failed: ${response.status}`)
      return Math.round(performance.now() - started)
    },
  })

  if (!serverUrl) return { state: 'unreachable', latencyMs: null, host }
  if (isPending) return { state: 'probing', latencyMs: null, host }
  if (data === undefined) return { state: 'unreachable', latencyMs: null, host }
  return { state: 'connected', latencyMs: data, host }
}

/** The rail's second line: `connected · 12 ms`, or why not. */
export function describeStatus(status: ServerStatus): string {
  if (status.state === 'probing') return 'connecting…'
  if (status.state === 'unreachable') return 'unreachable'
  return `connected · ${status.latencyMs} ms`
}
