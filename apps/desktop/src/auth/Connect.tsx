import { HaloClient, type AuthConfig } from '@halo/core'
import { fetch as nativeFetch } from '@tauri-apps/plugin-http'
import { useEffect, useState, type FormEvent } from 'react'
import mark from '../assets/halo-mark.png'
import { TitleBar } from '../components/TitleBar'
import { DEFAULT_SERVER_URL } from '../api'
import { useSession } from './session'

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
 * commits to it. It shares Login's lockup and 404px column.
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

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (probe.state !== 'reached') return
    connect(probe.url, probe.config)
  }

  return (
    <div className="auth-screen">
      <div className="glow glow-a" />
      <div className="glow glow-b" />
      <TitleBar />
      <div className="auth-stage">
        <form className="auth-col" onSubmit={submit}>
          <div className="logo-lockup">
            <img className="logo-mark" src={mark} alt="" />
            <div className="logo-word">HALO</div>
          </div>
          <div className="auth-title">Point Halo at your server.</div>
          <div className="auth-sub">
            Your library, watch history and addons live on your own Halo instance. Enter its
            address to begin.
          </div>

          <div className="field-block" style={{ marginTop: 22 }}>
            <label className="field-label" htmlFor="halo-server">
              Server address
            </label>
            <input
              id="halo-server"
              className="field field-mono"
              placeholder="https://halo.example.com"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              autoFocus
              spellCheck={false}
            />
          </div>

          <ProbeLine probe={probe} />

          <button
            type="submit"
            className="btn-accent h36 btn-block"
            disabled={probe.state !== 'reached'}
          >
            Continue
          </button>

          <div className="auth-sub" style={{ fontSize: 14, color: 'var(--t3)' }}>
            Need a server? Halo is self-hosted — run the API from the project&apos;s repository and
            point this at it.
          </div>
        </form>
      </div>
    </div>
  )
}

function ProbeLine({ probe }: { probe: Probe }) {
  if (probe.state === 'idle') return <div style={{ height: 30 }} />
  if (probe.state === 'checking') {
    return (
      <div className="auth-host" style={{ color: 'var(--t3)' }}>
        <span className="spinner" /> CHECKING…
      </div>
    )
  }
  if (probe.state === 'failed') {
    return (
      <div className="auth-host ellipsis" style={{ color: 'var(--cr)' }}>
        NOT REACHED · {probe.message.toUpperCase()}
      </div>
    )
  }
  return (
    <div className="auth-host" style={{ color: 'var(--su)' }}>
      REACHED · {probe.config.mode.toUpperCase()} AUTH
    </div>
  )
}
