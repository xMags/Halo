import type { MagnetFile, MagnetIdentity, MagnetRequest } from './readers'

/*
 * What Search's action card says about a pasted magnet, and which file of a
 * resolved magnet stands for a video of a title page. Shared by every client;
 * the Kotlin app carries the same rules, held to `test/vectors/magnets.json`.
 *
 * A magnet's files are never listed. A ready, identified magnet only opens
 * its title page, and is offered on that page as one source:
 * - a film (`movie`, video id equal to the matched `tt` id): its largest file;
 * - an episode (`series`, video id `tt…:season:episode` of the matched id):
 *   the largest file the server read that same season and episode from. A
 *   file without both numbers is never offered for an episode.
 */

/** Where the app stands with the pasted magnet. */
export type MagnetPhase =
  /** The server has no magnets for this account, or Halo has no account. */
  | 'unavailable'
  /** Asked, or waiting to learn whether the server has magnets. */
  | 'checking'
  /** The server answered; the card follows its request. */
  | 'answered'
  /** The server could not be reached or answered badly. */
  | 'failed'
  /** The viewer cancelled a download from the card. */
  | 'stopped'

export type MagnetCardTone = 'accent' | 'warn' | 'bad' | 'mute'

/** The card's one action: Open title is the accent button with the Enter hint; the rest are by click only. */
export type MagnetCardAction = 'none' | 'openTitle' | 'cancel' | 'playUnidentified' | 'retry'

export interface MagnetCardInput {
  phase: MagnetPhase
  /** The magnet's own `dn`, display only; may be empty. */
  linkName: string
  request?: MagnetRequest | null
  /** The server fetches only torrents its debrid account already holds (`capabilities.magnets.cachedOnly`). */
  cachedOnly: boolean
  /** How a size reads ("8.42 GB"); null for unknown. */
  formatSize: (bytes: number | null) => string
}

export interface MagnetCard {
  tone: MagnetCardTone
  /** The matched title's poster leads instead of the magnet glyph. */
  showsPoster: boolean
  title: string
  /** The meta row: a value in mono, a plain value, a second value in mono. */
  mono: string
  plain: string
  tail: string
  secondary: string
  /** 0 to 1 while a download is under way. */
  progress: number | null
  action: MagnetCardAction
  actionLabel: string
  /** The matched title, for the poster and Open title. */
  identity: MagnetIdentity | null
  /** The file Play as unidentified video plays. */
  unidentifiedFile: MagnetFile | null
}

export const MAGNET_COPY = {
  notAvailableTitle: "Magnet links aren't available",
  notAvailableText: "This server doesn't open magnet links for your account.",
  checkingTitle: 'Checking this torrent…',
  checkingText: 'Looking for a video file.',
  errorTitle: "Couldn't check this torrent",
  errorText: 'The server did not answer. Try again in a moment.',
  retryLabel: 'Try again',
  notCachedTitle: "This torrent isn't ready",
  notCachedText: "The server's debrid account doesn't have it yet. Try another magnet for the same title.",
  /** When the server may fetch uncached torrents, "not cached" means its debrid account could not start this one. */
  notCachedFetchText: "The server's debrid account couldn't start it. Try another magnet for the same title.",
  downloadingTitle: 'Getting this torrent ready…',
  downloadingText: 'You can leave this page; paste the magnet again to check on it.',
  cancelLabel: 'Cancel',
  noVideoTitle: 'No video in this torrent',
  noVideoText: 'Halo only plays video files. Nothing was added.',
  tooLargeTitle: 'This torrent is too large',
  tooLargeText: 'It is bigger than the 100 GB the server accepts.',
  unidentifiedTitle: 'Unidentified video',
  unidentifiedText: "Halo could not tell which film or series this is. It won't be added to your history.",
  playUnidentifiedLabel: 'Play as unidentified video',
  refusedTitle: "Can't open this magnet",
  refusedText: 'Halo could not tell which film or series this is.',
  seriesText: 'Open the title to pick an episode.',
  openTitleLabel: 'Open title',
  stoppedTitle: 'Stopped getting this torrent ready',
  stoppedText: 'Nothing more is fetched for it.',
  startAgainLabel: 'Start again',
  sourceName: 'Your magnet',
} as const

function note(tone: MagnetCardTone, title: string, secondary: string): MagnetCard {
  return {
    tone,
    showsPoster: false,
    title,
    mono: '',
    plain: '',
    tail: '',
    secondary,
    progress: null,
    action: 'none',
    actionLabel: '',
    identity: null,
    unidentifiedFile: null,
  }
}

function errorCard(): MagnetCard {
  return { ...note('bad', MAGNET_COPY.errorTitle, MAGNET_COPY.errorText), action: 'retry', actionLabel: MAGNET_COPY.retryLabel }
}

/** The biggest file, the first of equals; null for an empty list. */
export function largestMagnetFile(files: readonly MagnetFile[]): MagnetFile | null {
  let best: MagnetFile | null = null
  for (const file of files) if (!best || file.sizeBytes > best.sizeBytes) best = file
  return best
}

function readyCard(request: MagnetRequest, formatSize: MagnetCardInput['formatSize']): MagnetCard {
  const file = largestMagnetFile(request.files)
  // A ready answer always lists files (`readMagnet` refuses one without).
  if (!file) return errorCard()
  const size = (bytes: number): string => formatSize(bytes > 0 ? bytes : null)
  const identity = request.identified
  if (!identity) {
    return {
      ...note('warn', MAGNET_COPY.unidentifiedTitle, MAGNET_COPY.unidentifiedText),
      mono: file.name,
      tail: size(file.sizeBytes),
      action: 'playUnidentified',
      actionLabel: MAGNET_COPY.playUnidentifiedLabel,
      unidentifiedFile: file,
    }
  }
  const base: MagnetCard = {
    ...note('accent', identity.name, ''),
    showsPoster: true,
    identity,
    action: 'openTitle',
    actionLabel: MAGNET_COPY.openTitleLabel,
  }
  if (identity.type !== 'series') {
    return { ...base, mono: 'MOVIE', plain: identity.year ?? '', tail: size(file.sizeBytes), secondary: file.name }
  }
  // Only files with a season and an episode can be offered, so only those are counted.
  const seasons = new Set<number>()
  let episodes = 0
  let bytes = 0
  for (const candidate of request.files) {
    if (candidate.season === null || candidate.episode === null) continue
    seasons.add(candidate.season)
    episodes += 1
    bytes += candidate.sizeBytes
  }
  const plain = seasons.size === 1 ? `Season ${[...seasons][0]}` : seasons.size > 1 ? `${seasons.size} seasons` : ''
  return {
    ...base,
    mono: 'SERIES',
    plain,
    tail: `${episodes} ${episodes === 1 ? 'episode' : 'episodes'} · ${size(episodes > 0 ? bytes : request.sizeBytes)}`,
    secondary: MAGNET_COPY.seriesText,
  }
}

function answeredCard(input: MagnetCardInput, request: MagnetRequest): MagnetCard {
  switch (request.state) {
    case 'ready':
      return readyCard(request, input.formatSize)
    case 'not_cached':
      return note('warn', MAGNET_COPY.notCachedTitle, input.cachedOnly ? MAGNET_COPY.notCachedText : MAGNET_COPY.notCachedFetchText)
    case 'no_video':
      return note('bad', MAGNET_COPY.noVideoTitle, MAGNET_COPY.noVideoText)
    case 'too_large':
      return note('bad', MAGNET_COPY.tooLargeTitle, MAGNET_COPY.tooLargeText)
    case 'downloading': {
      const fraction = Number.isFinite(request.progress) ? Math.min(1, Math.max(0, request.progress)) : 0
      return {
        ...note('accent', MAGNET_COPY.downloadingTitle, MAGNET_COPY.downloadingText),
        mono: request.name || input.linkName,
        progress: fraction,
        tail: `${Math.floor(fraction * 100)}%`,
        action: 'cancel',
        actionLabel: MAGNET_COPY.cancelLabel,
      }
    }
    case 'failed':
      if (request.reason === 'unidentified') return note('bad', MAGNET_COPY.refusedTitle, MAGNET_COPY.refusedText)
      // Another failure (the debrid service refused, the torrent went away) is tried again by asking again.
      return errorCard()
  }
}

export function buildMagnetCard(input: MagnetCardInput): MagnetCard {
  switch (input.phase) {
    case 'unavailable':
      return note('mute', MAGNET_COPY.notAvailableTitle, MAGNET_COPY.notAvailableText)
    case 'checking':
      return { ...note('accent', MAGNET_COPY.checkingTitle, MAGNET_COPY.checkingText), mono: input.linkName }
    case 'failed':
      return errorCard()
    case 'stopped':
      return { ...note('mute', MAGNET_COPY.stoppedTitle, MAGNET_COPY.stoppedText), action: 'retry', actionLabel: MAGNET_COPY.startAgainLabel }
    case 'answered':
      return input.request ? answeredCard(input, input.request) : errorCard()
  }
}

/** A whole decimal number, digits only, that fits an int32. */
function wholeNumber(text: string): number | null {
  return /^\d{1,9}$/.test(text) ? Number(text) : null
}

/** `<metaId>:<season>:<episode>`, exactly. */
function episodeOf(videoId: string, metaId: string): { season: number; episode: number } | null {
  if (!metaId || videoId.length <= metaId.length + 1 || !videoId.startsWith(metaId) || videoId[metaId.length] !== ':') return null
  const rest = videoId.slice(metaId.length + 1)
  const colon = rest.indexOf(':')
  if (colon < 0) return null
  const season = wholeNumber(rest.slice(0, colon))
  const episode = wholeNumber(rest.slice(colon + 1))
  return season === null || episode === null ? null : { season, episode }
}

/** The file of a ready, identified magnet that stands for this title page's video, or null. */
export function magnetFileFor(magnet: MagnetRequest, type: string, videoId: string): MagnetFile | null {
  const identity = magnet.identified
  if (magnet.state !== 'ready' || !identity || magnet.files.length === 0 || type !== identity.type) return null
  if (type === 'movie') return videoId === identity.id ? largestMagnetFile(magnet.files) : null
  if (type !== 'series') return null
  const wanted = episodeOf(videoId, identity.id)
  if (!wanted) return null
  return largestMagnetFile(magnet.files.filter((file) => file.season === wanted.season && file.episode === wanted.episode))
}
