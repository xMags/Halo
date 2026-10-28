import { LANGUAGE_OPTIONS, type ManifestCatalog } from '@halo/core'

/** Language names Halo already knows, lower-cased, for telling a language list from a genre list. */
const KNOWN_LANGUAGES: ReadonlySet<string> = new Set(LANGUAGE_OPTIONS.map((option) => option.label.toLowerCase()))
/** How many known languages an option list must hold to count as a language choice. */
const MIN_KNOWN = 5

/**
 * The languages a catalog can be asked for, when it is a language catalog:
 * an optional `genre` extra whose options are language names (TMDB's
 * "Language" row). Null for every other catalog, such as Cinemeta's genre
 * lists or TMDB's years, whose options name no languages.
 */
export function languageChoices(catalog: Pick<ManifestCatalog, 'extra'>): string[] | null {
  const genre = (catalog.extra ?? []).find((extra) => extra.name === 'genre')
  if (!genre || genre.isRequired) return null
  const options = genre.options ?? []
  const known = options.filter((option) => KNOWN_LANGUAGES.has(option.trim().toLowerCase())).length
  return known >= MIN_KNOWN ? options : null
}

/**
 * The extras a Home catalog is asked with: the account's chosen language for
 * a language catalog that offers it, nothing otherwise, so the catalog falls
 * back to its own default.
 */
export function catalogExtra(
  catalog: Pick<ManifestCatalog, 'extra'>,
  preferred: string | undefined,
): Record<string, string> {
  const choices = languageChoices(catalog)
  return choices && preferred && choices.includes(preferred) ? { genre: preferred } : {}
}
