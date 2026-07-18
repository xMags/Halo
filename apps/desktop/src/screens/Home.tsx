import type { AddonsResponse, Me } from '@halo/core'
import { useEffect, useState } from 'react'
import { getClient } from '../api'
import { useSession } from '../session'

/**
 * Placeholder Home: proves the authenticated wire-through (who am I, which
 * addons are effective). Catalog rows, streams and the player arrive with the
 * playback milestone.
 */
export function Home() {
  const { serverUrl, signOut } = useSession()
  const [me, setMe] = useState<Me | null>(null)
  const [addons, setAddons] = useState<AddonsResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    Promise.all([getClient().getMe(), getClient().getAddons()])
      .then(([meRes, addonsRes]) => {
        if (cancelled) return
        setMe(meRes)
        setAddons(addonsRes)
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Failed to load')
      })
    return () => {
      cancelled = true
    }
  }, [])

  return (
    <div style={{ padding: 32, display: 'flex', flexDirection: 'column', gap: 24, height: '100%' }}>
      <header style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between' }}>
        <div className="t-large-title">Home</div>
        <button className="btn btn-glass" type="button" onClick={signOut}>
          Sign out
        </button>
      </header>

      {error && <div className="error-text">{error}</div>}

      <section
        style={{
          padding: 20,
          borderRadius: 16,
          background: 'var(--surface)',
          border: '1px solid var(--border)',
          maxWidth: 520,
        }}
      >
        <div className="t-overline">Connected</div>
        <div className="t-heading" style={{ marginTop: 8 }}>
          {me ? me.username : '…'}
          {me?.isAdmin ? <span className="t-caption"> · admin</span> : null}
        </div>
        <div className="t-caption" style={{ marginTop: 4 }}>
          {serverUrl}
        </div>
        <div className="t-caption" style={{ marginTop: 12 }}>
          {addons
            ? `${addons.global.length} global addon${addons.global.length === 1 ? '' : 's'}, ${addons.user.length} personal`
            : 'Loading addons…'}
        </div>
      </section>

      <div className="t-caption">Catalog, search and playback land in the next milestone.</div>
    </div>
  )
}
