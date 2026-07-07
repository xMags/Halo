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

/** Whether a subtitle's language code matches a preferred code ("pt-br" ≈ "pob" style variants aside, prefix-tolerant). */
export function languageMatches(subtitleLang: string, preferredCode: string): boolean {
  const sub = subtitleLang.trim().toLowerCase()
  const pref = preferredCode.trim().toLowerCase()
  if (sub === pref) return true
  // Some addons emit full labels ("Portuguese (BR)") or 639-1 codes.
  return languageLabel(sub).toLowerCase() === languageLabel(pref).toLowerCase()
}
