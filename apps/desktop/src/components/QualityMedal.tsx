import type { Medal } from './medal'

/**
 * A quality as a medal: gold for 4K, silver for Full HD, bronze for HD and a
 * plain badge below that. The resolution sits on a dark plate in the metal's
 * own colour and the tag is cut into the metal beside it. Fixed width per
 * size, so a column of them lines up whatever each one says.
 *
 * `sm` sits inline in a line of text (the title page, Downloads), `md` heads a
 * source row, `lg` the pick card and the fullscreen player.
 */
export function QualityMedal({ medal, size = 'md' }: { medal: Medal; size?: 'sm' | 'md' | 'lg' }) {
  const label = medal.tag ? `${medal.plate} ${medal.tag}` : medal.plate
  return (
    <span
      className={`medal medal-${size} medal-${medal.finish} ${medal.tag ? '' : 'medal-untagged'}`}
      role="img"
      aria-label={label}
    >
      <span className="medal-plate">
        <span className="medal-ink">{medal.plate}</span>
      </span>
      {medal.tag && <span className="medal-tag">{medal.tag}</span>}
    </span>
  )
}
