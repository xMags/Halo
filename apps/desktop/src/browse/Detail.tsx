import type { MetaDetail, MetaVideo, WatchState } from '@halo/core'
import { useMemo, useState } from 'react'
import { ArtImage } from '../components/ArtImage'
import { ComboBox } from '../components/ComboBox'
import { FluentIcon } from '../components/FluentIcon'
import { DetailSkeleton } from '../components/Skeleton'
import { useDownloads, type DownloadView } from '../downloads/downloadsStore'
import { airYear, episodeTag, formatAirDate, formatTimeLeft, runtimeMinutes } from '../format'
import { useNav, type StreamsParams } from '../nav'
import {
  libraryItemFromMeta,
  useLibrary,
  useMeta,
  useStreams,
  useUpsertLibrary,
  useWatchStates,
} from '../queries'
import { parseStreamInfo, qualityRank, type Quality } from '../sources/streamInfo'

/**
 * Title page: the backdrop runs to the top with the poster and playback entry
 * point pulled up over it, then the episode list beside a column of facts and
 * per-addon availability. Movies have no episode list, so their two cards take
 * the full width instead.
 */
export function Detail({ type, id }: { type: string; id: string }) {
  const { pop, openSheet } = useNav()
  const { data: meta, isLoading, error } = useMeta(type, id)
  const { data: library } = useLibrary()
  const { data: watchStates } = useWatchStates()
  const { downloads } = useDownloads()
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
        .filter(
          (s) => !s.watched && s.durationSec > 0 && (videoIds.size === 0 || videoIds.has(s.videoId)),
        )
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

  const sheetParams = (video?: MetaVideo): StreamsParams | null => {
    if (!meta) return null
    const tag = video ? episodeTag(video.season, video.episode) : null
    const minutes = runtimeMinutes(meta.runtime)
    return {
      type,
      videoId: video?.id ?? id,
      itemId,
      metaId: id,
      title: video ? (video.title ?? video.name ?? meta.name) : meta.name,
      showName: meta.name,
      ...(tag ? { episodeLabel: tag } : {}),
      ...(meta.poster ? { poster: meta.poster } : {}),
      ...(minutes != null ? { runtimeMinutes: minutes } : {}),
    }
  }

  const openStreams = (video?: MetaVideo) => {
    const params = sheetParams(video)
    if (params) openSheet(params)
  }

  if (isLoading) {
    return <DetailSkeleton />
  }
  if (error || !meta) {
    return (
      <div className="view">
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
    ? `Resume${resumeTag ? ` ${resumeTag}` : ''}`
    : type === 'series'
      ? 'Play first episode'
      : 'Play'

  /** What the availability card and the header button resolve sources for. */
  const targetVideo = resumeVideo ?? (type === 'series' ? episodes[0] : undefined)
  const isSeries = episodes.length > 0

  const kicker = [type.toUpperCase(), (meta.genres ?? [])[0]?.toUpperCase()]
    .filter(Boolean)
    .join(' · ')
  const metaLine = [
    meta.releaseInfo,
    seasons.length > 0
      ? `${seasons.length} season${seasons.length === 1 ? '' : 's'}`
      : meta.runtime,
    (meta.genres ?? []).slice(0, 3).join(', ') || null,
    meta.imdbRating ? `★ ${meta.imdbRating}` : null,
  ]
    .filter(Boolean)
    .join(' · ')

  const seasonMeta = [
    `${episodes.length} EPISODE${episodes.length === 1 ? '' : 'S'}`,
    airYear(episodes[0]?.released),
  ]
    .filter(Boolean)
    .join(' · ')

  return (
    <div className="view detail-screen">
      {/* Full-page background art, pinned behind the scrolling page */}
      <div className="art detail-bg" aria-hidden>
        <ArtImage src={meta.background ?? meta.poster} label="BACKDROP" />
        <div className="detail-bg-scrim" />
      </div>

      <div className="detail-view">
        <div style={{ position: 'relative', zIndex: 1, paddingBottom: 48 }}>
          <div style={{ height: 268 }} />

          <div className={`detail-grid ${isSeries ? '' : 'detail-grid-movie'}`}>
            <div className="art detail-poster">
              <ArtImage src={meta.poster} label={meta.name} />
            </div>

            <div className="detail-head">
              <div className="kicker kicker-accent">{kicker}</div>
              <div className="detail-title ellipsis">{meta.name}</div>
              <div className="detail-meta ellipsis">{metaLine}</div>
              <div className="detail-actions">
                <button
                  type="button"
                  className="btn-accent h36"
                  onClick={() => openStreams(targetVideo)}
                >
                  <FluentIcon glyph="play" size={14} />
                  <span>
                    {resumeLabel}
                    {resumeState
                      ? ` · ${formatTimeLeft(resumeState.positionSec, resumeState.durationSec)}`
                      : ''}
                  </span>
                </button>
                <button
                  type="button"
                  className={libraryEntry ? 'btn-outline-accent h36' : 'btn h36'}
                  onClick={toggleLibrary}
                >
                  <FluentIcon glyph={libraryEntry ? 'starFilled' : 'star'} size={15} />
                  <span>{libraryEntry ? 'In library' : 'Add to library'}</span>
                </button>
                <button
                  type="button"
                  className="icon-btn icon-btn-36"
                  title="Choose a download source"
                  onClick={() => openStreams(targetVideo)}
                >
                  <FluentIcon glyph="downloads" size={16} />
                </button>
              </div>
            </div>

            {isSeries && <div />}

            {isSeries && (
              <div className="detail-episodes">
                <div className="season-row">
                  {seasons.length > 1 && (
                    <ComboBox
                      options={seasons.map((s) => ({
                        value: s,
                        label: s === 0 ? 'Specials' : `Season ${s}`,
                      }))}
                      minWidth={140}
                      width="auto"
                      ariaLabel="Season"
                      value={activeSeason ?? seasons[0] ?? 0}
                      onChange={(val) => setSeason(Number(val))}
                    />
                  )}
                  <div className="mono" style={{ paddingBottom: 6 }}>
                    {seasonMeta}
                  </div>
                  <div className="spacer" />
                  <button
                    type="button"
                    className="btn-link"
                    title="Choose a source for the episode you would resume"
                    onClick={() => openStreams(targetVideo)}
                  >
                    Choose a source
                  </button>
                </div>

                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {episodes.map((video) => (
                    <EpisodeRow
                      key={video.id}
                      video={video}
                      runtime={meta.runtime}
                      state={statesForItem.find((s) => s.videoId === video.id) ?? null}
                      current={video.id === resumeState?.videoId}
                      download={downloads.find((item) => item.media.video_id === video.id)}
                      onOpen={() => openStreams(video)}
                    />
                  ))}
                </div>
              </div>
            )}

            <aside className="detail-aside" style={isSeries ? undefined : { gap: 20 }}>
              <SynopsisCard meta={meta} />
              <AvailabilityCard
                type={type}
                videoId={targetVideo?.id ?? id}
                onBrowse={() => openStreams(targetVideo)}
              />
            </aside>
          </div>
        </div>

        <button type="button" className="detail-back" title="Back" onClick={pop}>
          <FluentIcon glyph="back" size={16} />
        </button>
      </div>
    </div>
  )
}

function EpisodeRow({
  video,
  runtime,
  state,
  current,
  download,
  onOpen,
}: {
  video: MetaVideo
  runtime: string | undefined
  state: WatchState | null
  current: boolean
  download: DownloadView | undefined
  onOpen: () => void
}) {
  const fraction =
    state && state.durationSec > 0
      ? state.watched
        ? 1
        : state.positionSec / state.durationSec
      : 0
  const tag = episodeTag(video.season, video.episode) ?? `E${video.episode ?? '?'}`
  const inProgress = fraction > 0 && fraction < 1

  return (
    <button type="button" className="ep-row" onClick={onOpen}>
      <div className="art ep-thumb">
        <ArtImage src={video.thumbnail} label="STILL" lazy />
        {fraction > 0 && (
          <div className="art-progress">
            <div style={{ width: `${Math.round(fraction * 100)}%` }} />
          </div>
        )}
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 3, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
          <span className={`ep-tag ${current || inProgress ? 'ep-tag-current' : ''}`}>{tag}</span>
          {state?.watched && (
            <span style={{ display: 'flex', color: 'var(--su)' }} title="Watched">
              <FluentIcon glyph="check" size={14} />
            </span>
          )}
          <span className="ep-title ellipsis">{video.title ?? video.name ?? video.id}</span>
          {download?.status === 'done' && <span className="saved-chip">SAVED</span>}
        </div>
        {video.overview && <div className="ep-blurb ellipsis">{video.overview}</div>}
      </div>
      <div className="ep-right">
        {state && state.durationSec > 0 && !state.watched ? (
          <span>{formatTimeLeft(state.positionSec, state.durationSec).toUpperCase()}</span>
        ) : (
          runtime && <span>{runtime.toUpperCase()}</span>
        )}
        {formatAirDate(video.released) && <span>{formatAirDate(video.released)}</span>}
      </div>
    </button>
  )
}

/** Description plus whatever key facts the meta actually carries. */
function SynopsisCard({ meta }: { meta: MetaDetail }) {
  const facts: string[] = []
  const add = (key: string, value: string | undefined | string[]) => {
    const text = Array.isArray(value) ? value.slice(0, 3).join(', ') : value
    if (text) facts.push(`${key} · ${text.toUpperCase()}`)
  }
  add('DIRECTOR', meta.director ?? meta.writer)
  add('CAST', meta.cast)
  add('GENRE', meta.genres)
  add('COUNTRY', meta.country)

  return (
    <div className="card" style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <div className="kicker">SYNOPSIS</div>
      <div style={{ fontSize: 15, lineHeight: 1.45, color: 'var(--t2)', textWrap: 'pretty' }}>
        {meta.description ?? 'No description from this addon.'}
      </div>
      {facts.slice(0, 4).map((fact) => (
        <div key={fact} className="fact-line ellipsis" title={fact}>
          {fact}
        </div>
      ))}
    </div>
  )
}

/**
 * What is actually available for the video the header would play, broken down
 * the way the picker will show it. This resolves streams up front, which is
 * the same request the sources sheet makes — sharing the query key means
 * opening the sheet afterwards is instant, and a count is the only honest way
 * to show availability before committing.
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

  const lines = useMemo(() => {
    const streams = (data?.groups ?? []).flatMap((group) => group.streams)
    const byQuality = new Map<string, number>()
    const languages = new Set<string>()
    for (const stream of streams) {
      const info = parseStreamInfo(stream)
      const key = info.quality ?? 'OTHER'
      byQuality.set(key, (byQuality.get(key) ?? 0) + 1)
      for (const sub of stream.subtitles ?? []) languages.add(sub.lang)
    }
    const rows = [...byQuality.entries()]
      .sort(
        (a, b) =>
          qualityRank(a[0] === 'OTHER' ? null : (a[0] as Quality)) -
          qualityRank(b[0] === 'OTHER' ? null : (b[0] as Quality)),
      )
      .map(([quality, count]) => `${quality.toUpperCase()} · ${count} SOURCES`)
    if (languages.size > 0) rows.push(`SUBTITLES · ${languages.size} LANGUAGES`)
    return rows
  }, [data])

  const failed = data?.errors ?? []

  return (
    <div className="card" style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <div className="kicker">AVAILABILITY</div>
      {isLoading && (
        <div className="mono">
          <span className="spinner" /> ASKING YOUR ADDONS…
        </div>
      )}
      {lines.map((line) => (
        <div key={line} className="fact-line">
          {line}
        </div>
      ))}
      {failed.map((failure) => (
        <div key={failure.id} className="avail-row" style={{ marginTop: 4 }}>
          <span className="dot" style={{ background: 'var(--cr)' }} />
          <span className="ellipsis">{(failure.name ?? 'AN ADDON').toUpperCase()} · NO ANSWER</span>
        </div>
      ))}
      {!isLoading && lines.length === 0 && failed.length === 0 && (
        <div className="fact-line">NO ADDON OFFERS STREAMS FOR THIS TITLE</div>
      )}
      <button type="button" className="btn btn-block" style={{ marginTop: 6 }} onClick={onBrowse}>
        Browse all sources
      </button>
    </div>
  )
}
