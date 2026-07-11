import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { login, logout, restoreSession, setSessionExpiredHandler } from './api'

type SessionStatus = 'loading' | 'loggedOut' | 'ready'

interface SessionContextValue {
  status: SessionStatus
  signIn: (serverUrl: string, username: string, password: string) => Promise<void>
  signOut: () => Promise<void>
}

const SessionContext = createContext<SessionContextValue | null>(null)

export function SessionProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<SessionStatus>('loading')

  useEffect(() => {
    setSessionExpiredHandler(() => setStatus('loggedOut'))
    restoreSession()
      .then((client) => setStatus(client ? 'ready' : 'loggedOut'))
      .catch(() => setStatus('loggedOut'))
  }, [])

  const signIn = useCallback(async (serverUrl: string, username: string, password: string) => {
    await login(serverUrl, username, password)
    setStatus('ready')
  }, [])

  const signOut = useCallback(async () => {
    await logout()
    setStatus('loggedOut')
  }, [])

  const value = useMemo(() => ({ status, signIn, signOut }), [status, signIn, signOut])
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}

export function useSession(): SessionContextValue {
  const ctx = useContext(SessionContext)
  if (!ctx) throw new Error('useSession must be used inside SessionProvider')
  return ctx
}
