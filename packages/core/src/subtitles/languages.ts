/**
 * Human-readable labels for the ISO 639-2 codes subtitle addons emit.
 * Intl.DisplayNames only accepts 639-1 reliably and is missing from Hermes,
 * so a static map is the portable answer. Unknown codes fall back to the code.
 */
const LANGUAGE_LABELS: Record<string, string> = {
  ara: 'Arabic',
  bul: 'Bulgarian',
  chi: 'Chinese',
  zho: 'Chinese',
  cze: 'Czech',
  ces: 'Czech',
  dan: 'Danish',
  dut: 'Dutch',
  nld: 'Dutch',
  eng: 'English',
  est: 'Estonian',
  fin: 'Finnish',
  fre: 'French',
  fra: 'French',
  ger: 'German',
  deu: 'German',
  gre: 'Greek',
  ell: 'Greek',
  heb: 'Hebrew',
  hin: 'Hindi',
  hrv: 'Croatian',
  hun: 'Hungarian',
  ind: 'Indonesian',
  ita: 'Italian',
  jpn: 'Japanese',
  kor: 'Korean',
  lav: 'Latvian',
  lit: 'Lithuanian',
  may: 'Malay',
  msa: 'Malay',
  nor: 'Norwegian',
  per: 'Persian',
  fas: 'Persian',
  pol: 'Polish',
  por: 'Portuguese',
  pob: 'Portuguese (BR)',
  rum: 'Romanian',
  ron: 'Romanian',
  rus: 'Russian',
  slo: 'Slovak',
  slk: 'Slovak',
  slv: 'Slovenian',
  spa: 'Spanish',
  srp: 'Serbian',
  swe: 'Swedish',
  tha: 'Thai',
  tur: 'Turkish',
  ukr: 'Ukrainian',
  vie: 'Vietnamese',
}

export function languageLabel(code: string): string {
  const normalized = code.trim().toLowerCase()
  return LANGUAGE_LABELS[normalized] ?? code
}

/**
 * One entry per language for preference pickers, keyed by the 639-2/B code
 * subtitle addons actually emit (e.g. "ger" not "deu").
 */
export const LANGUAGE_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  'eng', 'spa', 'por', 'pob', 'fre', 'ger', 'ita', 'dut', 'pol', 'rus',
  'ukr', 'swe', 'nor', 'dan', 'fin', 'cze', 'slo', 'slv', 'hrv', 'srp',
  'hun', 'rum', 'bul', 'gre', 'tur', 'ara', 'heb', 'per', 'hin', 'tha',
  'vie', 'ind', 'may', 'chi', 'jpn', 'kor', 'est', 'lav', 'lit',
].map((code) => ({ code, label: LANGUAGE_LABELS[code]! }))

/**
 * Two-letter (ISO 639-1) codes for the languages above, by their three-letter
 * code. Files and addons tag tracks either way: Matroska's newer language tag
 * is `en` or `en-US` where the older one is `eng`.
 */
const TWO_LETTER_CODES: Record<string, string> = {
  ar: 'ara', bg: 'bul', zh: 'chi', cs: 'cze', da: 'dan', nl: 'dut', en: 'eng', et: 'est', fi: 'fin',
  fr: 'fre', de: 'ger', el: 'gre', he: 'heb', hi: 'hin', hr: 'hrv', hu: 'hun', id: 'ind', it: 'ita',
  ja: 'jpn', ko: 'kor', lv: 'lav', lt: 'lit', ms: 'may', no: 'nor', nb: 'nor', fa: 'per', pl: 'pol',
  pt: 'por', ro: 'rum', ru: 'rus', sk: 'slo', sl: 'slv', es: 'spa', sr: 'srp', sv: 'swe', th: 'tha',
  tr: 'tur', uk: 'ukr', vi: 'vie',
}

/**
 * The language a tag names, as this table's label, or the tag itself when it
 * is not a language known here. Takes three-letter codes, two-letter codes,
 * tags with a region or script (`en-US`, `zh_Hans`) and full labels.
 */
function languageIdentity(tag: string): string {
  const normalized = tag.trim().toLowerCase().replace(/_/g, '-')
  if (normalized === 'pt-br') return LANGUAGE_LABELS.pob!.toLowerCase()
  const primary = normalized.split('-')[0]!
  const code = TWO_LETTER_CODES[primary] ?? primary
  return (LANGUAGE_LABELS[normalized] ?? LANGUAGE_LABELS[code] ?? normalized).toLowerCase()
}

/** Whether a track's or subtitle's language tag names the preferred language, however either side spells it. */
export function languageMatches(subtitleLang: string, preferredCode: string): boolean {
  return languageIdentity(subtitleLang) === languageIdentity(preferredCode)
}

/**
 * Every code that names the same language as `preferredCode`, the given one
 * first: what a player's own language preference (mpv's `alang`) is given so
 * it picks the track however the file tagged it.
 */
export function languageCodeVariants(preferredCode: string): string[] {
  const given = preferredCode.trim().toLowerCase()
  if (!given) return []
  const identity = languageIdentity(given)
  const threeLetter = Object.keys(LANGUAGE_LABELS).filter((code) => LANGUAGE_LABELS[code]!.toLowerCase() === identity)
  const twoLetter = Object.keys(TWO_LETTER_CODES).filter((code) => threeLetter.includes(TWO_LETTER_CODES[code]!))
  const regional = identity === LANGUAGE_LABELS.pob!.toLowerCase() ? ['pt-br'] : []
  return [...new Set([given, ...threeLetter, ...regional, ...twoLetter])]
}
