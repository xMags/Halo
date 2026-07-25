import type { Manifest, MetaDetail, MetaPreview, MetaVideo, Stream } from '@halo/core'

/**
 * Canned Stremio addons for the development server.
 *
 * These stand in for real addons behind the API's injectable `safeFetch`, which
 * is the only way to serve them at all: the SSRF guard rejects every private
 * and loopback address with no override, so an addon on this machine is
 * unreachable by design. Replacing the fetch instead of the guard keeps the
 * real resolution, validation and storage paths intact — the server under test
 * is the server, not an imitation of it.
 *
 * Metadata uses real ids and public artwork so screens can be judged against
 * plausible content. Artwork is therefore the one part that needs a network;
 * every addon response itself is local.
 */

const POSTER = (id: string) => `https://images.metahub.space/poster/medium/${id}/img`
const BACKGROUND = (id: string) => `https://images.metahub.space/background/medium/${id}/img`
const LOGO = (id: string) => `https://images.metahub.space/logo/medium/${id}/img`

interface Title {
  id: string
  type: 'movie' | 'series'
  name: string
  releaseInfo: string
  imdbRating: string
  genres: string[]
  runtime?: string
  description: string
  videos?: MetaVideo[]
}

const MOVIES: Title[] = [
  {
    id: 'tt0133093',
    type: 'movie',
    name: 'The Matrix',
    releaseInfo: '1999',
    imdbRating: '8.7',
    genres: ['Action', 'Sci-Fi'],
    runtime: '136 min',
    description:
      'A computer programmer discovers that the world he lives in is an elaborate simulation, and joins a rebellion against the machines running it.',
  },
  {
    id: 'tt1375666',
    type: 'movie',
    name: 'Inception',
    releaseInfo: '2010',
    imdbRating: '8.8',
    genres: ['Action', 'Adventure', 'Sci-Fi'],
    runtime: '148 min',
    description:
      'A thief who steals corporate secrets through dream-sharing technology is given the inverse task of planting an idea into an heir’s mind.',
  },
  {
    id: 'tt0111161',
    type: 'movie',
    name: 'The Shawshank Redemption',
    releaseInfo: '1994',
    imdbRating: '9.3',
    genres: ['Drama'],
    runtime: '142 min',
    description:
      'Two imprisoned men bond over a number of years, finding solace and eventual redemption through acts of common decency.',
  },
  {
    id: 'tt0468569',
    type: 'movie',
    name: 'The Dark Knight',
    releaseInfo: '2008',
    imdbRating: '9.0',
    genres: ['Action', 'Crime', 'Drama'],
    runtime: '152 min',
    description:
      'Batman faces the Joker, a criminal mastermind intent on proving that even the best of Gotham can be broken.',
  },
  {
    id: 'tt0109830',
    type: 'movie',
    name: 'Forrest Gump',
    releaseInfo: '1994',
    imdbRating: '8.8',
    genres: ['Drama', 'Romance'],
    runtime: '142 min',
    description:
      'The presidencies of Kennedy and Johnson, Vietnam, and Watergate unfold from the perspective of an Alabama man with an IQ of 75.',
  },
  {
    id: 'tt0068646',
    type: 'movie',
    name: 'The Godfather',
    releaseInfo: '1972',
    imdbRating: '9.2',
    genres: ['Crime', 'Drama'],
    runtime: '175 min',
    description:
      'The aging patriarch of an organized crime dynasty transfers control of his clandestine empire to his reluctant son.',
  },
]

/** Episodes for one season, titled well enough to fill an episode list. */
function season(seriesId: string, number: number, count: number, offset = 0): MetaVideo[] {
  return Array.from({ length: count }, (_, index) => {
    const episode = index + 1
    return {
      id: `${seriesId}:${number}:${episode}`,
      title: number === 0 ? `Special ${episode}` : `Episode ${episode}`,
      season: number,
      episode,
      released: new Date(Date.UTC(2011 + number, 3, offset + episode)).toISOString(),
      thumbnail: `https://episodes.metahub.space/${seriesId}/${number}/${episode}/w780.jpg`,
      overview:
        number === 0
          ? 'A behind-the-scenes look that sorts after the numbered seasons rather than before them.'
          : `The story continues in season ${number}, episode ${episode}.`,
    }
  })
}

const SERIES: Title[] = [
  {
    id: 'tt0903747',
    type: 'series',
    name: 'Breaking Bad',
    releaseInfo: '2008–2013',
    imdbRating: '9.5',
    genres: ['Crime', 'Drama', 'Thriller'],
    description:
      'A chemistry teacher diagnosed with terminal cancer turns to manufacturing to secure his family’s future.',
    // Season 0 exists to exercise specials sorting last; seasons differ in
    // length so an episode list is not uniform.
    videos: [...season('tt0903747', 1, 7), ...season('tt0903747', 2, 13), ...season('tt0903747', 0, 2)],
  },
  {
    id: 'tt0944947',
    type: 'series',
    name: 'Game of Thrones',
    releaseInfo: '2011–2019',
    imdbRating: '9.2',
    genres: ['Action', 'Adventure', 'Drama'],
    description: 'Noble families vie for control of the Iron Throne while an ancient enemy returns.',
    videos: [...season('tt0944947', 1, 10), ...season('tt0944947', 2, 10)],
  },
  {
    id: 'tt0417299',
    type: 'series',
    name: 'Avatar: The Last Airbender',
    releaseInfo: '2005–2008',
    imdbRating: '9.3',
    genres: ['Animation', 'Adventure', 'Family'],
    description: 'A young boy discovers he is the Avatar, the only person able to master all four elements.',
    videos: season('tt0417299', 1, 20),
  },
]

const ALL: Title[] = [...MOVIES, ...SERIES]

function preview(title: Title): MetaPreview {
  return {
    id: title.id,
    type: title.type,
    name: title.name,
    poster: POSTER(title.id),
    posterShape: 'poster',
    background: BACKGROUND(title.id),
    logo: LOGO(title.id),
    releaseInfo: title.releaseInfo,
    imdbRating: title.imdbRating,
    genres: title.genres,
    description: title.description,
  }
}

function detail(title: Title): MetaDetail {
  return {
    ...preview(title),
    runtime: title.runtime,
    videos: title.videos ?? [],
    cast: ['A Performer', 'Another Performer', 'A Third Performer'],
    director: ['A Director'],
    writer: ['A Writer'],
    country: 'United States',
    language: 'English',
  }
}

/**
 * Streams for any video, derived from its id so every title is playable
 * without enumerating one by one.
 *
 * Shaped like the real thing on purpose: names carry newlines and emoji,
 * sizes are large enough to overflow a 32-bit integer, and a torrent-only
 * result is included so the server's filtering is visible rather than assumed.
 *
 * The playable URLs are unreachable unless the server was started with a media
 * file to serve, in which case both point at it. Only the URL changes: names,
 * sizes and hints stay identical in both modes, so nothing a picker displays
 * depends on whether playback is wired up.
 */
function streamsFor(videoId: string, mediaUrl: string | null): Stream[] {
  const filename = `${videoId.replace(/:/g, '.')}.2160p.WEB-DL.DDP5.1.HDR.HEVC`
  return [
    {
      url: mediaUrl ?? `https://cdn.fixture.test/${encodeURIComponent(videoId)}/2160p.mkv`,
      name: 'Fixture\n4K HDR',
      title: `📺 2160p • HEVC • DDP5.1\n💾 34.2 GB • ⚡ Cached`,
      behaviorHints: {
        bingeGroup: `fixture|${videoId.split(':')[0]}|2160p`,
        filename: `${filename}.mkv`,
        // Past 2^31 bytes: the field has to survive as a 64-bit value.
        videoSize: 34_179_869_184,
        videoHash: '8e245d9679d31e12',
      },
    },
    {
      // A query string on purpose: real debrid URLs carry them, and a client
      // that mishandles one when passing the URL around fails here rather than
      // against someone's paid account.
      url: mediaUrl ? `${mediaUrl}?variant=1080p` : `https://cdn.fixture.test/${encodeURIComponent(videoId)}/1080p.mp4`,
      name: 'Fixture\n1080p',
      title: `📺 1080p • H.264 • AAC\n💾 4.1 GB`,
      behaviorHints: {
        bingeGroup: `fixture|${videoId.split(':')[0]}|1080p`,
        filename: `${filename.replace('2160p', '1080p')}.mp4`,
        videoSize: 4_402_341_478,
      },
    },
    {
      // Torrents are never playable in Halo; the server drops this before it
      // reaches a client, which is the point of including it.
      name: 'Fixture\nTorrent',
      title: 'Should never appear in the picker',
      infoHash: 'deadbeefdeadbeefdeadbeefdeadbeefdeadbeef',
      fileIdx: 0,
    },
  ]
}

export interface FixtureAddon {
  /** Transport URL as installed; the manifest hangs off `${base}/manifest.json`. */
  base: string
  /** Opaque id seeded into the database, so it is stable across restarts. */
  entryId: string
  manifest: Manifest
  catalog?: (type: string, id: string, extra: URLSearchParams) => MetaPreview[] | null
  meta?: (type: string, id: string) => MetaDetail | null
  /** [mediaUrl] is the dev server's own media route, or null when it serves none. */
  stream?: (type: string, videoId: string, mediaUrl: string | null) => Stream[] | null
}

const catalogManifest: Manifest = {
  id: 'test.halo.catalogs',
  version: '1.0.0',
  name: 'Fixture Catalogs',
  description: 'Canned catalogs and metadata for local development.',
  resources: ['catalog', 'meta'],
  types: ['movie', 'series'],
  idPrefixes: ['tt'],
  catalogs: [
    // Parameterless, and searchable: these become both Home rows and search rows.
    { type: 'movie', id: 'top', name: 'Popular', extraSupported: ['search', 'skip'] },
    { type: 'series', id: 'top', name: 'Popular', extraSupported: ['search', 'skip'] },
    { type: 'movie', id: 'featured', name: 'Featured' },
    // Gated on a genre: must never become a Home row, since Home asks nothing.
    { type: 'movie', id: 'genre', name: 'By Genre', extra: [{ name: 'genre', isRequired: true }] },
  ],
}

const streamManifest: Manifest = {
  id: 'test.halo.streams',
  version: '2.1.0',
  name: 'Fixture Streams',
  description: 'Canned playable sources for local development.',
  resources: ['stream'],
  types: ['movie', 'series'],
  idPrefixes: ['tt'],
  catalogs: [],
}

/**
 * A third addon that has catalogs but keeps them out of discovery, seeded with
 * the flag already set. Its manifest reaches clients with `catalogs` emptied by
 * the server, so it is the case that tells "hidden" apart from "has none".
 */
const cloudManifest: Manifest = {
  id: 'test.halo.cloud',
  version: '0.9.0',
  name: 'Fixture Cloud',
  description: 'A debrid-style addon whose catalog is hidden from Home.',
  resources: ['catalog', 'stream'],
  types: ['movie'],
  idPrefixes: ['tt'],
  catalogs: [{ type: 'movie', id: 'cloud', name: 'My Cloud' }],
}

function matchTitles(pool: Title[], type: string, extra: URLSearchParams): MetaPreview[] {
  const search = extra.get('search')?.trim().toLowerCase()
  return pool
    .filter((title) => title.type === type)
    .filter((title) => !search || title.name.toLowerCase().includes(search))
    .map(preview)
}

export const FIXTURE_ADDONS: FixtureAddon[] = [
  {
    base: 'https://catalogs.fixture.test',
    entryId: 'fixture-catalogs',
    manifest: catalogManifest,
    catalog: (type, id, extra) => {
      if (id === 'featured') return matchTitles(ALL, type, extra).slice(0, 3)
      if (id === 'genre') {
        const genre = extra.get('genre')
        if (!genre) return null
        return matchTitles(ALL, type, extra).filter((meta) => meta.genres?.includes(genre))
      }
      if (id !== 'top') return null
      return matchTitles(ALL, type, extra)
    },
    meta: (type, id) => {
      const title = ALL.find((entry) => entry.id === id && entry.type === type)
      return title ? detail(title) : null
    },
  },
  {
    base: 'https://streams.fixture.test',
    entryId: 'fixture-streams',
    manifest: streamManifest,
    stream: (_type, videoId, mediaUrl) => streamsFor(videoId, mediaUrl),
  },
  {
    base: 'https://cloud.fixture.test',
    entryId: 'fixture-cloud',
    manifest: cloudManifest,
    catalog: (type, id, extra) => (id === 'cloud' ? matchTitles(MOVIES, type, extra).slice(0, 4) : null),
    stream: (_type, videoId, mediaUrl) => streamsFor(videoId, mediaUrl).slice(0, 1),
  },
]

/** Everything the seeded library and history reference, for the dev server. */
export const FIXTURE_TITLES = { movies: MOVIES, series: SERIES, poster: POSTER }

/**
 * Stands in for the SSRF-guarded fetch, answering the addon protocol's URL
 * shapes: `{base}/manifest.json` and `{base}/{resource}/{type}/{id}.json`, the
 * latter optionally carrying `/{k=v&k=v}` before the suffix. Path segments
 * arrive percent-encoded because ids contain colons.
 *
 * Anything unknown answers 404, which is how a real addon says it cannot serve
 * a resource — the API turns that into a per-addon error rather than a failure.
 *
 * [resolveMediaUrl] is consulted per stream request rather than captured once,
 * because the URL has to name the host the client actually reached: a phone on
 * `10.0.2.2` and a simulator on `127.0.0.1` are the same server, and a stream
 * URL naming the wrong one is unplayable on the device that asked for it.
 */
export function fixtureAddonFetch(
  addons: FixtureAddon[] = FIXTURE_ADDONS,
  resolveMediaUrl: () => string | null = () => null,
): typeof fetch {
  return async (input) => {
    const href = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const addon = addons.find((entry) => href.startsWith(`${entry.base}/`))
    if (!addon) return json({ error: 'no such addon' }, 404)

    const path = new URL(href).pathname.replace(/^\//, '')
    const segments = path.split('/').map((segment) => decodeURIComponent(segment))
    const last = segments.length - 1
    if (!segments[last]?.endsWith('.json')) return json({ error: 'not an addon resource' }, 404)
    segments[last] = segments[last].slice(0, -'.json'.length)

    if (segments.length === 1 && segments[0] === 'manifest') return json(addon.manifest)
    if (segments.length < 3 || segments.length > 4) return json({ error: 'unknown resource shape' }, 404)

    const [resource, type, id, extra] = segments
    const params = new URLSearchParams(extra ?? '')
    if (resource === 'catalog') {
      const metas = addon.catalog?.(type!, id!, params)
      return metas ? json({ metas }) : json({ error: 'no such catalog' }, 404)
    }
    if (resource === 'meta') {
      const meta = addon.meta?.(type!, id!)
      return meta ? json({ meta }) : json({ error: 'no such meta' }, 404)
    }
    if (resource === 'stream') {
      const streams = addon.stream?.(type!, id!, resolveMediaUrl())
      return streams ? json({ streams }) : json({ error: 'no such stream' }, 404)
    }
    return json({ error: `unsupported resource ${resource}` }, 404)
  }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}
