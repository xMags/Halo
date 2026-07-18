import type { AuthConfig } from '@halo/core'
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { clearServerUrl, getClient, getServerUrl, onUnauthorized, setServerUrl } from './api'
import { loadLocalSession, signOutLocal } from './localAuth'

/**
 * App-level auth state machine, mirroring mobile's session provider:
 *   no server configured → 'unconfigured' (Connect screen)
 *   server known, no session → 'unauthenticated' (Login screen, branched by auth mode)
 *   session present → 'authenticated'
 * Only a definitive rejection (refresh 401) signs the device out — network
 * failures never do (that policy lives in localAuth/HaloClient).
 */
export type SessionState = 'unconfigured' | 'unauthenticated' | 'authenticated'

interface SessionContextValue {
  state: SessionState
  serverUrl: string | null
  /** Auth mode of the configured server; null until discovered. */
  authConfig: AuthConfig | null
  connect: (serverUrl: string, config: AuthConfig) => void
  /** Called by the login screen after a successful sign-in. */
  signedIn: () => void
  signOut: () => void
  /** Forget the server entirely (back to Connect). */
  disconnect: () => void
}

const SessionContext = createContext<SessionContextValue | null>(null)

export function SessionProvider({ children }: { children: ReactNode }) {
  const [serverUrl, setServer] = useState<string | null>(() => getServerUrl())
  const [authConfig, setAuthConfig] = useState<AuthConfig | null>(null)
  const [state, setState] = useState<SessionState>(() => {
    if (!getServerUrl()) return 'unconfigured'
    return loadLocalSession() ? 'authenticated' : 'unauthenticated'
  })

  // A 401 that survives the refresh retry means the session is dead.
  useEffect(() => {
    onUnauthorized(() => {
      signOutLocal()
      setState('unauthenticated')
    })
  }, [])

  // Re-discover the auth mode for an already-configured server (needed by the
  // login screen after restart; harmless when authenticated).
  useEffect(() => {
    if (!serverUrl || authConfig) return
    getClient()
      .getAuthConfig()
      .then(setAuthConfig)
      .catch(() => {
        // Server unreachable right now — the login screen shows a retry.
      })
  }, [serverUrl, authConfig])

  const connect = useCallback((url: string, config: AuthConfig) => {
    setServerUrl(url)
    setServer(url)
    setAuthConfig(config)
    setState('unauthenticated')
  }, [])

  const signedIn = useCallback(() => setState('authenticated'), [])

  const signOut = useCallback(() => {
    signOutLocal()
    setState('unauthenticated')
  }, [])

  const disconnect = useCallback(() => {
    signOutLocal()
    clearServerUrl()
    setServer(null)
    setAuthConfig(null)
    setState('unconfigured')
  }, [])

  const value = useMemo(
    () => ({ state, serverUrl, authConfig, connect, signedIn, signOut, disconnect }),
    [state, serverUrl, authConfig, connect, signedIn, signOut, disconnect],
  )

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}

export function useSession(): SessionContextValue {
  const ctx = useContext(SessionContext)
  if (!ctx) throw new Error('useSession outside SessionProvider')
  return ctx
}
