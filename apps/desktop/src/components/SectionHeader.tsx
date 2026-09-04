import type { ReactNode } from 'react'

/**
 * The header every page except Detail opens with: the page title bottom-
 * aligned against whatever controls that page owns.
 */
export function SectionHeader({
  title,
  children,
  wrap,
}: {
  title: string
  children?: ReactNode
  /** Lets a crowded control group wrap under the title (Downloads). */
  wrap?: boolean
}) {
  return (
    <div className="page-head" style={wrap ? { flexWrap: 'wrap' } : undefined}>
      <div className="page-title">{title}</div>
      <div className="spacer" />
      {children && <div className="head-right">{children}</div>}
    </div>
  )
}
