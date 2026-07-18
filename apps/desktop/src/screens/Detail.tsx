import type { MetaVideo, WatchState } from '@halo/core'
import { useMemo, useState } from 'react'
import { Icon } from '../components/Icon'
import { useNav } from '../nav'
import {
  libraryItemFromMeta,
  useLibrary,
  useMeta,
  useUpsertLibrary,
  useWatchStates,
} from '../queries'

/**
 * Title page: hero art + description, then the playback entry point — a
 * Sources button for movies, a season/episode list for series. `videoId` for
 * episodes is the addon-defined video id (e.g. "tt0944947:1:2").
 */
export function Detail({ type, id }: { type: string; id: string }) {
  const { pop, push } = useNav()
  const { data: meta, isLoading, error } = useMeta(type, id)
  const { data: library } = useLibrary()
  const { data: watchStates } = useWatchStates()
  const upsertLibrary = useUpsertLibrary()

  const itemId = `${type}:${id}`
  const libraryEntry = (library ?? []).find((item) => item.id === itemId && !item.removedAt)

  const seasons = useMemo(() => {
    const nums = [...new Set((meta?.videos ?? []).map((v) => v.season ?? 0))]
    // Specials (season 0) list last, like every player UI.
    return nums.sort((a, b) => (a === 0 ? 1 : b === 0 ? -1 : a - b))
  }, [meta])

  // Open on the season of the most recently watched episode, not season 1 —
  // mid-binge, "the season I'm in" is almost always where the next click goes.
  const lastWatchedSeason = useMemo(() => {
    const videosById = new Map((meta?.videos ?? []).map((video) => [video.id, video]))
    const latest = (watchStates ?? [])
      .filter((s) => s.itemId === itemId && videosById.has(s.videoId))
      .sort((a, b) => b.updatedAt - a.updatedAt)[0]
    return latest ? (videosById.get(latest.videoId)!.season ?? null) : null
  }, [watchStates, itemId, meta])

  const [season, setSeason] = useState<number | null>(null)
  const activeSeason = season ?? lastWatchedSeason ?? seasons[0] ?? null
  const episodes = useMemo(
    () =>
      (meta?.videos ?? [])
        .filter((v) => (v.season ?? 0) === activeSeason)
        .sort((a, b) => (a.episode ?? 0) - (b.episode ?? 0)),
    [meta, activeSeason],
  )

  if (isLoading) return <Shell onBack={pop}>Loading…</Shell>
  if (error || !meta)
    return <Shell onBack={pop}>Could not load this title: {String(error ?? 'not found')}</Shell>

  const toggleLibrary = () => {
    const now = Date.now()
    if (libraryEntry) {
      // Tombstone, not delete — removals must sync across devices and survive
      // stale re-adds (LWW by updatedAt).
      void upsertLibrary.mutateAsync([{ ...libraryEntry, removedAt: now, updatedAt: now }])
    } else {
      void upsertLibrary.mutateAsync([libraryItemFromMeta(meta)])
    }
  }

  const progressFor = (videoId: string): WatchState | null => {
    const state = (watchStates ?? []).find((s) => s.videoId === videoId)
    if (!state || state.durationSec === 0) return null
    return state
  }

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

  const inLibrary = !!libraryEntry

  return (
    <div className="screen-scroll">
      <div
        style={{
          position: 'relative',
          minHeight: 300,
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
            background:
              'linear-gradient(rgba(10,12,17,0.55), rgba(10,12,17,0.85) 75%, var(--background))',
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
          <div style={{ display: 'flex', gap: 10, marginTop: 14 }}>
            {(type !== 'series' || !meta.videos?.length) && (
              <button className="btn btn-primary btn-row" type="button" onClick={() => openStreams()}>
                <Icon name="play" size={15} />
                Sources
              </button>
            )}
            <button
              className="btn btn-glass btn-row"
              type="button"
              onClick={toggleLibrary}
              style={inLibrary ? { color: 'var(--accent)' } : undefined}
            >
              <Icon name="bookmark" size={16} />
              {inLibrary ? 'In Library' : 'Add to Library'}
            </button>
          </div>
        </div>
      </div>

      <div style={{ padding: '20px 32px 48px', maxWidth: 760 }}>
        {meta.description && (
          <p style={{ color: 'var(--text-dim)', lineHeight: 1.55, marginTop: 0 }}>
            {meta.description}
          </p>
        )}

        {type === 'series' && !!meta.videos?.length && (
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
              {episodes.map((v) => {
                const state = progressFor(v.id)
                const fraction = state
                  ? state.watched
                    ? 1
                    : state.positionSec / state.durationSec
                  : null
                return (
                  <button key={v.id} type="button" onClick={() => openStreams(v)} className="episode-row">
                    <div>
                      <span className="t-callout" style={{ marginRight: 10 }}>
                        E{v.episode ?? '?'}
                      </span>
                      <span>{v.title ?? v.name ?? v.id}</span>
                    </div>
                    {fraction !== null && (
                      <div className="episode-progress">
                        <div style={{ width: `${Math.round(fraction * 100)}%` }} />
                      </div>
                    )}
                  </button>
                )
              })}
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
