import type { Stream } from '@halo/core'
import { useNav, type StreamsParams } from '../nav'
import { useStreams } from '../queries'

/**
 * Stream picker, grouped per addon (the server already filtered to playable
 * direct-URL streams). Row click hands the URL to the player along with the
 * binge/subtitle hints the stream carries.
 */
export function Streams(params: StreamsParams) {
  const { pop, push } = useNav()
  const { data, isLoading, error } = useStreams(params.type, params.videoId)

  const play = (addonId: string, stream: Stream) => {
    if (!stream.url) return
    push({
      name: 'player',
      url: stream.url,
      videoId: params.videoId,
      itemId: params.itemId,
      type: params.type,
      title: params.title,
      addonId,
      ...(params.metaId ? { metaId: params.metaId } : {}),
      ...(params.showName ? { showName: params.showName } : {}),
      ...(params.episodeLabel ? { episodeLabel: params.episodeLabel } : {}),
      ...(params.poster ? { poster: params.poster } : {}),
      ...(stream.behaviorHints?.bingeGroup ? { bingeGroup: stream.behaviorHints.bingeGroup } : {}),
      ...(stream.behaviorHints?.filename ? { filename: stream.behaviorHints.filename } : {}),
      ...(stream.behaviorHints?.videoSize ? { videoSize: stream.behaviorHints.videoSize } : {}),
    })
  }

  return (
    <div style={{ height: '100%', overflowY: 'auto', padding: '24px 32px 48px' }}>
      <button className="btn btn-glass" type="button" onClick={pop} style={{ marginBottom: 16 }}>
        ← Back
      </button>
      <div className="t-title">{params.title}</div>
      {params.episodeLabel && (
        <div className="t-caption" style={{ marginTop: 2 }}>
          {params.showName} · {params.episodeLabel}
        </div>
      )}

      {isLoading && <div className="t-caption" style={{ marginTop: 16 }}>Fetching sources…</div>}
      {error && <div className="error-text" style={{ marginTop: 16 }}>{String(error)}</div>}

      {data?.groups.length === 0 && !isLoading && (
        <div className="t-caption" style={{ marginTop: 16 }}>
          No playable sources from your addons for this title.
        </div>
      )}

      {data?.groups.map((group) => (
        <section key={group.addonId} style={{ marginTop: 20 }}>
          <div className="t-overline">{group.addonName}</div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginTop: 8, maxWidth: 720 }}>
            {group.streams.map((stream, i) => (
              <button
                key={i}
                type="button"
                className="stream-row"
                onClick={() => play(group.addonId, stream)}
              >
                <div className="t-callout">{stream.name ?? group.addonName}</div>
                {(stream.title ?? stream.description) && (
                  <div className="t-caption" style={{ whiteSpace: 'pre-line' }}>
                    {stream.title ?? stream.description}
                  </div>
                )}
              </button>
            ))}
          </div>
        </section>
      ))}

      {data && data.errors.length > 0 && (
        <div className="t-caption" style={{ marginTop: 24 }}>
          {data.errors.map((e) => `${e.id}: ${e.message}`).join(' · ')}
        </div>
      )}
    </div>
  )
}
