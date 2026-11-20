import {
  creditLine,
  departmentLabel,
  exactNamePerson,
  filmography,
  filmographyFilters,
  HaloApiError,
  type FilmographyFilter,
  type PersonCredit,
} from '@halo/core'
import { useQuery } from '@tanstack/react-query'
import { openUrl } from '@tauri-apps/plugin-opener'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { getClient } from '../api'
import { personSearchUrl } from '../browse/detail/detailFacts'
import { useCapabilities } from '../capabilities'
import { ArtImage } from '../components/ArtImage'
import { FluentIcon } from '../components/FluentIcon'
import { PosterCard } from '../components/PosterCard'
import { ProgressRing } from '../components/ProgressRing'
import { initials } from '../format'
import { useNav, type PersonParams } from '../nav'

/*
 * A person page: the header card with their portrait, who they are and their
 * biography, then their films and series with role chips. Opened by TMDB id
 * (the people row, a cast card that had one), or by name, when the page finds
 * them first; a name nobody has exactly opens IMDb's people search instead.
 */

const FILTER_LABELS: Record<FilmographyFilter, string> = { all: 'All', acting: 'Acting', directing: 'Directing', writing: 'Writing' }
const TMDB_IMAGE = 'https://image.tmdb.org/t/p/'

/** TMDB's thumbnail profile photo at the size the portrait is drawn. */
function portraitUrl(url: string | undefined | null): string | undefined {
  if (!url || !url.startsWith('https://')) return undefined
  return url.startsWith(`${TMDB_IMAGE}w185/`) ? `${TMDB_IMAGE}h632/${url.slice(`${TMDB_IMAGE}w185/`.length)}` : url
}

type ErrorKind = 'failed' | 'notFound' | 'unavailable'

const ERRORS: Record<ErrorKind, { title: string; text: string }> = {
  failed: { title: "Couldn't load this page", text: 'The server did not answer. Try again in a moment.' },
  notFound: { title: "This person isn't here", text: "The server doesn't know this person." },
  unavailable: { title: "This page can't be shown", text: 'This kind of page needs a Halo server that supports it.' },
}

function errorKind(error: unknown): ErrorKind {
  return error instanceof HaloApiError && error.status === 404 ? 'notFound' : 'failed'
}

export function Person({ tmdbId, personName, photo }: PersonParams) {
  const { pop } = useNav()
  const { capabilities, settled } = useCapabilities()
  const [filter, setFilter] = useState<FilmographyFilter>('all')
  const people = settled && capabilities.people

  // Only a name: the person with exactly that name, the most popular of several.
  const found = useQuery({
    queryKey: ['people', 'search', personName.trim()],
    enabled: people && tmdbId <= 0,
    staleTime: 10 * 60_000,
    retry: false,
    queryFn: ({ signal }) => getClient().searchPeople(personName, { signal }),
  })
  const byName = found.data ? exactNamePerson(found.data.people, personName) : null
  const resolvedId = tmdbId > 0 ? tmdbId : (byName?.tmdbId ?? 0)
  const person = useQuery({
    queryKey: ['person', resolvedId],
    enabled: people && resolvedId > 0,
    staleTime: 10 * 60_000,
    retry: false,
    queryFn: ({ signal }) => getClient().getPerson(resolvedId, { signal }),
  })

  // Nobody has exactly that name: IMDb's search finds them instead, and this page goes.
  const nobodyByName = tmdbId <= 0 && found.isSuccess && !byName
  useEffect(() => {
    if (!nobodyByName) return
    void openUrl(personSearchUrl(personName)).catch(() => undefined)
    pop()
  }, [nobodyByName, personName, pop])

  const detail = person.data
  const name = detail?.person.name ?? personName
  const portrait = portraitUrl(detail?.person.photo ?? photo)
  const kicker = departmentLabel(detail?.person.knownFor ?? null)
  const credits: PersonCredit[] = detail?.credits ?? []
  const filters = filmographyFilters(credits)
  const shownFilter = filters.includes(filter) ? filter : 'all'
  const shown = filmography(credits, shownFilter)

  let error: ErrorKind | null = null
  if (settled && !capabilities.people) error = 'unavailable'
  else if (found.isError) error = errorKind(found.error)
  else if (person.isError) error = errorKind(person.error)
  const loading = !error && !detail && !nobodyByName
  const retry = () => {
    if (found.isError) void found.refetch()
    else void person.refetch()
  }

  return (
    <div className="view">
      <div className="prs">
        <button type="button" className="prs-back" onClick={pop}>
          <FluentIcon glyph="back" size={14} />
          <span>Back</span>
        </button>
        <div className="prs-head">
          <div className="art prs-photo">
            <div className="prs-initials" aria-hidden>
              {initials(name)}
            </div>
            <ArtImage src={portrait} />
          </div>
          <div className="prs-text">
            {kicker && <div className="prs-kicker">{kicker}</div>}
            <h1 className="prs-name">{name}</h1>
            {detail?.person.biography && <Biography text={detail.person.biography} />}
          </div>
        </div>

        <div className="prs-body">
          {loading && (
            <div className="prs-loading">
              <ProgressRing size={28} />
            </div>
          )}
          {detail?.partial && (
            <div className="prs-partial">
              Still gathering this filmography. Some titles may be missing; open the page again in a moment.
            </div>
          )}
          {detail && credits.length > 0 && (
            <section className="srch-section">
              <div className="prs-films-head">
                <h2 className="prs-films-title">Films and series</h2>
                <span className="prs-count">{shown.length}</span>
                <span className="spacer" />
                {/* All and one role say the same thing; chips are worth showing from two roles on. */}
                {filters.length > 2 && (
                  <div className="srch-chips prs-chips" role="radiogroup" aria-label="Role">
                    {filters.map((option) => (
                      <button
                        key={option}
                        type="button"
                        role="radio"
                        aria-checked={shownFilter === option}
                        className={`srch-chip ${shownFilter === option ? 'srch-chip-on' : ''}`}
                        onClick={() => setFilter(option)}
                      >
                        {FILTER_LABELS[option]}
                      </button>
                    ))}
                  </div>
                )}
              </div>
              <div className="search-grid">
                {shown.map((credit) => (
                  <PosterCard
                    key={`${credit.type}:${credit.imdbId}`}
                    meta={{ id: credit.imdbId, type: credit.type, name: credit.name, ...(credit.poster ? { poster: credit.poster } : {}) }}
                    metaLine={creditLine(credit)}
                    softPlaceholder
                  />
                ))}
              </div>
            </section>
          )}
          {detail && credits.length === 0 && <div className="srch-empty-text prs-empty">No films or series to show for this person.</div>}
          {error && (
            <div className="srch-empty">
              <div className="srch-empty-title">{ERRORS[error].title}</div>
              <div className="srch-empty-text">{ERRORS[error].text}</div>
              {error === 'failed' && (
                <button type="button" className="btn srch-slot-btn" onClick={retry}>
                  Try again
                </button>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

/** Four lines until Read more opens it; Read more shows only while the text is cut short, or open. */
function Biography({ text }: { text: string }) {
  const [open, setOpen] = useState(false)
  const [clipped, setClipped] = useState(false)
  const ref = useRef<HTMLParagraphElement>(null)
  useLayoutEffect(() => {
    const element = ref.current
    if (!element || open) return
    const measure = () => setClipped(element.scrollHeight > element.clientHeight + 1)
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    return () => observer.disconnect()
  }, [text, open])
  return (
    <>
      <p ref={ref} className={`prs-bio ${open ? 'prs-bio-open' : ''}`}>
        {text}
      </p>
      {(clipped || open) && (
        <button type="button" className="prs-more" onClick={() => setOpen((value) => !value)}>
          {open ? 'Show less' : 'Read more'}
        </button>
      )}
    </>
  )
}
