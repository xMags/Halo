import type { CSSProperties, ReactNode } from 'react'

interface SkeletonProps {
  width?: number | string
  height?: number | string
  borderRadius?: number | string
  style?: CSSProperties
  className?: string
  children?: ReactNode
}

/**
 * Primitive soft-pulsing placeholder element that adapts to both Dark and Light
 * themes, avoiding harsh moving glare over stark boxes.
 */
export function Skeleton({
  width,
  height,
  borderRadius = 4,
  style,
  className = '',
  children,
}: SkeletonProps) {
  return (
    <div
      className={`skeleton ${className}`}
      style={{
        width,
        height,
        borderRadius,
        ...style,
      }}
      aria-hidden
    >
      {children}
    </div>
  )
}

/** 2:3 poster card placeholder matching `.poster`. */
export function PosterCardSkeleton({ showKind }: { showKind?: boolean }) {
  return (
    <div className="poster poster-skeleton" aria-hidden>
      <div className="art poster-art skeleton">
        {showKind && <div className="skeleton skeleton-badge" />}
      </div>
      <div className="skeleton skeleton-text" style={{ width: '78%', height: 14, marginTop: 8 }} />
      <div className="skeleton skeleton-text" style={{ width: '42%', height: 12, marginTop: 4 }} />
    </div>
  )
}

/** 16:9 still card placeholder matching `.cw-card`. */
export function ContinueCardSkeleton() {
  return (
    <div className="cw-card cw-card-skeleton" aria-hidden>
      <div className="art cw-art skeleton" />
      <div className="skeleton skeleton-text" style={{ width: '70%', height: 14, marginTop: 8 }} />
      <div className="skeleton skeleton-text" style={{ width: '48%', height: 12, marginTop: 4 }} />
    </div>
  )
}

/** Shelf header bar and horizontal row of skeleton poster cards. */
export function ShelfSkeleton({
  titleWidth = 140,
  count = 6,
}: {
  titleWidth?: number
  count?: number
}) {
  return (
    <div className="shelf shelf-skeleton" aria-hidden>
      <div className="shelf-head">
        <div className="skeleton skeleton-text" style={{ width: titleWidth, height: 18 }} />
        <div className="spacer" />
        <div className="skeleton skeleton-text" style={{ width: 48, height: 14 }} />
      </div>
      <div className="shelf-scroll">
        {Array.from({ length: count }).map((_, index) => (
          <PosterCardSkeleton key={index} />
        ))}
      </div>
    </div>
  )
}

/** Horizontal row of continue-watching skeleton cards. */
export function ContinueShelfSkeleton({ count = 4 }: { count?: number }) {
  return (
    <div className="shelf shelf-skeleton" aria-hidden>
      <div className="shelf-head">
        <div className="skeleton skeleton-text" style={{ width: 160, height: 18 }} />
        <div className="spacer" />
        <div className="skeleton skeleton-text" style={{ width: 80, height: 14 }} />
      </div>
      <div className="shelf-scroll">
        {Array.from({ length: count }).map((_, index) => (
          <ContinueCardSkeleton key={index} />
        ))}
      </div>
    </div>
  )
}

/** Featured carousel hero placeholder matching `.hero`. */
export function HeroSkeleton() {
  return (
    <div className="hero hero-skeleton" aria-hidden>
      <div className="hero-bg skeleton" style={{ opacity: 0.65 }}>
        <div className="hero-scrim-left" />
        <div className="hero-scrim-bottom" />
      </div>
      <div className="hero-body">
        <div className="skeleton skeleton-text" style={{ width: 82, height: 12, marginBottom: 8 }} />
        <div className="skeleton skeleton-text hero-skeleton-title" style={{ width: '55%', maxWidth: 380, height: 34, marginBottom: 12 }} />
        <div className="skeleton skeleton-text" style={{ width: 220, height: 14, marginBottom: 20 }} />
        <div className="hero-actions">
          <div className="skeleton" style={{ width: 110, height: 36, borderRadius: 4 }} />
          <div className="skeleton" style={{ width: 90, height: 36, borderRadius: 4 }} />
          <div className="skeleton" style={{ width: 130, height: 36, borderRadius: 4 }} />
        </div>
      </div>
    </div>
  )
}

/** Full-screen detail placeholder matching `.detail-backdrop` and `.detail-grid`. */
export function DetailSkeleton() {
  return (
    <div className="view detail-skeleton" aria-hidden>
      <div style={{ paddingBottom: 48 }}>
        <div className="art detail-backdrop skeleton" style={{ opacity: 0.55 }}>
          <div className="detail-backdrop-fade" />
        </div>
        <div className="detail-grid detail-grid-movie">
          <div className="art detail-poster skeleton" />
          <div className="detail-head">
            <div className="skeleton skeleton-text" style={{ width: 90, height: 13, marginBottom: 10 }} />
            <div className="skeleton skeleton-text" style={{ width: '70%', maxWidth: 420, height: 34, marginBottom: 12 }} />
            <div className="skeleton skeleton-text" style={{ width: 260, height: 15, marginBottom: 20 }} />
            <div className="detail-actions" style={{ marginBottom: 24 }}>
              <div className="skeleton" style={{ width: 130, height: 36, borderRadius: 4 }} />
              <div className="skeleton" style={{ width: 120, height: 36, borderRadius: 4 }} />
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8, maxWidth: 620 }}>
              <div className="skeleton skeleton-text" style={{ width: '100%', height: 14 }} />
              <div className="skeleton skeleton-text" style={{ width: '94%', height: 14 }} />
              <div className="skeleton skeleton-text" style={{ width: '62%', height: 14 }} />
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

/** Top search match placeholder matching `.top-match`. */
export function TopMatchSkeleton() {
  return (
    <div className="top-match top-match-skeleton" aria-hidden>
      <div className="art top-match-art skeleton" />
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8, paddingTop: 4, minWidth: 0 }}>
        <div className="skeleton skeleton-text" style={{ width: 70, height: 12 }} />
        <div className="skeleton skeleton-text" style={{ width: '60%', maxWidth: 260, height: 22 }} />
        <div className="skeleton skeleton-text" style={{ width: '40%', maxWidth: 180, height: 13 }} />
      </div>
    </div>
  )
}

/** Download row placeholder matching `.dl-item`. */
export function DownloadRowSkeleton() {
  return (
    <div className="dl-item dl-skeleton-item" aria-hidden>
      <div className="art dl-thumb skeleton" />
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 8, justifyContent: 'center' }}>
        <div className="skeleton skeleton-text" style={{ width: '55%', maxWidth: 320, height: 15 }} />
        <div className="skeleton skeleton-text" style={{ width: '35%', maxWidth: 200, height: 12 }} />
        <div className="skeleton" style={{ width: '100%', height: 3, borderRadius: 2, marginTop: 2 }} />
      </div>
    </div>
  )
}
