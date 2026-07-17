import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { login, loginLocal, logout, restoreSession, setSessionExpiredHandler } from './api'

type SessionStatus = 'loading' | 'loggedOut' | 'ready'

interface SessionContextValue {
  status: SessionStatus
  /**
   * Starts sign-in. Resolves 'credentials-required' when the server runs local
   * accounts — the login screen then collects them and calls signInLocal.
   */
  signIn: (serverUrl: string) => Promise<'ready' | 'credentials-required'>
  signInLocal: (serverUrl: string, username: string, password: string) => Promise<void>
  signOut: () => Promise<void>
}

const SessionContext = createContext<SessionContextValue | null>(null)

export function SessionProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<SessionStatus>('loading')
  const queryClient = useQueryClient()

  useEffect(() => {
    setSessionExpiredHandler(() => setStatus('loggedOut'))
    restoreSession()
      .then((client) => setStatus(client ? 'ready' : 'loggedOut'))
      .catch(() => setStatus('loggedOut'))
  }, [])

  // Every sync query (addons, library, watch-state, settings, me) is
  // user-scoped, so wipe the cache on any auth boundary. Clearing on sign-in as
  // well as sign-out covers the account-switch case where a session expired
  // straight to the login screen without an explicit sign-out.
  const signIn = useCallback(
    async (serverUrl: string) => {
      const result = await login(serverUrl)
      if (result === 'ready') {
        queryClient.clear()
        setStatus('ready')
      }
      return result
    },
    [queryClient],
  )

  const signInLocal = useCallback(
    async (serverUrl: string, username: string, password: string) => {
      await loginLocal(serverUrl, username, password)
      queryClient.clear()
      setStatus('ready')
    },
    [queryClient],
  )

  const signOut = useCallback(async () => {
    await logout()
    queryClient.clear()
    setStatus('loggedOut')
  }, [queryClient])

  const value = useMemo(() => ({ status, signIn, signInLocal, signOut }), [status, signIn, signInLocal, signOut])
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}

export function useSession(): SessionContextValue {
  const ctx = useContext(SessionContext)
  if (!ctx) throw new Error('useSession must be used inside SessionProvider')
  return ctx
}
