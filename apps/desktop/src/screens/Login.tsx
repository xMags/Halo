import { useState, type FormEvent } from 'react'
import { signInWithPassword } from '../localAuth'
import { useSession } from '../session'

/**
 * Sign-in, branched by the server's declared auth mode. Local mode posts the
 * password form; OIDC (the ditto deployment) needs the system-browser PKCE
 * flow — a separate milestone — so it presents as not-yet-supported rather
 * than a broken form.
 */
export function Login() {
  const { serverUrl, authConfig, signedIn, disconnect } = useSession()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!serverUrl) return
    setBusy(true)
    setError(null)
    try {
      await signInWithPassword(serverUrl, username, password)
      signedIn()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Sign-in failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div style={{ height: '100%', display: 'grid', placeItems: 'center' }}>
      <div style={{ width: 380, display: 'flex', flexDirection: 'column', gap: 16 }}>
        <div>
          <div className="t-title">Sign in</div>
          <div className="t-caption" style={{ marginTop: 4 }}>
            {serverUrl}
          </div>
        </div>

        {!authConfig && <div className="t-caption">Contacting server…</div>}

        {authConfig?.mode === 'local' && (
          <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
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
            {error && <div className="error-text">{error}</div>}
            <button className="btn btn-primary" type="submit" disabled={busy || !username || !password}>
              {busy ? 'Signing in…' : 'Sign in'}
            </button>
          </form>
        )}

        {authConfig?.mode === 'oidc' && (
          <div className="t-caption">
            This server uses single sign-on ({authConfig.issuer}). Desktop SSO isn&apos;t wired up yet — it&apos;s a
            planned milestone.
          </div>
        )}

        <button className="btn btn-glass" type="button" onClick={disconnect}>
          Use a different server
        </button>
      </div>
    </div>
  )
}
