import { useState, type FormEvent } from 'react'
import { signInWithPassword } from '../localAuth'
import { signInWithOidc } from '../oidc'
import { useSession } from '../session'

/**
 * Sign-in, branched by the server's declared auth mode. Local mode posts the
 * password form; OIDC opens the system browser for the PKCE dance and waits
 * for the loopback redirect.
 */
export function Login() {
  const { serverUrl, authConfig, signedIn, disconnect } = useSession()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

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
          <form onSubmit={submitLocal} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
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
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <div className="t-caption">
              This server signs in through {new URL(authConfig.issuer).host}. Your browser will open;
              come back here once you&apos;ve signed in.
            </div>
            {error && <div className="error-text">{error}</div>}
            <button className="btn btn-primary" type="button" disabled={busy} onClick={() => void submitOidc()}>
              {busy ? 'Waiting for the browser…' : 'Sign in with browser'}
            </button>
          </div>
        )}

        <button className="btn btn-glass" type="button" onClick={disconnect}>
          Use a different server
        </button>
      </div>
    </div>
  )
}
