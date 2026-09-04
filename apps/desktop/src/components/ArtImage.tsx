import { useState } from 'react'

/**
 * Artwork from an addon, with the placeholder as its fallback.
 *
 * Addons hand out poster and still URLs that 404 often enough to matter (a
 * catalog row outlives the CDN behind it), and a bare `<img>` answers that
 * with the browser's broken-image glyph — which lands on top of the design's
 * placeholder fill and reads as a bug. On error the image is dropped so the
 * placeholder underneath is what shows, with the optional label on it.
 *
 * Renders nothing at all when there is no URL, so callers can pass a possibly
 * undefined poster and get the plain placeholder.
 */
export function ArtImage({
  src,
  label,
  lazy,
}: {
  src: string | undefined
  /** Printed on the placeholder when the art is missing or fails. */
  label?: string
  lazy?: boolean
}) {
  const [failed, setFailed] = useState(false)

  if (!src || failed) return label ? <div className="art-label">{label}</div> : null
  return (
    <img
      src={src}
      alt=""
      draggable={false}
      loading={lazy ? 'lazy' : undefined}
      onError={() => setFailed(true)}
    />
  )
}
