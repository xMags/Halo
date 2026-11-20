import { isSearchedCatalogType, parseSearchQuery, type SearchTitle } from '../search/board'
import { nameRelevance } from '../search/results'
import type { PersonCredit, PersonCreditRole, PersonDepartment, PersonSummary } from './readers'

/*
 * Which people Search shows beside a query's titles, how a person and their
 * credits are described, and how a person page lists their work. Shared by
 * every client; the Kotlin app carries the same rules, held to
 * `test/vectors/people.json`.
 */

export const PEOPLE_ROW_LIMIT = 3
/** The row admits a name holding every word typed: the whole name, its start, or a surname alone ("nolan"). */
const LOOSEST_PERSON_TIER = 3
const EXACT_NAME_TIER = 0

/**
 * At most three people whose name holds every word of the query, best named
 * first, then most popular. When a title is named exactly what was typed,
 * only people named exactly that stay: "dune" finds the film, not someone
 * called Dune Smith. Beside someone with a photo, a namesake without one is
 * nobody the typist meant; with no photo anywhere, everyone stays.
 */
export function choosePeopleRow(people: readonly PersonSummary[], query: string, bestTitleTier: number | null): PersonSummary[] {
  const trimmed = query.trim()
  const candidates = people
    .map((person) => ({ person, tier: nameRelevance(person.name, trimmed) }))
    .filter(({ tier }) => tier <= LOOSEST_PERSON_TIER)
    .filter(({ tier }) => !(bestTitleTier === EXACT_NAME_TIER && tier > EXACT_NAME_TIER))
    // Array.prototype.sort is stable.
    .sort((a, b) => a.tier - b.tier || b.person.popularity - a.person.popularity)
  const anyPhoto = candidates.some(({ person }) => !!person.photo)
  return candidates
    .filter(({ person }) => !anyPhoto || !!person.photo)
    .slice(0, PEOPLE_ROW_LIMIT)
    .map(({ person }) => person)
}

/** The best name relevance among `titles`, read as the ranking reads it (the full query or its name without a year); null for none. */
export function bestTitleTier(titles: ReadonlyArray<Pick<SearchTitle, 'name'>>, query: string, currentYear?: number): number | null {
  const parts = parseSearchQuery(query.trim(), currentYear)
  let best: number | null = null
  for (const title of titles) {
    const tier = Math.min(nameRelevance(title.name, parts.full), nameRelevance(title.name, parts.name))
    if (best === null || tier < best) best = tier
  }
  return best
}

/** The one person a name stands for: the exact name, the most popular of several. Null when nobody has exactly that name. */
export function exactNamePerson(people: readonly PersonSummary[], name: string): PersonSummary | null {
  const trimmed = name.trim()
  if (!trimmed) return null
  let chosen: PersonSummary | null = null
  for (const person of people) {
    if (nameRelevance(person.name, trimmed) !== EXACT_NAME_TIER) continue
    if (!chosen || person.popularity > chosen.popularity) chosen = person
  }
  return chosen
}

/** A person's films and series as Search titles, in the order given; one without an IMDb id, a name or a searched kind is left out. */
export function creditTitles(credits: ReadonlyArray<Pick<PersonCredit, 'type' | 'imdbId' | 'name' | 'poster' | 'year'>>): SearchTitle[] {
  return credits.flatMap((credit): SearchTitle[] => {
    if (!/^tt\d+$/.test(credit.imdbId) || !credit.name || !isSearchedCatalogType(credit.type)) return []
    return [
      {
        id: credit.imdbId,
        imdb_id: credit.imdbId,
        type: credit.type,
        name: credit.name,
        ...(credit.poster ? { poster: credit.poster } : {}),
        ...(credit.year ? { releaseInfo: credit.year } : {}),
      },
    ]
  })
}

/** What a person is: "Actor", "Director", "Writer"; empty when unknown. */
export function knownForLabel(department: PersonDepartment | null): string {
  if (department === 'Acting') return 'Actor'
  if (department === 'Directing') return 'Director'
  if (department === 'Writing') return 'Writer'
  return ''
}

/** The department itself, as a person page's kicker. */
export function departmentLabel(department: PersonDepartment | null): string {
  return department ?? ''
}

/** The line under a person's name: "Director · Dune, Arrival, Sicario", or whichever half is known. */
export function personRowLine(person: Pick<PersonSummary, 'knownFor' | 'knownForTitles'>): string {
  const label = knownForLabel(person.knownFor)
  const titles = person.knownForTitles.join(', ')
  return label && titles ? `${label} · ${titles}` : label || titles
}

/** A person page's role chips: every credit, or only those in one role. */
export type FilmographyFilter = 'all' | 'acting' | 'directing' | 'writing'

const FILTER_ROLE: Readonly<Record<Exclude<FilmographyFilter, 'all'>, PersonCreditRole>> = {
  acting: 'cast',
  directing: 'director',
  writing: 'writer',
}

export function hasRole(credit: Pick<PersonCredit, 'roles'>, filter: FilmographyFilter): boolean {
  return filter === 'all' || credit.roles.includes(FILTER_ROLE[filter])
}

/** All, then each role at least one credit has, in chip order (Acting, Directing, Writing). */
export function filmographyFilters(credits: ReadonlyArray<Pick<PersonCredit, 'roles'>>): FilmographyFilter[] {
  const filters: FilmographyFilter[] = ['all']
  for (const filter of ['acting', 'directing', 'writing'] as const) {
    if (credits.some((credit) => hasRole(credit, filter))) filters.push(filter)
  }
  return filters
}

function creditYear(credit: Pick<PersonCredit, 'year'>): number {
  return credit.year && /^\d{4}$/.test(credit.year) ? Number(credit.year) : -1
}

/** The credits a filter keeps, newest first and those without a year last; within a year, the more popular first. */
export function filmography<T extends Pick<PersonCredit, 'roles' | 'year' | 'popularity'>>(credits: readonly T[], filter: FilmographyFilter): T[] {
  return credits
    .filter((credit) => hasRole(credit, filter))
    .sort((a, b) => creditYear(b) - creditYear(a) || b.popularity - a.popularity)
}

const ROLE_LABELS: ReadonlyArray<[PersonCreditRole, string]> = [
  ['cast', 'Actor'],
  ['director', 'Director'],
  ['writer', 'Writer'],
]

/** "Actor · Director · Writer", in that order, for the roles held. */
export function creditRoleLabel(roles: readonly PersonCreditRole[]): string {
  return ROLE_LABELS.filter(([role]) => roles.includes(role))
    .map(([, label]) => label)
    .join(' · ')
}

/** The line under a credit's poster: "2021 · Director · Writer", or the roles alone without a year. */
export function creditLine(credit: Pick<PersonCredit, 'year' | 'roles'>): string {
  const roles = creditRoleLabel(credit.roles)
  return credit.year && roles ? `${credit.year} · ${roles}` : credit.year || roles
}
