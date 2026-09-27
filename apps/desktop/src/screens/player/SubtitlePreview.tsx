import type { SubtitleOutline } from '@halo/core'
import { subtitlePreviewMetrics } from '../../playerLogic'

/** The eight directions an outline copy is offset in, as the native preview rings the glyphs. */
const OUTLINE_DIRECTIONS: ReadonlyArray<[number, number]> = [
  [-1, -1],
  [0, -1],
  [1, -1],
  [-1, 0],
  [1, 0],
  [-1, 1],
  [0, 1],
  [1, 1],
]

/**
 * The caption's outline and shadow as one `text-shadow`: eight copies ringing
 * the glyphs, then the drop shadow, listed last so it paints under the
 * outline as the native shadow copy does. Shared by the player panel and
 * Settings, which preview the same synced appearance.
 */
export function subtitleCaptionShadow(
  metrics: { outlineWidth: number; shadowOffset: number },
  shadow: boolean,
): string | undefined {
  const layers =
    metrics.outlineWidth > 0
      ? OUTLINE_DIRECTIONS.map(
          ([x, y]) => `${x * metrics.outlineWidth}px ${y * metrics.outlineWidth}px 0 rgba(0, 0, 0, 0.94)`,
        )
      : []
  if (shadow) layers.push(`${metrics.shadowOffset}px ${metrics.shadowOffset}px 0 rgba(0, 0, 0, 0.65)`)
  return layers.length > 0 ? layers.join(', ') : undefined
}

/**
 * A caption drawn the way the current appearance settings will draw it: size
 * scaled like mpv's sub-scale, eight outline copies ringing the glyphs, and a
 * drop shadow under them. The stage is pinned dark whatever the theme, since
 * it stands in for a video frame.
 */
export function SubtitlePreview({
  text,
  scalePercent,
  outline,
  shadow,
  fontFamily,
}: {
  text: string
  scalePercent: number
  outline: SubtitleOutline
  shadow: boolean
  /** The synced subtitle family; undefined leaves it to mpv's default face. */
  fontFamily: string | undefined
}) {
  const metrics = subtitlePreviewMetrics(scalePercent, outline)
  return (
    <div className="pcard">
      <div className="prail-kicker">PREVIEW</div>
      <div className="psub-stage">
        <div
          className="psub-caption"
          style={{
            fontSize: metrics.fontSize,
            fontFamily: fontFamily ? `'${fontFamily}', sans-serif` : 'sans-serif',
            textShadow: subtitleCaptionShadow(metrics, shadow),
          }}
        >
          {text}
        </div>
      </div>
    </div>
  )
}
