import { useState, type FormEvent } from 'react'
import mark from '../assets/halo-mark.png'
import { TitleBar } from '../components/TitleBar'
import { signInWithPassword } from './localAuth'
import { signInWithOidc } from './oidc'
import { useSession } from './session'

/**
 * Sign-in, branched by the server's declared auth mode — the two modes are
 * deployment-exclusive, so exactly one of them is ever drawn. Local mode posts
 * the password form; OIDC opens the system browser for the PKCE exchange and
 * waits for the loopback redirect. Both use the same 404px lockup.
 */
export function Login() {
  const { serverUrl, authConfig, signedIn, disconnect } = useSession()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const host = serverUrl?.replace(/^https?:\/\//, '') ?? ''

  async function submitLocal(event: FormEvent) {
    event.preventDefault()
    if (!serverUrl) return
    setBusy(true)
    setError(null)
    try {
      await signInWithPassword(serverUrl, username, password)
      signedIn('local')
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  async function submitOidc() {
    if (authConfig?.mode !== 'oidc') return
    setBusy(true)
    setError(null)
    try {
      await signInWithOidc(authConfig)
      signedIn('oidc')
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="auth-screen">
      <div className="glow glow-a" />
      <div className="glow glow-b" />
      <TitleBar />
      <div className="auth-stage">
        <div className="auth-col">
          <div className="logo-lockup">
            <img className="logo-mark" src={mark} alt="" />
            <div className="logo-word">HALO</div>
          </div>
          <div className="auth-title">Sign in to Halo</div>
          <div className="auth-host">{host}</div>

          {!authConfig && (
            <div className="auth-sub">
              <span className="spinner" /> Contacting the server…
            </div>
          )}

          {authConfig?.mode === 'local' && (
            <form onSubmit={submitLocal}>
              <div className="auth-sub">Use the account created on your Halo server.</div>
              <div className="field-block" style={{ marginTop: 22 }}>
                <label className="field-label" htmlFor="halo-username">
                  Username
                </label>
                <input
                  id="halo-username"
                  className="field"
                  value={username}
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
                  onChange={(e) => setPassword(e.target.value)}
                />
              </div>
              {error && <div className="auth-error">{error}</div>}
              <button
                type="submit"
                className="btn-accent h36 btn-block"
                style={{ marginTop: 20 }}
                disabled={busy || !username || !password}
              >
                {busy ? 'Signing in…' : 'Sign in'}
              </button>
            </form>
          )}

          {authConfig?.mode === 'oidc' && (
            <>
              <div className="auth-sub">
                This server signs in through {new URL(authConfig.issuer).host}. Your browser will
                open; come back here once you have signed in.
              </div>
              {error && <div className="auth-error">{error}</div>}
              <button
                type="button"
                className="btn-accent h36 btn-block"
                style={{ marginTop: 20 }}
                disabled={busy}
                onClick={() => void submitOidc()}
              >
                {busy ? 'Waiting for the browser…' : 'Continue with your identity provider'}
              </button>
            </>
          )}

          <button type="button" className="auth-help soon" title="Coming soon">
            Having trouble signing in?
          </button>
          <button
            type="button"
            className="btn-link"
            style={{ alignSelf: 'center', marginTop: 2 }}
            onClick={disconnect}
          >
            Use a different server
          </button>
        </div>
      </div>
    </div>
  )
}
