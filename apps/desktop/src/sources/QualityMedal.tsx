import type { Medal } from './sourcePresentation'

/**
 * A source's quality as a medal: gold for 4K, silver for Full HD, bronze for
 * HD and a plain badge below that. The resolution sits on a dark plate in the
 * metal's own colour and the tag is cut into the metal beside it. Fixed width,
 * so a column of them lines up whatever each one says.
 */
export function QualityMedal({ medal, large = false }: { medal: Medal; large?: boolean }) {
  const label = medal.tag ? `${medal.plate} ${medal.tag}` : medal.plate
  return (
    <span
      className={`medal medal-${medal.finish} ${large ? 'medal-lg' : ''} ${medal.tag ? '' : 'medal-untagged'}`}
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
