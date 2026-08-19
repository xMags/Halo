import type { AddonError, Stream } from '@halo/core'
import { useMemo, useState } from 'react'
import { Icon } from '../components/Icon'
import { formatBytes } from '../format'
import { useNav, type StreamsParams } from '../nav'
import { useStreams } from '../queries'
import { usePublishScreenTitle } from '../screenTitle'
import { compareStreams, parseStreamInfo, qualityRank, type StreamInfo } from '../streamInfo'

interface Source {
  addonId: string
  addonName: string
  stream: Stream
  info: StreamInfo
}

/** Pseudo-quality filter for "plays immediately", which cuts across resolutions. */
const INSTANT = 'instant'

/** Sub-second resolutions read as "0.0 s" in seconds; show those in ms. */
function formatElapsed(ms: number): string {
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`
}

/**
 * Stream picker: one recommended source up top, then everything else grouped
 * by the addon that offered it. The server has already filtered to playable
 * direct URLs; what is left is helping the user pick between them, which is
 * why every row is parsed for quality, codec, size and cached state.
 */
export function Streams(params: StreamsParams) {
  const { push } = useNav()
  const { data, isLoading, error } = useStreams(params.type, params.videoId)
  const [filter, setFilter] = useState<string | null>(null)

  const sources = useMemo<Source[]>(
    () =>
      (data?.groups ?? []).flatMap((group) =>
        group.streams
          .filter((stream) => !!stream.url)
          .map((stream) => ({
            addonId: group.addonId,
            addonName: group.addonName,
            stream,
            info: parseStreamInfo(stream),
          })),
      ),
    [data],
  )

  const best = useMemo(
    () => [...sources].sort((a, b) => compareStreams(a.info, b.info))[0] ?? null,
    [sources],
  )

  const filters = useMemo(() => {
    const qualities = new Map<string, number>()
    let instant = 0
    for (const source of sources) {
      if (source.info.cached) instant += 1
      const key = source.info.quality
      if (key) qualities.set(key, (qualities.get(key) ?? 0) + 1)
    }
    const tiers = [...qualities.entries()].sort(
      (a, b) => qualityRank(a[0] as StreamInfo['quality']) - qualityRank(b[0] as StreamInfo['quality']),
    )
    return [
      { key: null as string | null, label: 'All', count: sources.length },
      ...(instant > 0 ? [{ key: INSTANT, label: 'Instant', count: instant }] : []),
      ...tiers.map(([quality, count]) => ({ key: quality, label: quality, count })),
    ]
  }, [sources])

  const matches = (source: Source) =>
    filter === null || (filter === INSTANT ? source.info.cached === true : source.info.quality === filter)

  const groups = useMemo(() => {
    const byAddon = new Map<string, Source[]>()
    for (const source of sources) {
      if (!matches(source)) continue
      const existing = byAddon.get(source.addonId)
      if (existing) existing.push(source)
      else byAddon.set(source.addonId, [source])
    }
    // Keep each addon's own ordering: it ranks its results, and reshuffling
    // would throw away the only signal we don't have to guess at.
    return [...byAddon.values()]
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sources, filter])

  usePublishScreenTitle('Sources', params.episodeLabel ?? params.title.toUpperCase())

  const play = (source: Source) => {
    const { stream } = source
    if (!stream.url) return
    push({
      name: 'player',
      url: stream.url,
      videoId: params.videoId,
      itemId: params.itemId,
      type: params.type,
      title: params.title,
      addonId: source.addonId,
      ...(params.metaId ? { metaId: params.metaId } : {}),
      ...(params.showName ? { showName: params.showName } : {}),
      ...(params.episodeLabel ? { episodeLabel: params.episodeLabel } : {}),
      ...(params.poster ? { poster: params.poster } : {}),
      ...(stream.behaviorHints?.bingeGroup ? { bingeGroup: stream.behaviorHints.bingeGroup } : {}),
      ...(stream.behaviorHints?.filename ? { filename: stream.behaviorHints.filename } : {}),
      ...(stream.behaviorHints?.videoSize ? { videoSize: stream.behaviorHints.videoSize } : {}),
    })
  }

  const addonCount = data?.groups.filter((g) => g.streams.length > 0).length ?? 0

  return (
    <div className="view no-bar">
      <div className="src-head">
        <div className="art src-poster">
          {params.poster && <img src={params.poster} alt="" draggable={false} />}
        </div>
        <div style={{ minWidth: 0, paddingBottom: 2 }}>
          {params.episodeLabel && (
            <div className="kicker kicker-accent">
              {params.episodeLabel} · {params.title}
            </div>
          )}
          <div className="src-title ellipsis">{params.showName ?? params.title}</div>
          <div style={{ marginTop: 6, fontSize: 12.5, color: 'var(--text-muted)' }}>
            {isLoading
              ? 'Asking your addons…'
              : `${sources.length} source${sources.length === 1 ? '' : 's'} from ${addonCount} addon${addonCount === 1 ? '' : 's'} · resolved in ${formatElapsed(data?.elapsedMs ?? 0)}`}
          </div>
        </div>
        <div className="spacer" />
        {sources.length > 0 && (
          <div style={{ display: 'flex', gap: 7, paddingBottom: 4, flexWrap: 'wrap' }}>
            {filters.map((tier) => (
              <button
                key={tier.key ?? 'all'}
                type="button"
                className={`filter-pill ${filter === tier.key ? 'filter-pill-active' : ''}`}
                onClick={() => setFilter(tier.key)}
              >
                {tier.label} {tier.count}
              </button>
            ))}
          </div>
        )}
      </div>

      {isLoading && (
        <div className="state-note">
          <span className="spinner" /> Fetching sources…
        </div>
      )}
      {error && <div className="state-note error-text">{String(error)}</div>}
      {data && sources.length === 0 && !isLoading && (
        <div className="state-note">No playable sources from your addons for this title.</div>
      )}

      {best && (
        <div className="src-body">
          <div className="kicker">BEST FOR YOU</div>
          <button type="button" className="best-row" onClick={() => play(best)}>
            <div className="best-quality">
              <div className="best-quality-value">{best.info.quality ?? 'AUTO'}</div>
              {best.info.dynamicRange && (
                <div className="best-quality-sub">{best.info.dynamicRange}</div>
              )}
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <div style={{ fontSize: 14, fontWeight: 700 }}>
                  {best.info.cached ? 'Instant · already on your debrid' : best.addonName}
                </div>
                {best.info.cached && <div className="badge badge-success">NO WAIT</div>}
              </div>
              <div className="file-name ellipsis" style={{ marginTop: 6 }}>
                {best.info.filename}
              </div>
              <TechChips info={best.info} />
            </div>
            <div className="best-play">
              <Icon name="play" size={12} />
              <span>Play</span>
            </div>
          </button>
        </div>
      )}

      {groups.length > 0 && (
        <div className="src-body" style={{ paddingTop: 24 }}>
          {groups.map((group) => (
            <div key={group[0]!.addonId} style={{ marginBottom: 22 }}>
              <div className="src-group-head">
                <div className="kicker">{group[0]!.addonName.toUpperCase()}</div>
                <div className="src-rule" />
                <div className="meta-mono" style={{ fontSize: 9.5 }}>
                  {group.length}
                </div>
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                {group.map((source, index) => (
                  <SourceRow
                    key={`${source.addonId}:${index}:${source.stream.url}`}
                    source={source}
                    onPlay={() => play(source)}
                  />
                ))}
              </div>
            </div>
          ))}
        </div>
      )}

      {filter !== null && groups.length === 0 && sources.length > 0 && (
        <div className="state-note">No sources match that filter.</div>
      )}

      {data && data.errors.length > 0 && (
        <div className="state-note" style={{ paddingTop: 8 }}>
          {data.errors.map(formatAddonFailure).join(' · ')}
        </div>
      )}
    </div>
  )
}

function TechChips({ info }: { info: StreamInfo }) {
  const chips = [
    info.codec,
    info.audio,
    info.sizeBytes ? formatBytes(info.sizeBytes) : null,
    info.languages.length > 0 ? info.languages.join(' · ') : null,
  ].filter((chip): chip is string => !!chip)
  if (chips.length === 0) return null
  return (
    <div className="tech-chips">
      {chips.map((chip) => (
        <div key={chip} className="badge badge-outline">
          {chip}
        </div>
      ))}
    </div>
  )
}

function SourceRow({ source, onPlay }: { source: Source; onPlay: () => void }) {
  const { info } = source
  // The addon's own line wins when it has one: it was parsed *from* that text,
  // so printing both spells the same facts twice in two different formats.
  const meta =
    info.detail || [info.quality, info.codec, info.audio, info.dynamicRange].filter(Boolean).join(' · ')

  return (
    <button type="button" className="src-row" onClick={onPlay} title={info.filename}>
      <div className={`q-block ${info.quality === '2160p' ? 'q-block-hi' : ''}`}>
        {info.quality ?? '—'}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="file-name ellipsis">{info.filename}</div>
        {meta && (
          <div className="ellipsis" style={{ marginTop: 4, fontSize: 11, color: 'var(--text-dim)' }}>
            {meta}
          </div>
        )}
      </div>
      {info.cached !== null && (
        <div className={`badge ${info.cached ? 'badge-success' : 'badge-warning'}`}>
          {info.cached ? 'CACHED' : 'QUEUE'}
        </div>
      )}
      <div className="src-size">{info.sizeBytes ? formatBytes(info.sizeBytes) : ''}</div>
      <div className="src-play">
        <Icon name="play" size={11} />
      </div>
    </button>
  )
}

/** Formats only the server's safe compatibility fields, never opaque ids or legacy raw messages. */
function formatAddonFailure(error: AddonError): string {
  const candidate = error.name?.replace(/[\u0000-\u001f\u007f]/g, ' ').trim()
  const name =
    candidate && !/https?:\/\/|[/\\]|[A-Za-z0-9_-]{32,}/i.test(candidate)
      ? candidate.slice(0, 80)
      : 'An addon'
  switch (error.code) {
    case 'timeout':
      return `${name} timed out.`
    case 'upstream_http':
      return error.status ? `${name} returned HTTP ${error.status}.` : `${name} returned an HTTP error.`
    case 'blocked_target':
      return `${name} was blocked for safety.`
    case 'invalid_response':
      return `${name} returned invalid data.`
    case 'unavailable':
    default:
      return `${name} is unavailable.`
  }
}
