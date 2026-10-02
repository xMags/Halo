import { HaloClient, type AuthConfig, type OidcAuthConfig, type OtherAuthConfig } from '@halo/core'
import { fetch as nativeFetch } from '@tauri-apps/plugin-http'
import { openUrl } from '@tauri-apps/plugin-opener'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import mark from '../assets/halo-mark.png'
import avatar from '../assets/user-avatar.png'
import { activateSession, addonSessionKind, getClient, getServerUrl, seedDefaultAddons, type SessionKind } from '../api'
import { FluentIcon } from '../components/FluentIcon'
import { ProgressBar, ProgressRing } from '../components/ProgressRing'
import { TitleBar } from '../components/TitleBar'
import { accountLabel } from './accountLabel'
import { signInAddonFor } from './installedAddons'
import { signInWithPassword } from './localAuth'
import { cancelBrowserSignIn } from './loopbackSignIn'
import { signInWithOidc } from './oidc'
import type { SignInAddon } from './signInAddons'
import { useSession } from './session'
import { classifySignInFailure, localSignInMessage } from './signInFailure'

/** How long "You're all set" stays up before the library opens, as in the native app. */
const SIGNED_IN_DWELL_MS = 1200
const HELP_URL = 'https://github.com/xMags/Halo/issues'
const SETUP_GUIDE_URL = 'https://github.com/xMags/Halo#readme'

/** A browser sign-in: the server's OIDC provider, or the sign-in add-on for the server's mode. */
type BrowserMethod =
  | { kind: 'oidc'; config: OidcAuthConfig }
  | { kind: 'addon'; addon: SignInAddon; config: OtherAuthConfig }

/** The native sign-in page's steps (LoginViewModel), one panel each. */
type Step =
  | { kind: 'discovering' }
  | { kind: 'unreachable'; detail: string }
  | { kind: 'unsupported'; mode: string }
  | { kind: 'local'; error: string | null }
  | { kind: 'browser'; method: BrowserMethod; error: string | null }
  | { kind: 'waiting'; local: true }
  | { kind: 'waiting'; local: false; method: BrowserMethod; authUrl: string | null }
  | { kind: 'signedIn'; name: string; label: string }
  | { kind: 'declined'; method: BrowserMethod }
  | { kind: 'expired'; method: BrowserMethod }

function isOidcConfig(config: AuthConfig | OtherAuthConfig): config is OidcAuthConfig {
  return config.mode === 'oidc'
}

/** OIDC and local accounts are built in; any other mode needs this build to carry its add-on. */
function readyStep(config: AuthConfig | OtherAuthConfig): Step {
  if (config.mode === 'local') return { kind: 'local', error: null }
  if (isOidcConfig(config)) return { kind: 'browser', method: { kind: 'oidc', config }, error: null }
  // Neither built-in mode, so its fields are the add-on's to read. (A plain
  // `mode: string` member defeats narrowing, hence the explicit type.)
  const other = config as OtherAuthConfig
  const addon = signInAddonFor(other.mode)
  if (addon) return { kind: 'browser', method: { kind: 'addon', addon, config: other }, error: null }
  return { kind: 'unsupported', mode: other.mode }
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/**
 * The sign-in screen, matching the native app's LoginPage: the server is
 * built in, so it opens by asking that server how it signs people in, then
 * offers exactly that one way. Every async step is tagged with an attempt
 * number so a cancelled or superseded attempt can never overwrite a newer one.
 */
export function Login() {
  const { signedIn } = useSession()
  const serverUrl = getServerUrl()
  const [step, setStep] = useState<Step>({ kind: 'discovering' })
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [detailsOpen, setDetailsOpen] = useState(false)
  const attempt = useRef(0)

  async function discover() {
    const id = ++attempt.current
    setStep({ kind: 'discovering' })
    try {
      const config = await new HaloClient({ baseUrl: serverUrl, fetch: nativeFetch }).getAuthConfig()
      if (id === attempt.current) setStep(readyStep(config))
    } catch (err) {
      if (id === attempt.current) setStep({ kind: 'unreachable', detail: err instanceof Error ? err.message : String(err) })
    }
  }

  useEffect(() => {
    void discover()
    return () => {
      attempt.current++
    }
    // Discovery runs once per mount; Retry calls it again.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /** Binds the new session, shows "You're all set" while the account gets ready, then opens the app. */
  async function finish(kind: SessionKind, id: number) {
    activateSession(kind)
    const me = await getClient()
      .getMe()
      .catch(() => null)
    if (id !== attempt.current) return
    setStep({ kind: 'signedIn', name: me?.username ?? '', label: accountLabel(me ?? undefined) })
    // A first sign-in seeds the default addons; Home must not draw before they exist.
    await Promise.all([seedDefaultAddons(), delay(SIGNED_IN_DWELL_MS)])
    if (id === attempt.current) signedIn(kind)
  }

  async function submitLocal(event: FormEvent) {
    event.preventDefault()
    if (!username || !password) {
      setStep({ kind: 'local', error: 'Enter your username and password.' })
      return
    }
    const id = ++attempt.current
    setStep({ kind: 'waiting', local: true })
    try {
      await signInWithPassword(serverUrl, username, password)
    } catch (err) {
      if (id === attempt.current) setStep({ kind: 'local', error: localSignInMessage(err) })
      return
    }
    await finish('local', id)
  }

  async function startBrowser(method: BrowserMethod) {
    const id = ++attempt.current
    setStep({ kind: 'waiting', local: false, method, authUrl: null })
    const onOpened = (authUrl: string) => {
      if (id !== attempt.current) return
      setStep((current) => (current.kind === 'waiting' && !current.local ? { ...current, authUrl } : current))
    }
    try {
      if (method.kind === 'oidc') await signInWithOidc(method.config, onOpened)
      else await method.addon.signIn(serverUrl, method.config, onOpened)
    } catch (err) {
      if (id !== attempt.current) return
      const failure = classifySignInFailure(err)
      switch (failure.kind) {
        case 'cancelled':
          setStep({ kind: 'browser', method, error: null })
          return
        case 'refused':
          setStep({ kind: 'browser', method, error: failure.message })
          return
        case 'declined':
        case 'expired':
          setStep({ kind: failure.kind, method })
          return
        case 'unreachable':
          setDetailsOpen(false)
          setStep({ kind: 'unreachable', detail: failure.detail })
          return
      }
    }
    await finish(method.kind === 'oidc' ? 'oidc' : addonSessionKind(method.addon), id)
  }

  function cancelBrowser(method: BrowserMethod) {
    attempt.current++
    cancelBrowserSignIn()
    setStep({ kind: 'browser', method, error: null })
  }

  return (
    <div className="auth-screen">
      <div className="glow glow-a" />
      <div className="glow glow-b" />
      <TitleBar />
      <div className="auth-stage">
        <div className="auth-col">
          {/* The lockup is identical in every state, so it lives outside them. */}
          <div className="logo-lockup">
            <img className="logo-mark" src={mark} alt="" />
            <div className="logo-word">HALO</div>
          </div>

          {step.kind === 'discovering' && (
            <>
              <div className="auth-title">Connecting to Halo</div>
              <div className="auth-ring" style={{ marginTop: 22 }}>
                <ProgressRing size={22} />
              </div>
              <div className="auth-sub">Checking how this server signs you in.</div>
            </>
          )}

          {step.kind === 'local' && (
            <form onSubmit={(event) => void submitLocal(event)}>
              <div className="auth-title">Sign in to Halo</div>
              <div className="auth-sub">Use the account created on your Halo server.</div>
              <div className="field-block" style={{ marginTop: 22 }}>
                <label className="field-label" htmlFor="halo-username">
                  Username
                </label>
                <input
                  id="halo-username"
                  className="field"
                  value={username}
                  maxLength={512}
                  onChange={(e) => setUsername(e.target.value)}
                  autoFocus
                  spellCheck={false}
                />
              </div>
              <div className="field-block">
                <label className="field-label" htmlFor="halo-password">
                  Password
                </label>
                <input
                  id="halo-password"
                  className="field"
                  type="password"
                  value={password}
                  maxLength={4096}
                  onChange={(e) => setPassword(e.target.value)}
                />
              </div>
              {step.error && <div className="auth-error">{step.error}</div>}
              <button type="submit" className="btn-accent h36 btn-block" style={{ marginTop: 20 }}>
                Sign in
              </button>
            </form>
          )}

          {step.kind === 'browser' && (
            <>
              <div className="auth-title">Sign in to Halo</div>
              <div className="auth-sub">Your library and account data stay on your server.</div>
              {step.error && <div className="auth-error">{step.error}</div>}
              <button
                type="button"
                className="btn-accent h36 btn-block"
                style={{ marginTop: 24 }}
                onClick={() => void startBrowser(step.method)}
              >
                <FluentIcon glyph="openInNewWindow" size={15} />
                {step.method.kind === 'addon' ? step.method.addon.buttonLabel : 'Continue in browser'}
              </button>
              <button
                type="button"
                className="auth-help"
                title="Open Halo support"
                onClick={() => void openUrl(HELP_URL).catch(() => undefined)}
              >
                Having trouble signing in?
              </button>
            </>
          )}

          {step.kind === 'waiting' && (
            <>
              <div className="auth-title">{step.local ? 'Signing in' : 'Check your browser'}</div>
              <div className="auth-card" style={{ marginTop: 20 }}>
                <ProgressRing size={18} />
                <div className="auth-body">
                  {step.local
                    ? 'Halo is securely checking your account with your server.'
                    : 'We opened a sign-in request in your default browser. Approve it there and this window carries on by itself.'}
                </div>
              </div>
              <div className="auth-status">
                <span className="auth-status-dot" />
                <span className="mono">{step.local ? 'CHECKING ACCOUNT' : 'WAITING FOR BROWSER'}</span>
              </div>
              {!step.local && (
                <>
                  <button
                    type="button"
                    className="btn h36 btn-block"
                    style={{ marginTop: 22 }}
                    disabled={!step.authUrl}
                    onClick={() => step.authUrl && void openUrl(step.authUrl).catch(() => undefined)}
                  >
                    Open the link again
                  </button>
                  <button type="button" className="auth-help" onClick={() => cancelBrowser(step.method)}>
                    Cancel
                  </button>
                </>
              )}
            </>
          )}

          {step.kind === 'signedIn' && (
            <>
              <div className="auth-title">You’re all set</div>
              <div className="auth-account">
                <img className="auth-avatar" src={avatar} alt="" draggable={false} />
                <div className="auth-account-text">
                  {step.name && <div className="auth-name">{step.name}</div>}
                  <div className="mono">{step.label}</div>
                </div>
              </div>
              <div className="auth-sub" style={{ marginTop: 18 }}>
                Halo will remember this device until you sign out.
              </div>
              <div style={{ marginTop: 22 }}>
                <ProgressBar />
              </div>
              <div className="mono auth-center" style={{ marginTop: 10 }}>
                Opening your library…
              </div>
            </>
          )}

          {step.kind === 'declined' && (
            <>
              <div className="auth-title">Sign-in was declined</div>
              <div className="auth-sub" style={{ marginTop: 8 }}>
                You can try again whenever you’re ready.
              </div>
              <button
                type="button"
                className="btn-accent h36 btn-block"
                style={{ marginTop: 24 }}
                onClick={() => void startBrowser(step.method)}
              >
                Try again
              </button>
            </>
          )}

          {step.kind === 'expired' && (
            <>
              <div className="auth-title">Sign-in request expired</div>
              <div className="auth-sub" style={{ marginTop: 8 }}>
                This sign-in request has expired. Start a new one to continue.
              </div>
              <button
                type="button"
                className="btn-accent h36 btn-block"
                style={{ marginTop: 24 }}
                onClick={() => void startBrowser(step.method)}
              >
                Start again
              </button>
            </>
          )}

          {step.kind === 'unsupported' && (
            <>
              <div className="auth-title">This build can’t sign in here</div>
              <div className="auth-sub" style={{ marginTop: 8 }}>
                This server signs in with “{step.mode}”, which this build of Halo doesn’t include.
              </div>
              <button
                type="button"
                className="btn-accent h36 btn-block"
                style={{ marginTop: 24 }}
                onClick={() => void discover()}
              >
                Retry
              </button>
              <button
                type="button"
                className="auth-help"
                title="Open Halo support"
                onClick={() => void openUrl(HELP_URL).catch(() => undefined)}
              >
                Having trouble signing in?
              </button>
            </>
          )}

          {step.kind === 'unreachable' && (
            <>
              <div className="auth-title">Can’t connect to Halo</div>
              <div className="auth-sub" style={{ marginTop: 8 }}>
                Try again later.
              </div>
              <button
                type="button"
                className="btn-accent h36 btn-block"
                style={{ marginTop: 24 }}
                onClick={() => void discover()}
              >
                Retry
              </button>
              <div className="auth-setup">
                Need to set up a server?{' '}
                <button type="button" onClick={() => void openUrl(SETUP_GUIDE_URL).catch(() => undefined)}>
                  Read the setup guide
                </button>
              </div>
              <button
                type="button"
                className="auth-help auth-details-toggle"
                aria-expanded={detailsOpen}
                onClick={() => setDetailsOpen((open) => !open)}
              >
                <FluentIcon glyph={detailsOpen ? 'chevronDown' : 'chevronRight'} size={13} />
                Technical details
              </button>
              {detailsOpen && (
                <div className="auth-details">
                  <div className="auth-details-error">{step.detail}</div>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  )
}
