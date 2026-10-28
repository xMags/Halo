import type { MetaDetail } from '@halo/core'
import { openUrl } from '@tauri-apps/plugin-opener'
import { useEffect, useMemo, useState, type RefObject } from 'react'
import { FluentIcon } from '../../components/FluentIcon'
import { QualityBadge } from '../../components/QualityBadge'
import { formatTimeLeft } from '../../format'
import { useStreams } from '../../queries'
import { formatRuntime, summarizeAvailability, trailerUrl, type Availability } from './detailFacts'

/** What the page's main button plays, shared by the hero and the compact bar. */
export interface PlayTarget {
  label: 'Resume' | 'Play'
  /** `S1E3` for an episode. */
  tag: string | null
  progress: { positionSec: number; durationSec: number } | null
  /** The video the availability panel counts sources for. */
  videoId: string
  open: () => void
}

function playLabel(play: PlayTarget): string {
  return play.tag ? `${play.label} ${play.tag}` : play.label
}

function useAvailability(type: string, videoId: string) {
  const { data, isLoading } = useStreams(type, videoId)
  const summary = useMemo(
    () => summarizeAvailability((data?.groups ?? []).flatMap((group) => group.streams)),
    [data],
  )
  return { summary, isLoading, failed: data?.errors ?? [] }
}

export function DetailHero({
  meta,
  type,
  seasons,
  play,
  playButton,
  inLibrary,
  onToggleLibrary,
}: {
  meta: MetaDetail
  type: string
  /** Numbered seasons; a show's facts line counts them instead of a runtime. */
  seasons: number
  play: PlayTarget
  playButton: RefObject<HTMLButtonElement | null>
  inLibrary: boolean
  onToggleLibrary: () => void
}) {
  const availability = useAvailability(type, play.videoId)
  const best = availability.summary.groups[0]
  const trailer = trailerUrl(meta)
  const runtime = seasons > 0 ? `${seasons} season${seasons === 1 ? '' : 's'}` : formatRuntime(meta.runtime)
  const facts = [meta.releaseInfo, runtime, (meta.genres ?? []).slice(0, 3).join(', ') || null].filter(
    (fact): fact is string => Boolean(fact),
  )

  return (
    <section className="dh">
      <div className="dh-inner">
        <div className="dh-body">
          <HeroTitle name={meta.name} logo={meta.logo} />
          <div className="dh-facts">
            {facts.map((fact, index) => (
              <span key={fact}>
                {index > 0 && <span className="dh-sep">·</span>}
                {fact}
              </span>
            ))}
            {meta.ageRating && (
              <span>
                <span className="dh-sep">·</span>
                <span className="dh-cert">{meta.ageRating}</span>
              </span>
            )}
            {meta.imdbRating && (
              <span>
                <span className="dh-sep">·</span>
                <span className="dh-imdb">
                  <b>IMDb</b>
                  {meta.imdbRating}
                </span>
              </span>
            )}
          </div>
          {meta.description && <p className="dh-synopsis">{meta.description}</p>}

          <div className="dh-actions">
            <button type="button" className="dh-play" ref={playButton} onClick={play.open}>
              <FluentIcon glyph="play" size={16} />
              <span>{playLabel(play)}</span>
            </button>
            <button
              type="button"
              className={`glass-round dh-round ${inLibrary ? 'dh-round-on' : ''}`}
              aria-label={inLibrary ? 'In library' : 'Add to library'}
              aria-pressed={inLibrary}
              onClick={onToggleLibrary}
            >
              <FluentIcon glyph={inLibrary ? 'check' : 'star'} size={18} />
              <span className="dh-tip">{inLibrary ? 'In library' : 'Add to library'}</span>
            </button>
            <button
              type="button"
              className="glass-round dh-round"
              aria-label="Download"
              onClick={play.open}
            >
              <FluentIcon glyph="downloads" size={18} />
              <span className="dh-tip">Download</span>
            </button>
            {trailer && (
              <button
                type="button"
                className="glass-round dh-round"
                aria-label="Watch the trailer on YouTube"
                onClick={() => void openUrl(trailer).catch(() => undefined)}
              >
                <FluentIcon glyph="video" size={18} />
                <span className="dh-tip">Trailer on YouTube</span>
              </button>
            )}
          </div>

          <div className="dh-progress">
            {play.progress && (
              <>
                <span className="dh-bar" aria-hidden>
                  <span
                    style={{
                      width: `${Math.round(
                        Math.min(1, play.progress.positionSec / play.progress.durationSec) * 100,
                      )}%`,
                    }}
                  />
                </span>
                <span>{formatTimeLeft(play.progress.positionSec, play.progress.durationSec)}</span>
              </>
            )}
            {best && (
              <>
                {play.progress && <span className="dh-sep">·</span>}
                <QualityBadge tier={best.label} gold={best.gold} labelSize={11} />
                <button type="button" className="dh-sources" onClick={play.open}>
                  {availability.summary.total} source{availability.summary.total === 1 ? '' : 's'}
                </button>
              </>
            )}
          </div>
        </div>

        <WaysToWatch {...availability} onBrowse={play.open} />
      </div>
    </section>
  )
}

/**
 * The title's logo art when its addon has one, else the name in the display
 * face. A logo that fails to load falls back to the name, never a gap.
 */
function HeroTitle({ name, logo }: { name: string; logo: string | undefined }) {
  const [failed, setFailed] = useState(false)
  if (!logo || failed) return <h1 className="dh-title">{name}</h1>
  return (
    <img
      className="dh-logo"
      src={logo}
      alt={name}
      title={name}
      draggable={false}
      onError={() => setFailed(true)}
    />
  )
}

/**
 * What the addons offer for the video the main button plays, per quality,
 * with the share of sources each holds. The same request the source picker
 * makes, so opening it afterwards is instant.
 */
function WaysToWatch({
  summary,
  isLoading,
  failed,
  onBrowse,
}: {
  summary: Availability
  isLoading: boolean
  failed: Array<{ id: string; name?: string }>
  onBrowse: () => void
}) {
  const most = Math.max(1, ...summary.groups.map((group) => group.count))
  return (
    <aside className="watch glass">
      <h3 className="watch-title">Ways to watch</h3>
      {isLoading && (
        <div className="watch-note">
          <span className="spinner" /> Asking your addons…
        </div>
      )}
      {summary.groups.map((group) => (
        <button type="button" key={group.label} className="watch-row" onClick={onBrowse}>
          <span className="watch-badge">
            <QualityBadge tier={group.label} gold={group.gold} labelSize={11} />
          </span>
          <span className="watch-meter">
            <span
              className={group.gold ? 'watch-meter-gold' : ''}
              style={{ width: `${Math.max(4, Math.round((group.count / most) * 100))}%` }}
            />
          </span>
          <span className="watch-count">
            {group.count} source{group.count === 1 ? '' : 's'}
          </span>
          <span className="watch-go">
            <FluentIcon glyph="chevronRight" size={12} />
          </span>
        </button>
      ))}
      {!isLoading && summary.total === 0 && failed.length === 0 && (
        <div className="watch-note">No addon offers streams for this title.</div>
      )}
      {failed.map((failure) => (
        <div key={failure.id} className="watch-note watch-failed">
          {failure.name ?? 'An addon'} did not answer.
        </div>
      ))}
      {summary.subtitleLanguages > 0 && (
        <div className="watch-subs">
          <span>Subtitles</span>
          <span>
            {summary.subtitleLanguages} language{summary.subtitleLanguages === 1 ? '' : 's'}
          </span>
        </div>
      )}
      <button type="button" className="watch-browse" onClick={onBrowse}>
        Browse all sources
      </button>
    </aside>
  )
}

/**
 * A glass bar that takes over the top once the main button scrolls out of
 * sight, so playback is never a scroll away.
 */
export function CompactBar({
  meta,
  play,
  scroller,
  playButton,
}: {
  meta: MetaDetail
  play: PlayTarget
  scroller: RefObject<HTMLDivElement | null>
  playButton: RefObject<HTMLButtonElement | null>
}) {
  const [shown, setShown] = useState(false)
  const [logoFailed, setLogoFailed] = useState(false)

  useEffect(() => {
    const root = scroller.current
    const target = playButton.current
    if (!root || !target) return
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (!entry) return
        // Only above the fold counts: the button below the view (a short
        // window) is not "scrolled past".
        setShown(!entry.isIntersecting && entry.boundingClientRect.top < (entry.rootBounds?.top ?? 0))
      },
      { root, rootMargin: '-64px 0px 0px 0px' },
    )
    observer.observe(target)
    return () => observer.disconnect()
  }, [scroller, playButton])

  return (
    <header className={`compact-bar ${shown ? 'compact-bar-shown' : ''}`} aria-hidden={!shown}>
      {meta.logo && !logoFailed ? (
        <img className="compact-logo" src={meta.logo} alt={meta.name} onError={() => setLogoFailed(true)} />
      ) : (
        <span className="compact-title ellipsis">{meta.name}</span>
      )}
      <span className="spacer" />
      {play.progress && (
        <span className="compact-left">
          {formatTimeLeft(play.progress.positionSec, play.progress.durationSec)}
        </span>
      )}
      <button type="button" className="dh-play dh-play-small" tabIndex={shown ? 0 : -1} onClick={play.open}>
        <FluentIcon glyph="play" size={14} />
        <span>{playLabel(play)}</span>
      </button>
    </header>
  )
}
