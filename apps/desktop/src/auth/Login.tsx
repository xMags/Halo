import { HaloClient, type AuthConfig, type OidcAuthConfig, type OtherAuthConfig } from '@halo/core'
import { fetch as nativeFetch } from '@tauri-apps/plugin-http'
import { openUrl } from '@tauri-apps/plugin-opener'
import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import {
  activateSession,
  addonSessionKind,
  getAccountClient,
  getBuiltInServerUrl,
  getDeviceProfile,
  seedDefaultAddons,
  type SessionKind,
} from '../api'
import { FluentIcon } from '../components/FluentIcon'
import { HaloMark } from '../components/HaloMark'
import { TitleBar } from '../components/TitleBar'
import { moveDeviceDataIntoAccount } from '../device/deviceDataMove'
import { parseServerAddress } from '../serverUrl'
import { accountDisplayName, accountLabel } from './accountLabel'
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
/** The last self-hosted server signed in to from the form, offered again next time. */
const LAST_SERVER_KEY = 'halo.lastServerAddress'

/** A browser sign-in: the server's OIDC provider, or the sign-in add-on for the server's mode. */
type BrowserMethod =
  | { kind: 'oidc'; config: OidcAuthConfig }
  | { kind: 'addon'; addon: SignInAddon; config: OtherAuthConfig }

/**
 * The sign-in steps. The built-in server decides the first: a browser method
 * (`browser`), its own accounts (`local`), or a mode this build lacks. The
 * username & password form for a self-hosted server (`custom`) is reachable
 * from every resting step, and returns to the one it was opened from.
 */
type Step =
  | { kind: 'discovering' }
  | { kind: 'unreachable'; detail: string }
  | { kind: 'unsupported'; mode: string }
  | { kind: 'local'; error: string | null }
  | { kind: 'browser'; method: BrowserMethod; error: string | null }
  | { kind: 'custom'; error: string | null; back: RestingStep }
  | { kind: 'waiting'; local: true }
  | { kind: 'waiting'; local: false; method: BrowserMethod; authUrl: string | null }
  | { kind: 'signedIn'; name: string; label: string; moving: boolean }
  | { kind: 'declined'; method: BrowserMethod }
  | { kind: 'expired'; method: BrowserMethod }
  | { kind: 'preparing' }

/** Where the custom-server form goes back to. */
type RestingStep = Extract<Step, { kind: 'discovering' | 'unreachable' | 'unsupported' | 'local' | 'browser' }>

function isOidcConfig(config: AuthConfig | OtherAuthConfig): config is OidcAuthConfig {
  return config.mode === 'oidc'
}

/** OIDC and local accounts are built in; any other mode needs this build to carry its add-on. */
function readyStep(config: AuthConfig | OtherAuthConfig): RestingStep {
  if (config.mode === 'local') return { kind: 'local', error: null }
  if (isOidcConfig(config)) return { kind: 'browser', method: { kind: 'oidc', config }, error: null }
  // Neither built-in mode, so its fields are the add-on's to read. (A plain
  // `mode: string` member defeats narrowing, hence the explicit type.)
  const other = config as OtherAuthConfig
  const addon = signInAddonFor(other.mode)
  if (addon) return { kind: 'browser', method: { kind: 'addon', addon, config: other }, error: null }
  return { kind: 'unsupported', mode: other.mode }
}

/** The browser method's button: the add-on names itself ("Continue with Magneto"). */
function methodLabel(method: BrowserMethod): string {
  return method.kind === 'addon' ? method.addon.buttonLabel : 'Continue in browser'
}

/** The form's way back to the browser method: "Sign in with Magneto instead". */
function backLabel(back: RestingStep): string {
  if (back.kind !== 'browser') return 'Back'
  return `${methodLabel(back.method).replace(/^Continue\b/, 'Sign in')} instead`
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function readLastServer(): string {
  try {
    return localStorage.getItem(LAST_SERVER_KEY) ?? ''
  } catch {
    return ''
  }
}

/**
 * Steps that leave Halo usable without an account. The others are mid sign-in
 * (credentials sent, or tokens already being saved) or a form, where the way
 * out is the form's own back link.
 */
const NO_ACCOUNT_STEPS: ReadonlySet<Step['kind']> = new Set([
  'discovering',
  'unreachable',
  'unsupported',
  'local',
  'browser',
  'declined',
  'expired',
])

/** The tagline and feature chips: left out of the forms, whose fields need the room. */
const INTRO_HIDDEN: ReadonlySet<Step['kind']> = new Set(['custom', 'local'])

/**
 * Posters fanned on the halo ring behind the card, left to right, and the
 * backdrops that crossfade behind everything. Fetched from Metahub, the image
 * host Cinemeta's own metadata points at, like every poster the app shows;
 * signed out there is no addon list to ask, so the titles are fixed (the same
 * ones the phone shows). A poster that fails to load keeps its dark card.
 */
const POSTERS: ReadonlyArray<{ imdbId: string; badge?: 'offline' | 'quality' }> = [
  { imdbId: 'tt11280740' },
  { imdbId: 'tt3581920', badge: 'offline' },
  { imdbId: 'tt15398776', badge: 'quality' },
  { imdbId: 'tt4574334' },
  { imdbId: 'tt1375666' },
]
const BACKDROPS = ['tt15239678', 'tt2543164', 'tt1877830']

const posterUrl = (imdbId: string) => `https://images.metahub.space/poster/medium/${imdbId}/img`
const backdropUrl = (imdbId: string) => `https://images.metahub.space/background/medium/${imdbId}/img`

/**
 * The sign-in screen: a frosted card over a fan of posters on the halo ring,
 * the design Last Music and RainFrames share, in Halo's accent. The server is
 * built in, so it opens by asking that server how it signs people in and
 * offers exactly that way, beside using Halo without an account and a
 * username & password form for a self-hosted server. Every async step is
 * tagged with an attempt number so a cancelled or superseded attempt can
 * never overwrite a newer one.
 */
export function Login() {
  const { state, signedIn, continueWithoutAccount, cancelSignIn } = useSession()
  const builtInServer = getBuiltInServerUrl()
  const [step, setStep] = useState<Step>({ kind: 'discovering' })
  const [deviceError, setDeviceError] = useState<string | null>(null)
  const [server, setServer] = useState(readLastServer)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [detailsOpen, setDetailsOpen] = useState(false)
  const attempt = useRef(0)

  async function discover() {
    const id = ++attempt.current
    setStep({ kind: 'discovering' })
    try {
      const config = await new HaloClient({ baseUrl: builtInServer, fetch: nativeFetch }).getAuthConfig()
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

  /**
   * Binds the new session, shows "You're all set" while the account gets
   * ready (and takes in what this PC holds from Halo without an account),
   * then opens the app.
   */
  async function finish(kind: SessionKind, id: number) {
    activateSession(kind)
    const me = await (getAccountClient()?.getMe() ?? Promise.resolve(null)).catch(() => null)
    if (id !== attempt.current) return
    // Moving needs the account's id (it names the downloads partition); when
    // it is not known yet, the signed-in shell does the move once it is.
    const moving = me !== null && getDeviceProfile() !== null
    setStep({
      kind: 'signedIn',
      name: me ? accountDisplayName(me.username) : '',
      label: accountLabel(me ?? undefined),
      moving,
    })
    const ready = async () => {
      // A first sign-in seeds the default addons; Home must not draw before
      // they exist. Seeding first also keeps a moved addon from counting as
      // the account already having addons.
      await seedDefaultAddons()
      if (moving) await moveDeviceDataIntoAccount(me.id)
    }
    await Promise.all([ready(), delay(SIGNED_IN_DWELL_MS)])
    if (id === attempt.current) signedIn(kind)
  }

  /** The built-in server's own accounts. */
  async function submitLocal(event: FormEvent) {
    event.preventDefault()
    if (!username || !password) {
      setStep({ kind: 'local', error: 'Enter your username and password.' })
      return
    }
    const id = ++attempt.current
    setStep({ kind: 'waiting', local: true })
    try {
      await signInWithPassword(builtInServer, username, password)
    } catch (err) {
      if (id === attempt.current) setStep({ kind: 'local', error: localSignInMessage(err) })
      return
    }
    await finish('local', id)
  }

  /**
   * A self-hosted server's accounts. Its sign-in mode is checked first, so a
   * server that signs in through a web page says so instead of refusing the
   * password as if it were wrong.
   */
  async function submitCustom(event: FormEvent, back: RestingStep) {
    event.preventDefault()
    const fail = (error: string) => setStep({ kind: 'custom', error, back })
    let address: string
    try {
      address = parseServerAddress(server)
    } catch (err) {
      fail(err instanceof Error ? err.message : String(err))
      return
    }
    if (!username || !password) {
      fail('Enter your username and password.')
      return
    }
    const id = ++attempt.current
    setStep({ kind: 'waiting', local: true })
    let mode: string
    try {
      mode = (await new HaloClient({ baseUrl: address, fetch: nativeFetch }).getAuthConfig()).mode
    } catch {
      if (id === attempt.current) fail('Couldn’t reach that server. Check the address and try again.')
      return
    }
    if (id !== attempt.current) return
    if (mode !== 'local') {
      fail('That server signs in through a web page, not a username and password.')
      return
    }
    try {
      await signInWithPassword(address, username, password)
    } catch (err) {
      if (id === attempt.current) fail(localSignInMessage(err))
      return
    }
    try {
      localStorage.setItem(LAST_SERVER_KEY, address)
    } catch {
      // Only a convenience for next time.
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
      else await method.addon.signIn(builtInServer, method.config, onOpened)
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

  /** Opens Halo on this PC; on success the session leaves this screen. */
  async function continueOnDevice() {
    const id = ++attempt.current
    const previous = step
    setDeviceError(null)
    setStep({ kind: 'preparing' })
    try {
      await continueWithoutAccount()
    } catch (err) {
      if (id !== attempt.current) return
      setDeviceError(`Halo could not set up on this PC: ${err instanceof Error ? err.message : String(err)}`)
      setStep(previous)
    }
  }

  function openCustom() {
    if (step.kind === 'discovering' || step.kind === 'unreachable' || step.kind === 'unsupported' || step.kind === 'local' || step.kind === 'browser') {
      setStep({ kind: 'custom', error: null, back: step })
    }
  }

  const passwordLink = (
    <button type="button" className="si-link" onClick={openCustom}>
      Use username &amp; password
    </button>
  )

  const noAccount =
    NO_ACCOUNT_STEPS.has(step.kind) &&
    (state === 'device' ? (
      <button type="button" className="si-btn si-btn-quiet" onClick={cancelSignIn}>
        Back to Halo
      </button>
    ) : (
      <button type="button" className="si-btn si-btn-quiet" onClick={() => void continueOnDevice()}>
        Continue without an account
      </button>
    ))

  return (
    <div className="si-screen">
      {BACKDROPS.map((imdbId, index) => (
        <div
          key={imdbId}
          className={`si-backdrop si-backdrop-${index + 1}`}
          style={{ backgroundImage: `url(${backdropUrl(imdbId)})` }}
          aria-hidden
        />
      ))}
      <div className="si-shade" aria-hidden />
      <div className="si-glow si-glow-a" aria-hidden />
      <div className="si-glow si-glow-c" aria-hidden />
      <TitleBar dark />

      <main className="si-stage">
        <div className="si-deck">
          <div className="si-fan" aria-hidden>
            <div className="si-ring" />
            {POSTERS.map((poster, index) => (
              <div key={poster.imdbId} className={`si-poster si-poster-${index + 1}`}>
                {poster.badge === 'offline' && (
                  <span className="si-chip si-chip-offline">
                    <FluentIcon glyph="check" size={10} />
                    OFFLINE
                  </span>
                )}
                {poster.badge === 'quality' && <span className="si-chip si-chip-gold">4K · HDR</span>}
                <img
                  src={posterUrl(poster.imdbId)}
                  alt=""
                  draggable={false}
                  onError={(event) => {
                    event.currentTarget.style.visibility = 'hidden'
                  }}
                />
              </div>
            ))}
          </div>

          <section className="si-card" aria-labelledby="si-title">
            <div className="si-meta">
              <span className="si-meta-brand">
                <HaloMark size={16} />
                HALO
              </span>
              <span>MEDIA CENTER</span>
            </div>

            <h1 className="si-title" id="si-title">
              Your shows,
              <br />
              <span>everywhere.</span>
            </h1>
            {!INTRO_HIDDEN.has(step.kind) && <Intro />}

            <div className="si-action">
              {step.kind === 'discovering' && (
                <>
                  <Status spinner>CONNECTING TO HALO</Status>
                  {noAccount}
                  {passwordLink}
                </>
              )}

              {step.kind === 'browser' && (
                <>
                  {step.error && <div className="si-error">{step.error}</div>}
                  <button type="button" className="si-btn si-btn-primary" onClick={() => void startBrowser(step.method)}>
                    <FluentIcon glyph="openInNewWindow" size={15} />
                    {methodLabel(step.method)}
                  </button>
                  {noAccount}
                  {passwordLink}
                </>
              )}

              {step.kind === 'local' && (
                <form className="si-form" onSubmit={(event) => void submitLocal(event)}>
                  <Field id="si-username" label="Username" value={username} onChange={setUsername} autoComplete="username" autoFocus />
                  <Field id="si-password" label="Password" value={password} onChange={setPassword} type="password" autoComplete="current-password" />
                  {step.error && <div className="si-error">{step.error}</div>}
                  <button type="submit" className="si-btn si-btn-primary si-submit">
                    Sign in
                  </button>
                  {noAccount}
                  <button type="button" className="si-link" onClick={openCustom}>
                    Use another server
                  </button>
                </form>
              )}

              {step.kind === 'custom' && (
                <form className="si-form" onSubmit={(event) => void submitCustom(event, step.back)}>
                  <Field
                    id="si-server"
                    label="Server address"
                    value={server}
                    onChange={setServer}
                    placeholder="https://your-server.example"
                    autoComplete="url"
                    autoFocus={!server}
                  />
                  <Field id="si-username" label="Username" value={username} onChange={setUsername} autoComplete="username" autoFocus={!!server} />
                  <Field id="si-password" label="Password" value={password} onChange={setPassword} type="password" autoComplete="current-password" />
                  {step.error && <div className="si-error">{step.error}</div>}
                  <button type="submit" className="si-btn si-btn-primary si-submit">
                    Sign in
                  </button>
                  <button type="button" className="si-link" onClick={() => setStep(step.back)}>
                    {backLabel(step.back)}
                  </button>
                </form>
              )}

              {step.kind === 'waiting' && (
                <>
                  <div className="si-wait">
                    <div className="si-wait-head">{step.local ? 'Signing in' : 'Check your browser'}</div>
                    <p>
                      {step.local
                        ? 'Halo is checking your account with your server.'
                        : 'We opened a sign-in page in your browser. Approve it there and Halo carries on by itself.'}
                    </p>
                    <Status spinner>{step.local ? 'CHECKING ACCOUNT' : 'WAITING FOR BROWSER'}</Status>
                  </div>
                  {!step.local && (
                    <>
                      <button
                        type="button"
                        className="si-btn si-btn-quiet si-after-wait"
                        disabled={!step.authUrl}
                        onClick={() => step.authUrl && void openUrl(step.authUrl).catch(() => undefined)}
                      >
                        Open the link again
                      </button>
                      <button type="button" className="si-link" onClick={() => cancelBrowser(step.method)}>
                        Cancel
                      </button>
                    </>
                  )}
                </>
              )}

              {step.kind === 'signedIn' && (
                <>
                  <div className="si-wait-head si-done">You’re all set</div>
                  <div className="si-account">
                    <div className="si-avatar" aria-hidden>
                      {(step.name || 'H').slice(0, 1).toUpperCase()}
                    </div>
                    <div className="si-account-text">
                      {step.name && <span className="si-account-name">{step.name}</span>}
                      <span className="si-account-label">{step.label}</span>
                    </div>
                  </div>
                  <div className="si-bar" aria-hidden>
                    <i />
                  </div>
                  <Status>{step.moving ? 'MOVING THIS PC’S LIBRARY INTO YOUR ACCOUNT…' : 'OPENING YOUR LIBRARY…'}</Status>
                </>
              )}

              {(step.kind === 'declined' || step.kind === 'expired') && (
                <>
                  <div className="si-wait">
                    <div className="si-wait-head">
                      {step.kind === 'declined' ? 'Sign-in was declined' : 'Sign-in request expired'}
                    </div>
                    <p>
                      {step.kind === 'declined'
                        ? 'You can try again whenever you’re ready.'
                        : 'This sign-in request has expired. Start a new one to continue.'}
                    </p>
                  </div>
                  <button type="button" className="si-btn si-btn-primary si-after-wait" onClick={() => void startBrowser(step.method)}>
                    Try again
                  </button>
                  {noAccount}
                </>
              )}

              {step.kind === 'unsupported' && (
                <>
                  <div className="si-wait">
                    <div className="si-wait-head">This build can’t sign in here</div>
                    <p>This server signs in with “{step.mode}”, which this build of Halo doesn’t include.</p>
                  </div>
                  <button type="button" className="si-btn si-btn-primary si-after-wait" onClick={() => void discover()}>
                    Retry
                  </button>
                  {noAccount}
                  {passwordLink}
                </>
              )}

              {step.kind === 'unreachable' && (
                <>
                  <div className="si-wait">
                    <div className="si-wait-head">Can’t reach Halo</div>
                    <p>Check your connection and try again.</p>
                  </div>
                  <button type="button" className="si-btn si-btn-primary si-after-wait" onClick={() => void discover()}>
                    Try again
                  </button>
                  {noAccount}
                  {passwordLink}
                  <button
                    type="button"
                    className="si-link si-details-toggle"
                    aria-expanded={detailsOpen}
                    onClick={() => setDetailsOpen((open) => !open)}
                  >
                    <FluentIcon glyph={detailsOpen ? 'chevronDown' : 'chevronRight'} size={12} />
                    Technical details
                  </button>
                  {detailsOpen && <div className="si-details">{step.detail}</div>}
                </>
              )}

              {step.kind === 'preparing' && <Status spinner>GETTING HALO READY ON THIS PC</Status>}

              {deviceError && NO_ACCOUNT_STEPS.has(step.kind) && <div className="si-error">{deviceError}</div>}
            </div>

            <div className="si-meta si-foot">
              <span>© 2026 LAST PROJECTS &amp; SHY</span>
              <button
                type="button"
                className="si-help"
                title="Open Halo support"
                onClick={() => void openUrl(HELP_URL).catch(() => undefined)}
              >
                NEED HELP?
              </button>
            </div>
          </section>
        </div>
      </main>
    </div>
  )
}

/**
 * The feature chips' glyphs: the phone's own (Material Icons' extension,
 * download outline, subtitles and refresh, Apache-2.0), so both sign-in
 * screens draw the same four.
 */
const INTRO_CHIPS: ReadonlyArray<{ label: string; path: string }> = [
  {
    label: 'Addons',
    path:
      'M20.5 11H19V7c0-1.1-.9-2-2-2h-4V3.5C13 2.12 11.88 1 10.5 1S8 2.12 8 3.5V5H4c-1.1 0-1.99.9-1.99 ' +
      '2v3.8H3.5c1.49 0 2.7 1.21 2.7 2.7s-1.21 2.7-2.7 2.7H2V20c0 1.1.9 2 2 2h3.8v-1.5c0-1.49 ' +
      '1.21-2.7 2.7-2.7 1.49 0 2.7 1.21 2.7 2.7V22H17c1.1 0 2-.9 2-2v-4h1.5c1.38 0 2.5-1.12 ' +
      '2.5-2.5S21.88 11 20.5 11z',
  },
  { label: 'Downloads', path: 'M19 9h-4V3H9v6H5l7 7 7-7zm-8 2V5h2v6h1.17L12 13.17 9.83 11H11zm-6 7h14v2H5z' },
  {
    label: 'Subtitles',
    path:
      'M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zM4 12h4v2H4v-2zm10 ' +
      '6H4v-2h10v2zm6 0h-4v-2h4v2zm0-4H10v-2h10v2z',
  },
  {
    label: 'Sync',
    path:
      'M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-8 8s3.58 8 8 8c3.73 0 6.84-2.55 ' +
      '7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 ' +
      '4.22 1.78L13 11h7V4l-2.35 2.35z',
  },
]

function Intro() {
  return (
    <>
      <p className="si-tagline">Stream your addons, download for later, pick up on any device.</p>
      <ul className="si-chips" aria-label="Includes">
        {INTRO_CHIPS.map((chip) => (
          <li key={chip.label}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden>
              <path d={chip.path} />
            </svg>
            {chip.label}
          </li>
        ))}
      </ul>
    </>
  )
}

/** The mono status line: a spinner while something is under way, else plain text. */
function Status({ spinner = false, children }: { spinner?: boolean; children: ReactNode }) {
  return (
    <div className="si-status" role="status">
      {spinner && <span className="si-spinner" aria-hidden />}
      {children}
    </div>
  )
}

function Field({
  id,
  label,
  value,
  onChange,
  type = 'text',
  placeholder,
  autoComplete,
  autoFocus = false,
}: {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
  type?: 'text' | 'password'
  placeholder?: string
  autoComplete: string
  autoFocus?: boolean
}) {
  return (
    <div className="si-field">
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        type={type}
        value={value}
        placeholder={placeholder}
        autoComplete={autoComplete}
        autoFocus={autoFocus}
        spellCheck={false}
        maxLength={type === 'password' ? 4096 : 512}
        onChange={(event) => onChange(event.target.value)}
      />
    </div>
  )
}
