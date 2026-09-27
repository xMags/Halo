import type { ReactNode } from 'react'
import { FluentIcon } from './FluentIcon'

/**
 * The header every page except Detail opens with: the page title bottom-
 * aligned against whatever controls that page owns. A page reached from
 * another page (the catalog grid) passes `onBack` for the native header's
 * back button before the title.
 */
export function SectionHeader({
  title,
  children,
  wrap,
  onBack,
}: {
  title: string
  children?: ReactNode
  /** Lets a crowded control group wrap under the title (Downloads). */
  wrap?: boolean
  onBack?: () => void
}) {
  return (
    <div className="page-head" style={wrap ? { flexWrap: 'wrap' } : undefined}>
      {onBack && (
        <button type="button" className="btn page-back" aria-label="Back" title="Back" onClick={onBack}>
          <FluentIcon glyph="back" />
        </button>
      )}
      <div className="page-title">{title}</div>
      <div className="spacer" />
      {children && <div className="head-right">{children}</div>}
    </div>
  )
}
