import { NO_CAPABILITIES, type Capabilities } from '@halo/core'
import { useQuery } from '@tanstack/react-query'
import { getClient } from './api'
import { useSession } from './auth/session'
import { useTmdbKeyStatus } from './device/tmdbKey'

/**
 * What the server (or, without an account, this PC) offers beyond the shared
 * contract: people search, IMDb/TVDB lookups, magnets. Asked once per
 * account; until it answers, and when the answer cannot be had, everything
 * reads as off. A failure is asked again the next time Search opens
 * (`refreshIfFailed`). Without an account it follows the saved TMDB key, so
 * saving or removing one in Settings applies at once.
 */
export function useCapabilities(): {
  capabilities: Capabilities
  /** The answer is in (or definitely failed): a screen waiting on it may decide. */
  settled: boolean
  /** Asks again when the last answer could not be had. */
  refreshIfFailed: () => void
  /** Without an account and without a working key, people search is off for want of one. */
  needsTmdbKey: boolean
} {
  const { state } = useSession()
  const keyStatus = useTmdbKeyStatus()
  const device = state === 'device'
  // Without an account the answer follows the key, so it waits for the key's first read.
  const keyLoading = device && keyStatus === 'loading'
  const query = useQuery({
    queryKey: ['capabilities', device ? `device:${keyStatus}` : 'account'],
    enabled: !keyLoading,
    queryFn: () => getClient().getCapabilities(),
    staleTime: Infinity,
    retry: false,
  })
  return {
    capabilities: query.data ?? NO_CAPABILITIES,
    settled: !query.isPending,
    refreshIfFailed: () => {
      if (query.isError) void query.refetch()
    },
    needsTmdbKey: device && !keyLoading && keyStatus !== 'working',
  }
}
