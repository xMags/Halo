import type { MetaVideo } from '@halo/core'
import { useMemo, useRef, useState } from 'react'
import { FluentIcon } from '../../components/FluentIcon'
import { DetailSkeleton } from '../../components/Skeleton'
import { useDownloads } from '../../downloads/downloadsStore'
import { episodeTag, runtimeMinutes } from '../../format'
import { useNav, type StreamsParams } from '../../nav'
import { libraryItemFromMeta, useLibrary, useMeta, useUpsertLibrary, useWatchStates } from '../../queries'
import { useTitleMenu } from '../titleMenu'
import { hasWatchProgress } from '../watchMarks'
import { AboutPanel, CastPanel, MoreLikeThis } from './DetailPanels'
import { CompactBar, DetailHero, type PlayTarget } from './DetailHero'
import { EpisodesPanel } from './EpisodesPanel'

/**
 * Title page: the title's art fills the top with the logo, facts and playback
 * over it, and runs on behind the whole page blurred, so every panel below is
 * frosted glass over the title's own colours. The page is dark in both app
 * themes (`theme-dark`), because it always sits on that art.
 */
export function Detail({ type, id }: { type: string; id: string }) {
  const { pop, openSheet } = useNav()
  const { data: meta, isLoading, error } = useMeta(type, id)
  const { data: library } = useLibrary()
  const { data: watchStates } = useWatchStates()
  const { downloads } = useDownloads()
  const upsertLibrary = useUpsertLibrary()
  const titleMenu = useTitleMenu()
  const scroller = useRef<HTMLDivElement>(null)
  const playButton = useRef<HTMLButtonElement>(null)

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
  // An episode marked unwatched records no viewing, so it does not count.
  const lastWatchedSeason = useMemo(() => {
    const videosById = new Map((meta?.videos ?? []).map((video) => [video.id, video]))
    const latest = statesForItem
      .filter((s) => videosById.has(s.videoId) && hasWatchProgress(s))
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

  const sheetParams = (video?: MetaVideo): StreamsParams => {
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
  const openStreams = (video?: MetaVideo) => openSheet(sheetParams(video))

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

  const isSeries = (meta.videos ?? []).length > 0
  /** What the header plays and the availability panel counts sources for. */
  const targetVideo = resumeVideo ?? (isSeries ? episodes[0] : undefined)
  const resumeTag = resumeVideo ? episodeTag(resumeVideo.season, resumeVideo.episode) : null
  const targetTag = targetVideo ? episodeTag(targetVideo.season, targetVideo.episode) : null
  const play: PlayTarget = {
    label: resumeState ? 'Resume' : 'Play',
    tag: resumeState ? resumeTag : targetTag,
    progress: resumeState
      ? { positionSec: resumeState.positionSec, durationSec: resumeState.durationSec }
      : null,
    videoId: targetVideo?.id ?? id,
    open: () => openStreams(targetVideo),
  }

  return (
    <div className="view detail-screen theme-dark">
      <div className="detail-bg" aria-hidden>
        {(meta.background ?? meta.poster) && (
          <>
            <div className="detail-bg-blur" style={{ backgroundImage: `url("${meta.background ?? meta.poster}")` }} />
            <div className="detail-bg-art" style={{ backgroundImage: `url("${meta.background ?? meta.poster}")` }} />
          </>
        )}
        <div className="detail-bg-scrim" />
      </div>

      <div className="detail-view" ref={scroller}>
        <DetailHero
          meta={meta}
          type={type}
          seasons={seasons.filter((s) => s !== 0).length}
          play={play}
          playButton={playButton}
          inLibrary={Boolean(libraryEntry)}
          onToggleLibrary={toggleLibrary}
        />

        <div className="detail-panels">
          {isSeries && (
            <EpisodesPanel
              meta={meta}
              seasons={seasons}
              activeSeason={activeSeason}
              onSeason={setSeason}
              episodes={episodes}
              states={statesForItem}
              resumeVideoId={resumeState?.videoId ?? null}
              downloads={downloads}
              onOpen={openStreams}
              onMenu={(event, video) =>
                titleMenu.forEpisode(event, { meta, itemId, video, play: () => openStreams(video) })
              }
            />
          )}
          <div className="detail-split">
            <CastPanel meta={meta} />
            <AboutPanel meta={meta} />
          </div>
          <MoreLikeThis type={type} id={id} genres={meta.genres ?? []} />
        </div>
      </div>

      <button type="button" className="detail-back glass-round" title="Back" onClick={pop}>
        <FluentIcon glyph="back" size={16} />
      </button>
      <CompactBar meta={meta} play={play} scroller={scroller} playButton={playButton} />
    </div>
  )
}
