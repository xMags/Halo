import type { MetaPreview } from '@halo/core'
import { useNav } from '../nav'
import { browsableCatalogs, useCatalog, useEffectiveAddons, type BrowsableCatalog } from '../queries'
import { useSession } from '../session'

/** How many catalog rows Home renders (each is one server round-trip). */
const MAX_ROWS = 8

export function Home() {
  const { signOut } = useSession()
  const { data: addons, isLoading, error } = useEffectiveAddons()
  const rows = addons ? browsableCatalogs(addons).slice(0, MAX_ROWS) : []

  return (
    <div style={{ height: '100%', overflowY: 'auto', padding: '24px 0 48px' }}>
      <header
        style={{
          display: 'flex',
          alignItems: 'baseline',
          justifyContent: 'space-between',
          padding: '0 32px 8px',
        }}
      >
        <div className="t-large-title">Halo</div>
        <button className="btn btn-glass" type="button" onClick={signOut}>
          Sign out
        </button>
      </header>

      {error && <div className="error-text" style={{ padding: '0 32px' }}>{String(error)}</div>}
      {isLoading && <div className="t-caption" style={{ padding: '0 32px' }}>Loading addons…</div>}
      {addons && rows.length === 0 && (
        <div className="t-caption" style={{ padding: '0 32px' }}>
          No browsable catalogs — add addons with catalogs (e.g. Cinemeta) in a Stremio-compatible
          manifest.
        </div>
      )}

      {rows.map((row) => (
        <CatalogRow key={`${row.addonId}/${row.catalog.type}/${row.catalog.id}`} row={row} />
      ))}
    </div>
  )
}

function CatalogRow({ row }: { row: BrowsableCatalog }) {
  const { data: metas, isLoading } = useCatalog(row.addonId, row.catalog.type, row.catalog.id)

  // A catalog that errored or came back empty doesn't earn a row.
  if (!isLoading && (!metas || metas.length === 0)) return null

  return (
    <section style={{ marginTop: 24 }}>
      <div className="t-heading" style={{ padding: '0 32px 12px' }}>
        {row.title}
      </div>
      <div style={{ display: 'flex', gap: 12, overflowX: 'auto', padding: '0 32px 8px' }}>
        {(metas ?? []).slice(0, 30).map((meta) => (
          <PosterCard key={`${meta.type}:${meta.id}`} meta={meta} />
        ))}
        {isLoading && <div className="t-caption">Loading…</div>}
      </div>
    </section>
  )
}

function PosterCard({ meta }: { meta: MetaPreview }) {
  const { push } = useNav()
  return (
    <button
      type="button"
      onClick={() => push({ name: 'detail', type: meta.type, id: meta.id })}
      title={meta.name}
      style={{
        flex: '0 0 auto',
        width: 112,
        padding: 0,
        border: 'none',
        background: 'transparent',
        cursor: 'pointer',
        textAlign: 'left',
        color: 'var(--text)',
      }}
    >
      {meta.poster ? (
        <img
          src={meta.poster}
          alt=""
          loading="lazy"
          style={{
            width: 112,
            height: 168,
            objectFit: 'cover',
            borderRadius: 'var(--radius-md)',
            border: '1px solid var(--glass-border)',
            display: 'block',
          }}
        />
      ) : (
        <div
          style={{
            width: 112,
            height: 168,
            borderRadius: 'var(--radius-md)',
            background: 'var(--surface-high)',
            display: 'grid',
            placeItems: 'center',
            fontSize: 11,
            color: 'var(--text-dim)',
            padding: 8,
            textAlign: 'center',
          }}
        >
          {meta.name}
        </div>
      )}
      <div
        className="t-caption"
        style={{
          marginTop: 6,
          whiteSpace: 'nowrap',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          width: 112,
        }}
      >
        {meta.name}
      </div>
    </button>
  )
}
