import { useQueryClient } from '@tanstack/react-query'
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import {
  activateDevice,
  activateSession,
  deactivateSession,
  getDeviceProfile,
  getSessionKind,
  onUnauthorized,
  rememberDeviceProfile,
  restoreSession,
  seedDefaultAddons,
  sessionAddon,
  type SessionKind,
} from '../api'
import { signOutLocal } from './localAuth'
import { signOutOidc } from './oidc'
import { clearDownloadsAccount } from '../downloads/downloadsStore'
import { createDeviceProfile, readDeviceProfile, type DeviceProfile } from '../device/deviceStore'

/**
 * App-level auth state, mirroring mobile's session provider. The server is
 * built in (serverUrl.ts), so there are three states:
 *   no session, never used without one → 'chooser' (Login, which also offers
 *     to continue without an account)
 *   Halo used without an account → 'device' (everything kept on this PC)
 *   session present → 'authenticated'
 * Only a definitive rejection (OIDC invalid_grant / local refresh 401 / a
 * sign-in add-on's session refused by the server) signs the device out;
 * network failures never do (that policy lives in the auth modules, the
 * add-ons and HaloClient).
 */
export type SessionState = 'chooser' | 'device' | 'authenticated'

interface SessionContextValue {
  state: SessionState
  /** True while the sign-in screen is open over Halo without an account. */
  signingIn: boolean
  /** Called by the sign-in screen once the session is persisted and the account is ready. */
  signedIn: (kind: SessionKind) => void
  signOut: () => void
  /** Starts (or resumes) Halo without an account; resolves once the default addons are in. */
  continueWithoutAccount: () => Promise<void>
  /** Opens the sign-in screen from Halo without an account. */
  startSignIn: () => void
  /** Leaves the sign-in screen and returns to Halo without an account, untouched. */
  cancelSignIn: () => void
}

const SessionContext = createContext<SessionContextValue | null>(null)

let startupDeviceProfile: DeviceProfile | null = null

/**
 * Reads the device profile once, before the first render (main.tsx): the
 * provider's first state depends on it, and reading it is a native call.
 */
export async function loadStartupDeviceProfile(): Promise<void> {
  startupDeviceProfile = await readDeviceProfile().catch(() => null)
}

function initialState(): SessionState {
  if (restoreSession()) {
    // Signed in with data still on this PC: a move into the account that has
    // not finished yet, which the signed-in shell retries.
    if (startupDeviceProfile) rememberDeviceProfile(startupDeviceProfile)
    return 'authenticated'
  }
  if (startupDeviceProfile) {
    activateDevice(startupDeviceProfile)
    return 'device'
  }
  return 'chooser'
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [state, setState] = useState<SessionState>(initialState)
  const [signingIn, setSigningIn] = useState(false)

  // Every change of backend starts from an empty cache, so nothing one
  // account (or this PC) loaded can show under another.
  const switchTo = useCallback(
    (next: SessionState) => {
      queryClient.clear()
      setSigningIn(false)
      setState(next)
    },
    [queryClient],
  )

  // Reached only when the refresh itself was rejected (or impossible): the
  // session is dead. A 401 after a successful refresh keeps the session.
  useEffect(() => {
    onUnauthorized(() => {
      void clearDownloadsAccount().catch(() => undefined)
      switchTo('chooser')
    })
  }, [switchTo])

  // A first run without an account may have had no network for the default
  // addons. Retried on every start until they are in; a no-op afterwards.
  useEffect(() => {
    if (state !== 'device') return
    void seedDefaultAddons().then(() => queryClient.invalidateQueries({ queryKey: ['addons'] }))
  }, [state, queryClient])

  const signedIn = useCallback(
    (kind: SessionKind) => {
      if (getSessionKind() !== kind) activateSession(kind)
      switchTo('authenticated')
    },
    [switchTo],
  )

  const signOut = useCallback(() => {
    void clearDownloadsAccount().catch(() => undefined)
    const kind = getSessionKind()
    if (kind === 'oidc') void signOutOidc()
    else if (kind === 'local') signOutLocal()
    else void sessionAddon(kind)?.signOut()
    deactivateSession()
    switchTo('chooser')
  }, [switchTo])

  const continueWithoutAccount = useCallback(async () => {
    // An earlier profile is resumed: its data may still be waiting to move
    // into an account, or the person signed out after a move that failed.
    const profile = getDeviceProfile() ?? (await readDeviceProfile()) ?? (await createDeviceProfile())
    activateDevice(profile)
    await seedDefaultAddons()
    switchTo('device')
  }, [switchTo])

  const startSignIn = useCallback(() => setSigningIn(true), [])
  const cancelSignIn = useCallback(() => setSigningIn(false), [])

  const value = useMemo(
    () => ({ state, signingIn, signedIn, signOut, continueWithoutAccount, startSignIn, cancelSignIn }),
    [state, signingIn, signedIn, signOut, continueWithoutAccount, startSignIn, cancelSignIn],
  )

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}

export function useSession(): SessionContextValue {
  const ctx = useContext(SessionContext)
  if (!ctx) throw new Error('useSession outside SessionProvider')
  return ctx
}
