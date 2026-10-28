import type { MetaDetail, MetaPreview } from '@halo/core'
import { useState } from 'react'
import { ScrollRow } from '../../components/ScrollRow'
import { initials } from '../../format'
import { useNav } from '../../nav'
import { useEffectiveAddons, useGenreCatalog } from '../../queries'
import { pickGenreCatalog, type GenreCatalog } from '../similarTitles'
import { useTitleMenu } from '../titleMenu'
import { aboutRows, creditsOf, type Credit } from './detailFacts'

/** One shelf's worth: enough to page through, not a whole catalog. */
const SIMILAR_LIMIT = 20

/**
 * Art that fades in over a shimmer once it has loaded, instead of popping;
 * a picture that fails leaves the fallback underneath.
 */
function FadeArt({ src, fallback }: { src: string | null; fallback?: string }) {
  const [state, setState] = useState<'loading' | 'loaded' | 'failed'>(src ? 'loading' : 'failed')
  return (
    <div className={`fade-art ${state === 'loading' ? 'fade-art-loading' : ''}`}>
      {state === 'failed' && fallback && <span className="fade-art-fallback">{fallback}</span>}
      {src && state !== 'failed' && (
        <img
          src={src}
          alt=""
          loading="lazy"
          draggable={false}
          className={state === 'loaded' ? 'fade-art-in' : ''}
          onLoad={() => setState('loaded')}
          onError={() => setState('failed')}
        />
      )}
    </div>
  )
}

/** The pictured cast when the addon sends one, else names on initials; drawn only when there is someone. */
export function CastPanel({ meta }: { meta: MetaDetail }) {
  const credits = creditsOf(meta)
  if (credits.length === 0) return null
  return (
    <div className="glass detail-panel">
      <ScrollRow title="Cast and crew" note={`${credits.length} ${credits.length === 1 ? 'person' : 'people'}`}>
        {credits.map((credit) => (
          <CreditCard key={credit.name} credit={credit} />
        ))}
      </ScrollRow>
    </div>
  )
}

function CreditCard({ credit }: { credit: Credit }) {
  return (
    <div className="credit" title={credit.role ? `${credit.name} · ${credit.role}` : credit.name}>
      <FadeArt src={credit.photo} fallback={initials(credit.name)} />
      <div className="credit-name ellipsis">{credit.name}</div>
      {credit.role && <div className="credit-role ellipsis">{credit.role}</div>}
    </div>
  )
}

/** Facts the addon sends, as a quiet two-column list; drawn only when there are some. */
export function AboutPanel({ meta }: { meta: MetaDetail }) {
  const rows = aboutRows(meta)
  if (rows.length === 0) return null
  return (
    <div className="glass detail-panel detail-about">
      <h2 className="srow-title">About</h2>
      <dl className="about-list">
        {rows.map(([label, value]) => (
          <div key={label} className="about-row">
            <dt>{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
    </div>
  )
}

/**
 * Titles of the same type that share this one's genre, from the first addon
 * catalog that can filter by it. Drawn only once there is something to show:
 * no such catalog, a failed request or an empty answer leave no trace.
 */
export function MoreLikeThis({ type, id, genres }: { type: string; id: string; genres: string[] }) {
  const { data: addons } = useEffectiveAddons()
  const source = addons ? pickGenreCatalog(addons, type, genres) : null
  if (!source) return null
  return <GenreShelf source={source} type={type} excludeId={id} />
}

function GenreShelf({ source, type, excludeId }: { source: GenreCatalog; type: string; excludeId: string }) {
  const { data } = useGenreCatalog(source.addonId, type, source.catalogId, source.genre)
  const seen = new Set<string>([`${type}:${excludeId}`])
  const metas = (data ?? [])
    .filter((meta) => {
      // Catalogs can repeat a title; a shelf must not draw it twice.
      const key = `${meta.type}:${meta.id}`
      if (seen.has(key)) return false
      seen.add(key)
      return true
    })
    .slice(0, SIMILAR_LIMIT)
  if (metas.length === 0) return null
  return (
    <div className="glass detail-panel">
      <ScrollRow title="More like this" note={source.genre}>
        {metas.map((meta) => (
          <SimilarCard key={`${meta.type}:${meta.id}`} meta={meta} />
        ))}
      </ScrollRow>
    </div>
  )
}

function SimilarCard({ meta }: { meta: MetaPreview }) {
  const { push } = useNav()
  const titleMenu = useTitleMenu()
  return (
    <button
      type="button"
      className="similar"
      title={meta.name}
      onClick={() => push({ name: 'detail', type: meta.type, id: meta.id })}
      onContextMenu={(event) => titleMenu.forPoster(event, meta)}
    >
      <div className="similar-art">
        <FadeArt src={meta.poster ?? null} fallback={meta.name} />
        {meta.imdbRating && <span className="similar-rating">★ {meta.imdbRating}</span>}
      </div>
      <div className="similar-name ellipsis">{meta.name}</div>
      {meta.releaseInfo && <div className="similar-year">{meta.releaseInfo}</div>}
    </button>
  )
}
