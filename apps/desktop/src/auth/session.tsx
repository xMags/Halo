import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import {
  activateSession,
  deactivateSession,
  getSessionKind,
  onUnauthorized,
  restoreSession,
  type SessionKind,
} from '../api'
import { signOutLocal } from './localAuth'
import { signOutOidc } from './oidc'
import { clearDownloadsAccount } from '../downloads/downloadsStore'

/**
 * App-level auth state, mirroring mobile's session provider. The server is
 * built in (serverUrl.ts), so there are two states:
 *   no session → 'unauthenticated' (Login, branched by the server's auth mode)
 *   session present → 'authenticated'
 * Only a definitive rejection (OIDC invalid_grant / local refresh 401) signs
 * the device out; network failures never do (that policy lives in the auth
 * modules and HaloClient).
 */
export type SessionState = 'unauthenticated' | 'authenticated'

interface SessionContextValue {
  state: SessionState
  /** Called by the sign-in screen once the session is persisted and the account is ready. */
  signedIn: (kind: SessionKind) => void
  signOut: () => void
}

const SessionContext = createContext<SessionContextValue | null>(null)

export function SessionProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<SessionState>(() => (restoreSession() ? 'authenticated' : 'unauthenticated'))

  // Reached only when the refresh itself was rejected (or impossible): the
  // session is dead. A 401 after a successful refresh keeps the session.
  useEffect(() => {
    onUnauthorized(() => {
      void clearDownloadsAccount().catch(() => undefined)
      setState('unauthenticated')
    })
  }, [])

  const signedIn = useCallback((kind: SessionKind) => {
    if (getSessionKind() !== kind) activateSession(kind)
    setState('authenticated')
  }, [])

  const signOut = useCallback(() => {
    void clearDownloadsAccount().catch(() => undefined)
    if (getSessionKind() === 'oidc') void signOutOidc()
    else signOutLocal()
    deactivateSession()
    setState('unauthenticated')
  }, [])

  const value = useMemo(() => ({ state, signedIn, signOut }), [state, signedIn, signOut])

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}

export function useSession(): SessionContextValue {
  const ctx = useContext(SessionContext)
  if (!ctx) throw new Error('useSession outside SessionProvider')
  return ctx
}
