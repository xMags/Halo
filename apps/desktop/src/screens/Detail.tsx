import type { MetaDetail, MetaVideo, WatchState } from '@halo/core'
import { useMemo, useState } from 'react'
import { Icon } from '../components/Icon'
import { Segmented } from '../components/Segmented'
import { episodeTag, formatAirDate, formatTimeLeft } from '../format'
import { useNav } from '../nav'
import {
  libraryItemFromMeta,
  useLibrary,
  useMeta,
  useStreams,
  useUpsertLibrary,
  useWatchStates,
} from '../queries'
import { usePublishScreenTitle } from '../screenTitle'

/**
 * Title page: art and the playback entry point up top, then the episode list
 * beside a column of facts and per-addon availability. Movies have no episode
 * list, so their two cards take the full width instead.
 */
export function Detail({ type, id }: { type: string; id: string }) {
  const { push } = useNav()
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

  const statesForItem = useMemo(
    () => (watchStates ?? []).filter((s) => s.itemId === itemId),
    [watchStates, itemId],
  )

  // The episode the user is actually mid-way through: what Resume targets and
  // which row the list highlights.
  const resumeState = useMemo(() => {
    const videoIds = new Set((meta?.videos ?? []).map((v) => v.id))
    return (
      statesForItem
        .filter((s) => !s.watched && s.durationSec > 0 && (videoIds.size === 0 || videoIds.has(s.videoId)))
        .sort((a, b) => b.updatedAt - a.updatedAt)[0] ?? null
    )
  }, [statesForItem, meta])
  const resumeVideo = (meta?.videos ?? []).find((v) => v.id === resumeState?.videoId)

  // Open on the season of the most recently watched episode, not season 1 —
  // mid-binge, "the season I'm in" is almost always where the next click goes.
  const lastWatchedSeason = useMemo(() => {
    const videosById = new Map((meta?.videos ?? []).map((video) => [video.id, video]))
    const latest = statesForItem
      .filter((s) => videosById.has(s.videoId))
      .sort((a, b) => b.updatedAt - a.updatedAt)[0]
    return latest ? (videosById.get(latest.videoId)!.season ?? null) : null
  }, [statesForItem, meta])

  const [season, setSeason] = useState<number | null>(null)
  const activeSeason = season ?? lastWatchedSeason ?? seasons[0] ?? null
  const episodes = useMemo(
    () =>
      (meta?.videos ?? [])
        .filter((v) => (v.season ?? 0) === activeSeason)
        .sort((a, b) => (a.episode ?? 0) - (b.episode ?? 0)),
    [meta, activeSeason],
  )

  usePublishScreenTitle(
    meta?.name ?? 'Loading…',
    [type, meta?.releaseInfo].filter(Boolean).join(' · ').toUpperCase(),
  )

  const openStreams = (video?: MetaVideo) => {
    if (!meta) return
    const tag = video ? episodeTag(video.season, video.episode) : null
    push({
      name: 'streams',
      type,
      videoId: video?.id ?? id,
      itemId,
      metaId: id,
      title: video ? (video.title ?? video.name ?? meta.name) : meta.name,
      showName: meta.name,
      ...(tag ? { episodeLabel: tag } : {}),
      ...(meta.poster ? { poster: meta.poster } : {}),
    })
  }

  if (isLoading) {
    return (
      <div className="view no-bar">
        <div className="state-note">
          <span className="spinner" /> Loading title…
        </div>
      </div>
    )
  }
  if (error || !meta) {
    return (
      <div className="view no-bar">
        <div className="state-note error-text">
          Could not load this title: {String(error ?? 'not found')}
        </div>
      </div>
    )
  }

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

  const resumeTag = resumeVideo ? episodeTag(resumeVideo.season, resumeVideo.episode) : null
  const resumeLabel = resumeState
    ? `Resume${resumeTag ? ` ${resumeTag}` : ''} · ${formatTimeLeft(resumeState.positionSec, resumeState.durationSec)}`
    : type === 'series'
      ? 'Play first episode'
      : 'Play'

  /** What the availability card and the header button resolve sources for. */
  const targetVideo = resumeVideo ?? (type === 'series' ? episodes[0] : undefined)

  return (
    <div className="view no-bar">
      <div className="detail-backdrop">
        {(meta.background ?? meta.poster) && (
          <div
            className="detail-backdrop-art"
            style={{ backgroundImage: `url(${meta.background ?? meta.poster})` }}
          />
        )}
        <div className="detail-scrim" />
        <div className="detail-head">
          <div className="art detail-poster">
            {meta.poster ? (
              <img src={meta.poster} alt="" draggable={false} />
            ) : (
              <div className="art-label">{meta.name}</div>
            )}
          </div>
          <div style={{ minWidth: 0, paddingBottom: 4 }}>
            <div className="kicker kicker-accent">{type.toUpperCase()}</div>
            <div className="detail-title ellipsis">{meta.name}</div>
            <div className="detail-facts">
              {meta.imdbRating && <span className="rating">★ {meta.imdbRating}</span>}
              {meta.releaseInfo && <span>{meta.releaseInfo}</span>}
              {seasons.length > 0 && (
                <>
                  <span className="dot-sep">/</span>
                  <span>
                    {seasons.length} season{seasons.length === 1 ? '' : 's'} ·{' '}
                    {(meta.videos ?? []).length} episodes
                  </span>
                </>
              )}
              {meta.runtime && (
                <>
                  <span className="dot-sep">/</span>
                  <span>{meta.runtime}</span>
                </>
              )}
            </div>
            <div className="detail-actions">
              <button type="button" className="btn-primary" onClick={() => openStreams(targetVideo)}>
                <Icon name="play" size={13} />
                {resumeLabel}
              </button>
              <button
                type="button"
                className={libraryEntry ? 'btn-accent' : 'btn-glass'}
                onClick={toggleLibrary}
              >
                <Icon name="bookmark" size={15} />
                {libraryEntry ? 'In library' : 'Add to library'}
              </button>
            </div>
          </div>
        </div>
      </div>

      {episodes.length > 0 ? (
        <div className="detail-split">
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              {seasons.length > 1 && (
                <Segmented
                  options={seasons.map((s) => ({
                    value: String(s),
                    label: s === 0 ? 'Specials' : `Season ${s}`,
                  }))}
                  value={String(activeSeason)}
                  onChange={(value) => setSeason(Number(value))}
                />
              )}
            </div>

            <div className="ep-list">
              {episodes.map((video) => (
                <EpisodeRow
                  key={video.id}
                  video={video}
                  state={statesForItem.find((s) => s.videoId === video.id) ?? null}
                  current={video.id === resumeState?.videoId}
                  onOpen={() => openStreams(video)}
                />
              ))}
            </div>
          </div>
          <aside className="detail-aside">
            <SynopsisCard meta={meta} />
            <AvailabilityCard
              type={type}
              videoId={targetVideo?.id ?? id}
              onBrowse={() => openStreams(targetVideo)}
            />
          </aside>
        </div>
      ) : (
        <div
          className="detail-split"
          style={{ maxWidth: 'var(--wide-max)', alignItems: 'stretch' }}
        >
          <div style={{ flex: 1, minWidth: 0 }}>
            <SynopsisCard meta={meta} />
          </div>
          <div className="detail-aside">
            <AvailabilityCard type={type} videoId={id} onBrowse={() => openStreams()} />
          </div>
        </div>
      )}
    </div>
  )
}

function EpisodeRow({
  video,
  state,
  current,
  onOpen,
}: {
  video: MetaVideo
  state: WatchState | null
  current: boolean
  onOpen: () => void
}) {
  const fraction =
    state && state.durationSec > 0
      ? state.watched
        ? 1
        : state.positionSec / state.durationSec
      : 0
  const tag = episodeTag(video.season, video.episode) ?? `E${video.episode ?? '?'}`

  return (
    <button type="button" className={`ep-row ${current ? 'ep-row-current' : ''}`} onClick={onOpen}>
      <div className="art art-wide ep-still">
        {video.thumbnail ? (
          <img src={video.thumbnail} alt="" loading="lazy" draggable={false} />
        ) : (
          <div className="art-label">STILL</div>
        )}
        {fraction > 0 && (
          <div className="art-progress">
            <div style={{ width: `${Math.round(fraction * 100)}%` }} />
          </div>
        )}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
          <div className={`ep-tag ${current ? 'ep-tag-current' : ''}`}>{tag}</div>
          <div className="ep-title ellipsis">{video.title ?? video.name ?? video.id}</div>
          {state?.watched && <div className="badge badge-success">WATCHED</div>}
        </div>
        {video.overview && <div className="ep-blurb ellipsis">{video.overview}</div>}
      </div>
      <div className="ep-right">
        {state && state.durationSec > 0 && !state.watched && (
          <div className="ep-runtime">{formatTimeLeft(state.positionSec, state.durationSec)}</div>
        )}
        {formatAirDate(video.released) && (
          <div className="ep-aired">{formatAirDate(video.released)}</div>
        )}
      </div>
    </button>
  )
}

/** Description plus whatever key/value facts the meta actually carries. */
function SynopsisCard({ meta }: { meta: MetaDetail }) {
  const facts: Array<{ key: string; value: string }> = []
  const add = (key: string, value: string | undefined | string[]) => {
    const text = Array.isArray(value) ? value.slice(0, 3).join(', ') : value
    if (text) facts.push({ key, value: text })
  }
  add('CREATED BY', meta.director ?? meta.writer)
  add('CAST', meta.cast)
  add('GENRES', meta.genres)
  add('COUNTRY', meta.country)
  add('RUNTIME', meta.runtime)
  add('AWARDS', meta.awards)

  return (
    <div className="card">
      <div className="kicker">SYNOPSIS</div>
      {meta.description ? (
        <div className="body-copy" style={{ marginTop: 10, color: '#a7aebd' }}>
          {meta.description}
        </div>
      ) : (
        <div className="body-copy" style={{ marginTop: 10 }}>
          No description from this addon.
        </div>
      )}
      {facts.length > 0 && (
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            gap: 9,
            marginTop: 15,
            paddingTop: 14,
            borderTop: '1px solid rgba(255,255,255,.07)',
          }}
        >
          {facts.slice(0, 5).map((fact) => (
            <div key={fact.key} className="fact-row">
              <div className="fact-key">{fact.key}</div>
              <div className="fact-value">{fact.value}</div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/**
 * How many playable sources each addon has for the video the header would
 * play. This resolves streams up front, which is the same request the Sources
 * screen makes — sharing the query key means clicking through is instant, and
 * the count is the only honest way to show availability before you commit.
 */
function AvailabilityCard({
  type,
  videoId,
  onBrowse,
}: {
  type: string
  videoId: string
  onBrowse: () => void
}) {
  const { data, isLoading } = useStreams(type, videoId)

  const rows = (data?.groups ?? []).map((group) => ({
    id: group.addonId,
    name: group.addonName,
    count: group.streams.length,
  }))
  const failed = data?.errors ?? []

  return (
    <div className="card">
      <div className="kicker">AVAILABILITY</div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10, marginTop: 12 }}>
        {isLoading && (
          <div className="body-copy" style={{ fontSize: 12 }}>
            <span className="spinner" /> Asking your addons…
          </div>
        )}
        {rows.map((row) => (
          <div key={row.id} className="avail-row">
            <span
              className="avail-dot"
              style={{ background: row.count > 0 ? 'var(--success)' : 'var(--text-dimmer)' }}
            />
            <span className="avail-name ellipsis">{row.name}</span>
            <span className="avail-count">{row.count}</span>
          </div>
        ))}
        {failed.map((failure) => (
          <div key={failure.id} className="avail-row">
            <span className="avail-dot" style={{ background: 'var(--danger)' }} />
            <span className="avail-name ellipsis">{failure.name ?? 'An addon'}</span>
            <span className="avail-count">—</span>
          </div>
        ))}
        {!isLoading && rows.length === 0 && failed.length === 0 && (
          <div className="body-copy" style={{ fontSize: 12 }}>
            No addon offers streams for this title.
          </div>
        )}
      </div>
      <button
        type="button"
        className="btn-glass"
        style={{ width: '100%', justifyContent: 'center', marginTop: 14, padding: 9, fontSize: 12.5 }}
        onClick={onBrowse}
      >
        Browse all sources
      </button>
    </div>
  )
}
