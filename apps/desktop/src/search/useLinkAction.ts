import {
  buildMagnetCard,
  classifySearchInput,
  HaloApiError,
  hasNoPoster,
  SEARCH_WEB_LINK_TITLE_MAX_LENGTH,
  type IdLookup,
  type MagnetCard,
  type MetaDetail,
  type SearchInput,
} from '@halo/core'
import { useQueries, useQuery } from '@tanstack/react-query'
import { getClient } from '../api'
import { useCapabilities } from '../capabilities'
import { ratingLabel } from '../format'
import { useNav } from '../nav'
import { formatStreamSize } from '../sources/sourcePresentation'
import { useMagnet } from './useMagnet'

/*
 * The action card a pasted link or id takes the place of the top match with:
 * what it is and, when there is one, the single thing it does (Enter, a click
 * on the card, or its button). A bare IMDb id is asked of the addons as a
 * film and as a series at once; TVDB numbers and IMDb people are looked up on
 * the server (or with the viewer's TMDB key); a web link plays as it is, away
 * from history; a magnet goes to the server's debrid account.
 */

export type ActionTone = 'accent' | 'warn' | 'bad' | 'mute'
export type ActionGlyph = 'id' | 'link' | 'magnet' | 'poster'

export interface ActionButton {
  label: string
  /** The play glyph before the label. */
  playGlyph: boolean
  /** Cancel: a destructive control is red. */
  destructive: boolean
  run: () => void
}

export interface ActionCardModel {
  tone: ActionTone
  glyph: ActionGlyph
  kicker: string
  title: string
  rating: string
  mono: string
  year: string
  tail: string
  secondary: string
  /** The poster (or a found person's photo) when the glyph is `poster`. */
  poster: string | null
  /** The IMDb id a poster falls back on (metahub draws those). */
  metaId: string
  /** The person glyph stands in for a photo that has not loaded. */
  person: boolean
  /** 0 to 1 while a magnet is being fetched. */
  progress: number | null
  /** The accent action with the Enter hint. */
  primary: ActionButton | null
  /** A magnet card's standard button, by click only. */
  plain: ActionButton | null
}

const COPY = {
  lookingUp: 'Looking up ',
  lookingUpSecondary: 'Asking your addons which title this is.',
  noAddonKnows: 'No addon knows this title',
  notFoundSuffix: ' was not found by any of your addons.',
  cannotLookUp: "This link can't be looked up",
  tvdbSlug: 'This TVDB link has no number in it. Paste the IMDb link instead.',
  needsServer: 'This kind of link needs a Halo server that supports it.',
  askingTitle: 'Asking the server which title this is.',
  askingPerson: 'Asking the server who this is.',
  unknownTitle: "The server doesn't know this title",
  unknownPerson: "The server doesn't know this person",
  serverNotFoundSuffix: ' was not found.',
  serverNoAnswer: 'The server did not answer. Try again in a moment.',
  openPerson: 'Open person page',
  webLinkSecondary: 'Plays straight from this address. Not added to your history.',
  cannotPlay: "This link can't be played",
  openTitle: 'Open title',
  playLink: 'Play this link',
} as const

const KICKERS: Record<SearchInput['kind'], string> = {
  text: '',
  imdbTitle: 'IMDB LINK',
  imdbPerson: 'IMDB LINK',
  tvdbNumeric: 'TVDB LINK',
  tvdbSlug: 'TVDB LINK',
  magnet: 'MAGNET LINK',
  webLink: 'VIDEO LINK',
  unsupported: 'LINK',
}

type LookupState = 'looking' | 'found' | 'notFound' | 'failed' | 'unsupported'

/** The title an IMDb id names: a series with episodes beats the film; otherwise the film wins. */
function chooseTitle(movie: MetaDetail | undefined, series: MetaDetail | undefined): MetaDetail | null {
  const counts = (meta: MetaDetail | undefined, type: string) => !!meta && meta.type === type
  if (counts(series, 'series') && (series!.videos?.length ?? 0) > 0) return series!
  if (counts(movie, 'movie')) return movie!
  if (counts(series, 'series')) return series!
  return null
}

function card(base: Partial<ActionCardModel> & Pick<ActionCardModel, 'tone' | 'glyph' | 'kicker' | 'title'>): ActionCardModel {
  return {
    rating: '',
    mono: '',
    year: '',
    tail: '',
    secondary: '',
    poster: null,
    metaId: '',
    person: false,
    progress: null,
    primary: null,
    plain: null,
    ...base,
  }
}

/** The card for `input`, or null for words. A link is never recorded as a search term. */
export function useLinkAction(input: SearchInput): ActionCardModel | null {
  const { push } = useNav()
  const { capabilities, settled } = useCapabilities()

  const imdbId = input.kind === 'imdbTitle' ? input.imdbId : ''
  const metas = useQueries({
    queries: (['movie', 'series'] as const).map((type) => ({
      queryKey: ['meta', type, imdbId] as const,
      enabled: imdbId !== '',
      staleTime: 10 * 60_000,
      retry: false,
      queryFn: async (): Promise<MetaDetail> => (await getClient().getMeta(type, imdbId)).meta,
    })),
  })

  const server =
    input.kind === 'tvdbNumeric'
      ? { source: 'tvdb' as const, id: input.tvdbId, expected: 'title' as const }
      : input.kind === 'imdbPerson'
        ? { source: 'imdb' as const, id: input.personId, expected: 'person' as const }
        : null
  const lookup = useQuery({
    queryKey: ['lookup', server?.source, server?.id],
    enabled: !!server && settled && capabilities.lookup,
    staleTime: 10 * 60_000,
    retry: false,
    queryFn: ({ signal }) => getClient().lookup(server!.source, server!.id, { signal }),
  })

  const magnetHash = input.kind === 'magnet' ? input.infoHash : null
  const magnet = useMagnet(magnetHash, input.kind === 'magnet' ? input.displayName : '')

  const openDetail = (type: string, id: string) => push({ name: 'detail', type, id })
  const openPerson = (tmdbId: number, name: string, photo: string | null) =>
    push({ name: 'person', tmdbId, personName: name, ...(photo ? { photo } : {}) })
  const playLink = (url: string, title: string, filename?: string, videoSize?: number) => {
    // Read again rather than trusted from the card: only a web link the
    // classifier accepts, exactly as given, reaches playback. No video id or
    // title page: nothing about it is kept in history, and no addon is asked
    // for its subtitles. It is opened bare; no account token goes with it.
    const again = classifySearchInput(url)
    if (again.kind !== 'webLink' || again.url !== url) return
    push({
      name: 'player',
      url,
      videoId: '',
      itemId: '',
      type: 'movie',
      title: title.slice(0, SEARCH_WEB_LINK_TITLE_MAX_LENGTH),
      recordsHistory: false,
      ...(filename ? { filename } : {}),
      ...(videoSize ? { videoSize } : {}),
    })
  }

  const kicker = KICKERS[input.kind]
  switch (input.kind) {
    case 'text':
      return null
    case 'imdbTitle': {
      const settledAll = metas.every((result) => !result.isPending)
      const found = chooseTitle(metas[0]?.data, metas[1]?.data)
      const state: LookupState = found ? 'found' : settledAll ? 'notFound' : 'looking'
      if (state === 'found' && found) {
        return card({
          tone: 'accent',
          glyph: 'poster',
          kicker,
          title: found.name,
          rating: ratingLabel(found.imdbRating) ?? '',
          mono: found.type === 'series' ? 'SERIES' : 'MOVIE',
          year: found.releaseInfo ?? '',
          poster: hasNoPoster(found) ? null : (found.poster ?? null),
          metaId: found.id,
          primary: { label: COPY.openTitle, playGlyph: false, destructive: false, run: () => openDetail(found.type, found.id) },
        })
      }
      return card({
        tone: state === 'notFound' ? 'mute' : 'accent',
        glyph: 'id',
        kicker,
        title: state === 'notFound' ? COPY.noAddonKnows : `${COPY.lookingUp}${input.imdbId}…`,
        secondary: state === 'notFound' ? `${input.imdbId}${COPY.notFoundSuffix}` : COPY.lookingUpSecondary,
      })
    }
    case 'tvdbNumeric':
    case 'imdbPerson': {
      const id = input.kind === 'tvdbNumeric' ? input.tvdbId : input.personId
      const expected = server!.expected
      let state: LookupState
      let answer: IdLookup | null = null
      if (!settled) state = 'looking'
      else if (!capabilities.lookup) state = 'unsupported'
      else if (lookup.isPending) state = 'looking'
      else if (lookup.isSuccess && lookup.data.kind === expected) {
        state = 'found'
        answer = lookup.data
      } else if (lookup.isSuccess || (lookup.error instanceof HaloApiError && lookup.error.status === 404)) state = 'notFound'
      else state = 'failed'
      if (answer?.kind === 'title') {
        const title = answer
        return card({
          tone: 'accent',
          glyph: 'poster',
          kicker,
          title: title.name,
          mono: title.type === 'series' ? 'SERIES' : 'MOVIE',
          year: title.year ?? '',
          poster: title.poster,
          metaId: title.id,
          primary: { label: COPY.openTitle, playGlyph: false, destructive: false, run: () => openDetail(title.type, title.id) },
        })
      }
      if (answer?.kind === 'person') {
        const who = answer
        return card({
          tone: 'accent',
          glyph: 'poster',
          kicker,
          title: who.name,
          poster: who.photo,
          person: true,
          primary: capabilities.people
            ? { label: COPY.openPerson, playGlyph: false, destructive: false, run: () => openPerson(who.tmdbId, who.name, who.photo) }
            : null,
        })
      }
      const isTvdb = input.kind === 'tvdbNumeric'
      return card({
        tone: state === 'looking' ? 'accent' : state === 'failed' ? 'warn' : 'mute',
        glyph: 'id',
        kicker,
        title:
          state === 'notFound'
            ? isTvdb
              ? COPY.unknownTitle
              : COPY.unknownPerson
            : state === 'unsupported' || state === 'failed'
              ? COPY.cannotLookUp
              : `${COPY.lookingUp}${id}…`,
        secondary:
          state === 'unsupported'
            ? COPY.needsServer
            : state === 'failed'
              ? COPY.serverNoAnswer
              : state === 'notFound'
                ? `${id}${COPY.serverNotFoundSuffix}`
                : isTvdb
                  ? COPY.askingTitle
                  : COPY.askingPerson,
      })
    }
    case 'tvdbSlug':
      return card({ tone: 'mute', glyph: 'id', kicker, title: COPY.cannotLookUp, secondary: COPY.tvdbSlug })
    case 'unsupported':
      return card({ tone: 'mute', glyph: 'link', kicker, title: COPY.cannotPlay, secondary: input.reason })
    case 'webLink':
      return card({
        tone: 'accent',
        glyph: 'link',
        kicker,
        title: input.displayName || input.host,
        mono: input.host,
        secondary: COPY.webLinkSecondary,
        primary: { label: COPY.playLink, playGlyph: true, destructive: false, run: () => playLink(input.url, input.displayName || input.host) },
      })
    case 'magnet': {
      const built: MagnetCard = buildMagnetCard({
        phase: magnet.phase,
        linkName: input.displayName,
        request: magnet.request,
        cachedOnly: magnet.cachedOnly,
        formatSize: formatStreamSize,
      })
      const identity = built.identity
      const file = built.unidentifiedFile
      const plainRun =
        built.action === 'cancel'
          ? magnet.cancel
          : built.action === 'retry'
            ? magnet.retry
            : built.action === 'playUnidentified' && file
              ? () => playLink(file.playUrl, file.name, file.name, file.sizeBytes)
              : null
      return card({
        tone: built.tone,
        glyph: built.showsPoster && identity ? 'poster' : 'magnet',
        kicker,
        title: built.title,
        mono: built.mono,
        year: built.plain,
        tail: built.tail,
        secondary: built.secondary,
        poster: identity?.poster ?? null,
        metaId: identity?.id ?? '',
        progress: built.progress,
        primary:
          built.action === 'openTitle' && identity
            ? { label: built.actionLabel, playGlyph: false, destructive: false, run: () => openDetail(identity.type, identity.id) }
            : null,
        plain: plainRun
          ? { label: built.actionLabel, playGlyph: built.action === 'playUnidentified', destructive: built.action === 'cancel', run: plainRun }
          : null,
      })
    }
  }
}
