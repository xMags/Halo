import { HaloClient, type AuthConfig } from '@halo/core'
import { fetch as nativeFetch } from '@tauri-apps/plugin-http'
import { useEffect, useState, type FormEvent } from 'react'
import { WindowButtons } from '../components/WindowButtons'
import { DEFAULT_SERVER_URL } from '../api'
import { useSession } from '../session'

const PROBE_DEBOUNCE_MS = 600

/** Accepts a bare host; https is assumed unless the user says otherwise. */
function normalizeUrl(raw: string): string {
  const trimmed = raw.trim().replace(/\/+$/, '')
  return /^https?:\/\//.test(trimmed) ? trimmed : `https://${trimmed}`
}

type Probe =
  | { state: 'idle' }
  | { state: 'checking' }
  | { state: 'reached'; url: string; config: AuthConfig }
  | { state: 'failed'; message: string }

/**
 * First-run screen: point the app at a Halo server. The address is probed as
 * it is typed — `/auth/config` is public and doubles as auth-mode discovery,
 * so a reachable server can say which sign-in it will ask for before the user
 * commits to it.
 */
export function Connect() {
  const { connect } = useSession()
  const [url, setUrl] = useState(DEFAULT_SERVER_URL)
  const [probe, setProbe] = useState<Probe>({ state: 'idle' })

  useEffect(() => {
    const candidate = url.trim()
    if (candidate.length < 4) {
      setProbe({ state: 'idle' })
      return
    }
    setProbe({ state: 'checking' })
    let cancelled = false
    const timer = setTimeout(() => {
      const normalized = normalizeUrl(candidate)
      // Probe client: unauthenticated on purpose. The real client is built
      // only once the URL is committed.
      new HaloClient({ baseUrl: normalized, fetch: nativeFetch })
        .getAuthConfig()
        .then((config) => {
          if (!cancelled) setProbe({ state: 'reached', url: normalized, config })
        })
        .catch((err: unknown) => {
          if (cancelled) return
          setProbe({
            state: 'failed',
            message: err instanceof Error ? err.message : 'Could not reach the server',
          })
        })
    }, PROBE_DEBOUNCE_MS)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [url])

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (probe.state !== 'reached') return
    connect(probe.url, probe.config)
  }

  return (
    <div className="auth-screen">
      <div className="auth-titlebar" data-tauri-drag-region>
        <WindowButtons />
      </div>
      <div className="auth-stage">
        <div className="auth-glow" />
        <form className="auth-form" onSubmit={submit}>
          <div className="logo-lockup">
            <span className="logo-mark" />
            <span className="logo-word">HALO</span>
          </div>
          <div className="auth-title">Point Halo at your server.</div>
          <div className="auth-sub">
            Your library, watch history and addons live on your own Halo instance. Enter its
            address to begin.
          </div>

          <input
            className="field field-mono"
            style={{ marginTop: 24, fontSize: 13 }}
            placeholder="https://halo.example.com"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            autoFocus
            spellCheck={false}
          />

          <ProbeLine probe={probe} />

          <button
            type="submit"
            className="btn-primary auth-submit"
            disabled={probe.state !== 'reached'}
          >
            Continue
          </button>

          <div style={{ marginTop: 18, fontSize: 11.5, color: 'var(--text-dim)' }}>
            Need a server? Halo is self-hosted — run the API from the project&apos;s repository and
            point this at it.
          </div>
        </form>
      </div>
    </div>
  )
}

function ProbeLine({ probe }: { probe: Probe }) {
  if (probe.state === 'idle') return <div style={{ height: 27 }} />
  if (probe.state === 'checking') {
    return (
      <div className="auth-status" style={{ color: 'var(--text-dim)' }}>
        <span className="auth-status-dot" />
        <span>CHECKING…</span>
      </div>
    )
  }
  if (probe.state === 'failed') {
    return (
      <div className="auth-status" style={{ color: 'var(--danger)' }}>
        <span className="auth-status-dot" />
        <span className="ellipsis">NOT REACHED · {probe.message.toUpperCase()}</span>
      </div>
    )
  }
  return (
    <div className="auth-status" style={{ color: 'var(--success)' }}>
      <span className="auth-status-dot" />
      <span>REACHED · {probe.config.mode.toUpperCase()} AUTH</span>
    </div>
  )
}
