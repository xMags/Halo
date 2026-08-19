import { useState, type FormEvent } from 'react'
import { WindowButtons } from '../components/WindowButtons'
import { signInWithPassword } from '../localAuth'
import { signInWithOidc } from '../oidc'
import { useSession } from '../session'

/**
 * Sign-in, branched by the server's declared auth mode — the two modes are
 * deployment-exclusive, so exactly one of them is ever drawn. Local mode posts
 * the password form; OIDC opens the system browser for the PKCE exchange and
 * waits for the loopback redirect.
 */
export function Login() {
  const { serverUrl, authConfig, signedIn, disconnect } = useSession()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const host = serverUrl?.replace(/^https?:\/\//, '') ?? ''

  async function submitLocal(e: FormEvent) {
    e.preventDefault()
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
      <div className="auth-titlebar" data-tauri-drag-region>
        <WindowButtons />
      </div>
      <div className="auth-stage">
        <div className="auth-glow" />
        <div className="auth-form">
          <div className="logo-lockup">
            <span className="logo-mark" />
            <span className="logo-word">HALO</span>
          </div>
          <div className="auth-title">Sign in</div>
          <div className="auth-host">{host}</div>

          {!authConfig && (
            <div className="auth-status" style={{ color: 'var(--text-dim)' }}>
              <span className="auth-status-dot" />
              <span>CONTACTING SERVER…</span>
            </div>
          )}

          {authConfig?.mode === 'local' && (
            <form onSubmit={submitLocal}>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 9, marginTop: 22 }}>
                <input
                  className="field"
                  placeholder="Username"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  autoFocus
                  spellCheck={false}
                />
                <input
                  className="field"
                  placeholder="Password"
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                />
              </div>
              {error && (
                <div className="error-text" style={{ marginTop: 11 }}>
                  {error}
                </div>
              )}
              <button
                type="submit"
                className="btn-primary auth-submit"
                style={{ marginTop: 16 }}
                disabled={busy || !username || !password}
              >
                {busy ? 'Signing in…' : 'Sign in'}
              </button>
            </form>
          )}

          {authConfig?.mode === 'oidc' && (
            <>
              <div className="auth-sub" style={{ marginTop: 20 }}>
                This server signs in through {new URL(authConfig.issuer).host}. Your browser will
                open; come back here once you have signed in.
              </div>
              {error && (
                <div className="error-text" style={{ marginTop: 11 }}>
                  {error}
                </div>
              )}
              <button
                type="button"
                className="btn-primary auth-submit"
                disabled={busy}
                onClick={() => void submitOidc()}
              >
                {busy ? 'Waiting for the browser…' : 'Continue with your identity provider'}
              </button>
            </>
          )}

          <div className="or-divider">
            <span />
            <em>OR</em>
            <span />
          </div>
          <button type="button" className="btn-glass auth-alt" onClick={disconnect}>
            Use a different server
          </button>
        </div>
      </div>
    </div>
  )
}
