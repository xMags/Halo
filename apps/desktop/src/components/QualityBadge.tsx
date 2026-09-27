/**
 * The native Halo Desktop's QualityBadge: a tier stamped on a dark plate
 * inside a frame, with an optional detail beside it (`HDR`). Gold is spent on
 * the top tier only, and stays gold in both themes: it is a mark, not a
 * palette colour. Muted follows the theme.
 */
export function QualityBadge({
  tier,
  detail,
  gold,
  labelSize = 12,
}: {
  tier: string
  detail?: string
  gold: boolean
  /** The pick card sets its label a point larger than the rows. */
  labelSize?: number
}) {
  return (
    <span className={`qbadge ${gold ? 'qbadge-gold' : 'qbadge-muted'}`} style={{ fontSize: labelSize }}>
      <span className="qbadge-plate">
        <span className="qbadge-tier">{tier}</span>
      </span>
      {detail ? <span className="qbadge-detail">{detail}</span> : null}
    </span>
  )
}
