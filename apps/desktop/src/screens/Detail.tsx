import type { MetaVideo } from '@halo/core'
import { useMemo, useState } from 'react'
import { useNav } from '../nav'
import { useMeta } from '../queries'

/**
 * Title page: hero art + description, then the playback entry point — a
 * Sources button for movies, a season/episode list for series. `videoId` for
 * episodes is the addon-defined video id (e.g. "tt0944947:1:2").
 */
export function Detail({ type, id }: { type: string; id: string }) {
  const { pop, push } = useNav()
  const { data: meta, isLoading, error } = useMeta(type, id)

  const itemId = `${type}:${id}`
  const seasons = useMemo(() => {
    const nums = [...new Set((meta?.videos ?? []).map((v) => v.season ?? 0))]
    // Specials (season 0) list last, like every player UI.
    return nums.sort((a, b) => (a === 0 ? 1 : b === 0 ? -1 : a - b))
  }, [meta])
  const [season, setSeason] = useState<number | null>(null)
  const activeSeason = season ?? seasons[0] ?? null
  const episodes = useMemo(
    () =>
      (meta?.videos ?? [])
        .filter((v) => (v.season ?? 0) === activeSeason)
        .sort((a, b) => (a.episode ?? 0) - (b.episode ?? 0)),
    [meta, activeSeason],
  )

  if (isLoading) return <Shell onBack={pop}>Loading…</Shell>
  if (error || !meta) return <Shell onBack={pop}>Could not load this title: {String(error ?? 'not found')}</Shell>

  const openStreams = (video?: MetaVideo) =>
    push({
      name: 'streams',
      type,
      videoId: video?.id ?? id,
      itemId,
      metaId: id,
      title: video ? (video.title ?? video.name ?? meta.name) : meta.name,
      showName: meta.name,
      ...(video && video.season != null && video.episode != null
        ? { episodeLabel: `S${video.season}E${video.episode}` }
        : {}),
      ...(meta.poster ? { poster: meta.poster } : {}),
    })

  return (
    <div style={{ height: '100%', overflowY: 'auto' }}>
      <div
        style={{
          position: 'relative',
          minHeight: 280,
          padding: '24px 32px',
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'flex-end',
          backgroundImage: meta.background ? `url(${meta.background})` : undefined,
          backgroundSize: 'cover',
          backgroundPosition: 'center',
        }}
      >
        <div
          style={{
            position: 'absolute',
            inset: 0,
            background: 'linear-gradient(rgba(10,12,17,0.55), rgba(10,12,17,0.85) 75%, var(--background))',
          }}
        />
        <div style={{ position: 'relative' }}>
          <button className="btn btn-glass" type="button" onClick={pop} style={{ marginBottom: 16 }}>
            ← Back
          </button>
          <div className="t-large-title">{meta.name}</div>
          <div className="t-caption" style={{ marginTop: 4 }}>
            {[meta.releaseInfo, meta.runtime, meta.imdbRating && `★ ${meta.imdbRating}`]
              .filter(Boolean)
              .join(' · ')}
          </div>
        </div>
      </div>

      <div style={{ padding: '20px 32px 48px', maxWidth: 760 }}>
        {meta.description && (
          <p style={{ color: 'var(--text-dim)', lineHeight: 1.55, marginTop: 0 }}>{meta.description}</p>
        )}

        {type !== 'series' || !meta.videos?.length ? (
          <button className="btn btn-primary" type="button" onClick={() => openStreams()}>
            Sources
          </button>
        ) : (
          <>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', margin: '8px 0 16px' }}>
              {seasons.map((s) => (
                <button
                  key={s}
                  type="button"
                  className="btn btn-glass"
                  onClick={() => setSeason(s)}
                  style={
                    s === activeSeason
                      ? { background: 'var(--accent)', borderColor: 'var(--accent)' }
                      : undefined
                  }
                >
                  {s === 0 ? 'Specials' : `Season ${s}`}
                </button>
              ))}
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              {episodes.map((v) => (
                <button
                  key={v.id}
                  type="button"
                  onClick={() => openStreams(v)}
                  className="episode-row"
                >
                  <span className="t-callout" style={{ marginRight: 10 }}>
                    E{v.episode ?? '?'}
                  </span>
                  <span>{v.title ?? v.name ?? v.id}</span>
                </button>
              ))}
            </div>
          </>
        )}
      </div>
    </div>
  )
}

function Shell({ children, onBack }: { children: React.ReactNode; onBack: () => void }) {
  return (
    <div style={{ padding: 32 }}>
      <button className="btn btn-glass" type="button" onClick={onBack} style={{ marginBottom: 16 }}>
        ← Back
      </button>
      <div className="t-caption">{children}</div>
    </div>
  )
}
