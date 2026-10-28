import type { MetaDetail, MetaVideo, WatchState } from '@halo/core'
import type { MouseEvent } from 'react'
import { ArtImage } from '../../components/ArtImage'
import { ComboBox } from '../../components/ComboBox'
import { FluentIcon } from '../../components/FluentIcon'
import type { DownloadView } from '../../downloads/downloadsStore'
import { airYear, episodeTag, formatAirDate, formatTimeLeft } from '../../format'

/** A show's season picker and the chosen season's episodes, as one glass panel. */
export function EpisodesPanel({
  meta,
  seasons,
  activeSeason,
  onSeason,
  episodes,
  states,
  resumeVideoId,
  downloads,
  onOpen,
  onMenu,
}: {
  meta: MetaDetail
  seasons: number[]
  activeSeason: number | null
  onSeason: (season: number) => void
  episodes: MetaVideo[]
  states: WatchState[]
  resumeVideoId: string | null
  downloads: DownloadView[]
  onOpen: (video: MetaVideo) => void
  onMenu: (event: MouseEvent<HTMLElement>, video: MetaVideo) => void
}) {
  const note = [`${episodes.length} episode${episodes.length === 1 ? '' : 's'}`, airYear(episodes[0]?.released)]
    .filter(Boolean)
    .join(' · ')

  return (
    <div className="glass detail-panel">
      <div className="srow-head">
        <h2 className="srow-title">
          Episodes
          <small>{note}</small>
        </h2>
        {seasons.length > 1 && (
          <ComboBox
            options={seasons.map((s) => ({ value: s, label: s === 0 ? 'Specials' : `Season ${s}` }))}
            minWidth={140}
            width="auto"
            ariaLabel="Season"
            value={activeSeason ?? seasons[0] ?? 0}
            onChange={(value) => onSeason(Number(value))}
          />
        )}
      </div>
      <div className="ep-list">
        {episodes.map((video) => (
          <EpisodeRow
            key={video.id}
            video={video}
            runtime={meta.runtime}
            state={states.find((s) => s.videoId === video.id) ?? null}
            current={video.id === resumeVideoId}
            download={downloads.find((item) => item.media.video_id === video.id)}
            onOpen={() => onOpen(video)}
            onMenu={(event) => onMenu(event, video)}
          />
        ))}
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
  onMenu,
}: {
  video: MetaVideo
  runtime: string | undefined
  state: WatchState | null
  current: boolean
  download: DownloadView | undefined
  onOpen: () => void
  onMenu: (event: MouseEvent<HTMLElement>) => void
}) {
  const fraction =
    state && state.durationSec > 0 ? (state.watched ? 1 : state.positionSec / state.durationSec) : 0
  const tag = episodeTag(video.season, video.episode) ?? `E${video.episode ?? '?'}`
  const inProgress = fraction > 0 && fraction < 1
  const aired = formatAirDate(video.released)

  return (
    <button
      type="button"
      className={`ep-row ${current ? 'ep-row-current' : ''}`}
      onClick={onOpen}
      onContextMenu={onMenu}
    >
      <div className="art ep-thumb">
        <ArtImage src={video.thumbnail} label="STILL" lazy />
        <span className="ep-play" aria-hidden>
          <FluentIcon glyph="play" size={14} />
        </span>
        {fraction > 0 && (
          <div className="art-progress">
            <div style={{ width: `${Math.round(Math.min(1, fraction) * 100)}%` }} />
          </div>
        )}
      </div>
      <div className="ep-text">
        <div className="ep-line">
          <span className={`ep-tag ${current || inProgress ? 'ep-tag-current' : ''}`}>{tag}</span>
          {state?.watched && (
            <span className="ep-watched" title="Watched">
              <FluentIcon glyph="check" size={13} />
            </span>
          )}
          <span className="ep-title ellipsis">{video.title ?? video.name ?? video.id}</span>
          {download?.status === 'done' && <span className="saved-chip">SAVED</span>}
        </div>
        {video.overview && <div className="ep-blurb">{video.overview}</div>}
      </div>
      <div className="ep-right">
        {state && state.durationSec > 0 && !state.watched ? (
          <span>{formatTimeLeft(state.positionSec, state.durationSec)}</span>
        ) : (
          runtime && <span>{runtime}</span>
        )}
        {aired && <span>{aired}</span>}
      </div>
    </button>
  )
}
