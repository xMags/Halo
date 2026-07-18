import { HaloClient } from '@halo/core'
import { fetch as nativeFetch } from '@tauri-apps/plugin-http'
import { useState, type FormEvent } from 'react'
import { useSession } from '../session'

/**
 * First-run screen: point the app at a Halo server. Validates by fetching the
 * public /auth/config, which doubles as auth-mode discovery.
 */
export function Connect() {
  const { connect } = useSession()
  const [url, setUrl] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    const trimmed = url.trim().replace(/\/$/, '')
    if (!trimmed) return
    const normalized = /^https?:\/\//.test(trimmed) ? trimmed : `https://${trimmed}`
    setBusy(true)
    setError(null)
    try {
      // Probe client: unauthenticated on purpose; the real client is built
      // after the server URL is committed.
      const probe = new HaloClient({ baseUrl: normalized, fetch: nativeFetch })
      const config = await probe.getAuthConfig()
      connect(normalized, config)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not reach the server')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div style={{ height: '100%', display: 'grid', placeItems: 'center' }}>
      <form onSubmit={submit} style={{ width: 380, display: 'flex', flexDirection: 'column', gap: 16 }}>
        <div>
          <div className="t-large-title">Halo</div>
          <div className="t-caption" style={{ marginTop: 4 }}>
            Connect to your Halo server to get started.
          </div>
        </div>
        <input
          className="field"
          placeholder="https://halo.example.com"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          autoFocus
          spellCheck={false}
        />
        {error && <div className="error-text">{error}</div>}
        <button className="btn btn-primary" type="submit" disabled={busy || !url.trim()}>
          {busy ? 'Connecting…' : 'Connect'}
        </button>
      </form>
    </div>
  )
}
