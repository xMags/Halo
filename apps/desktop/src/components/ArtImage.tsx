import { useState } from 'react'
import { cardArtworkUrl } from './artworkUrl'
import { FluentIcon, type FluentGlyph } from './FluentIcon'

/** What a soft placeholder stands for, and so which glyph it draws. */
export type PlaceholderKind = 'movie' | 'series' | 'person'

const PLACEHOLDER_GLYPHS: Record<PlaceholderKind, FluentGlyph> = { movie: 'film', series: 'tv', person: 'person' }

/** A poster or portrait that has not arrived: a soft surface and one faint glyph, nothing else. */
export function SoftPlaceholder({ kind }: { kind: PlaceholderKind }) {
  return (
    <div className="art-soft" aria-hidden>
      <FluentIcon glyph={PLACEHOLDER_GLYPHS[kind]} size={30} />
    </div>
  )
}

/**
 * Artwork from an addon, then an optional second source, then the placeholder.
 *
 * Addons hand out poster and still URLs that 404 often enough to matter (a
 * catalog row outlives the CDN behind it), and a bare `<img>` answers that
 * with the browser's broken-image glyph — which lands on top of the design's
 * placeholder fill and reads as a bug. On error the image is dropped so the
 * placeholder underneath is what shows, with the optional label on it.
 *
 * Renders nothing at all when there is no URL, so callers can pass a possibly
 * undefined poster and get the plain placeholder. With `placeholder` set (Search
 * and the person page only), the soft glyph placeholder stands in instead and
 * the label is not printed.
 */
export function ArtImage({
  src,
  fallback,
  label,
  lazy,
  placeholder,
}: {
  src: string | undefined
  /** Tried when [src] is missing or fails, before the placeholder. */
  fallback?: string
  /** Printed on the placeholder when the art is missing or fails. */
  label?: string
  lazy?: boolean
  placeholder?: PlaceholderKind
}) {
  const sources = [src, fallback].filter((url): url is string => Boolean(url))
  const [failures, setFailures] = useState(0)
  const current = sources[failures]

  if (!current) {
    if (placeholder) return <SoftPlaceholder kind={placeholder} />
    return label ? <div className="art-label">{label}</div> : null
  }
  const image = (
    <img
      // Keyed by the URL so a fallback starts as a fresh image, not the failed one.
      key={current}
      // Card-sized art: a huge original shrunk this far pixelates.
      src={cardArtworkUrl(current)}
      alt=""
      draggable={false}
      loading={lazy ? 'lazy' : undefined}
      onError={() => setFailures((count) => count + 1)}
    />
  )
  // Under the image, so a slow one never shows the striped fill first.
  return placeholder ? (
    <>
      <SoftPlaceholder kind={placeholder} />
      {image}
    </>
  ) : (
    image
  )
}
