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
      <div className="hero-scrim" />
      <div className="hero-body">
        <div className="skeleton skeleton-text" style={{ width: 80, height: 12, marginBottom: 4 }} />
        <div
          className="skeleton skeleton-text hero-skeleton-title"
          style={{ width: '60%', maxWidth: 420, height: 'var(--ht, 36px)', borderRadius: 6, marginBottom: 8 }}
        />
        <div className="skeleton skeleton-text" style={{ width: 240, height: 14, marginBottom: 12 }} />
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginBottom: 12, maxWidth: 500 }}>
          <div className="skeleton skeleton-text" style={{ width: '100%', height: 13 }} />
          <div className="skeleton skeleton-text" style={{ width: '92%', height: 13 }} />
          <div className="skeleton skeleton-text" style={{ width: '60%', height: 13 }} />
        </div>
        <div className="hero-actions">
          <div className="skeleton" style={{ width: 104, height: 32, borderRadius: 4 }} />
          <div className="skeleton" style={{ width: 88, height: 32, borderRadius: 4 }} />
          <div className="skeleton" style={{ width: 128, height: 32, borderRadius: 4 }} />
        </div>
      </div>
      <div className="hero-pips">
        <span className="pip pip-active" style={{ opacity: 0.5 }} />
        <span className="pip" style={{ opacity: 0.3 }} />
        <span className="pip" style={{ opacity: 0.3 }} />
      </div>
    </div>
  )
}

/** Full-screen detail placeholder matching `.detail-view` with full-page background. */
export function DetailSkeleton() {
  return (
    <div className="view detail-view detail-skeleton" aria-hidden>
      <div className="art detail-bg">
        <div className="detail-bg-scrim" />
      </div>
      <div style={{ position: 'relative', zIndex: 1, paddingBottom: 48 }}>
        <div style={{ height: 268 }} />
        <div className="detail-grid detail-grid-movie">
          <div className="art detail-poster skeleton" />
          <div className="detail-head">
            <div className="skeleton skeleton-text" style={{ width: 90, height: 12, marginBottom: 8 }} />
            <div
              className="skeleton skeleton-text"
              style={{ width: '70%', maxWidth: 420, height: 36, borderRadius: 6, marginBottom: 10 }}
            />
            <div className="skeleton skeleton-text" style={{ width: 260, height: 15, marginBottom: 16 }} />
            <div className="detail-actions" style={{ marginBottom: 20 }}>
              <div className="skeleton" style={{ width: 130, height: 36, borderRadius: 4 }} />
              <div className="skeleton" style={{ width: 120, height: 36, borderRadius: 4 }} />
            </div>
          </div>
          <div className="detail-aside">
            <div className="card" style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <div className="skeleton skeleton-text" style={{ width: 80, height: 12 }} />
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                <div className="skeleton skeleton-text" style={{ width: '100%', height: 14 }} />
                <div className="skeleton skeleton-text" style={{ width: '95%', height: 14 }} />
                <div className="skeleton skeleton-text" style={{ width: '65%', height: 14 }} />
              </div>
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
        <div
          className="skeleton skeleton-text"
          style={{ width: '65%', maxWidth: 260, height: 22, borderRadius: 4 }}
        />
        <div className="skeleton skeleton-text" style={{ width: 180, height: 13 }} />
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginTop: 4 }}>
          <div className="skeleton skeleton-text" style={{ width: '90%', height: 13 }} />
          <div className="skeleton skeleton-text" style={{ width: '60%', height: 13 }} />
        </div>
      </div>
    </div>
  )
}

/** Download row placeholder matching `.dl-row`. */
export function DownloadRowSkeleton() {
  return (
    <div className="dl-row dl-skeleton-row" aria-hidden>
      <div className="art dl-thumb skeleton" />
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
          <div className="skeleton skeleton-text" style={{ width: 180, height: 16 }} />
          <div
            className="skeleton skeleton-badge"
            style={{ position: 'static', width: 60, height: 18, borderRadius: 3 }}
          />
        </div>
        <div className="skeleton" style={{ width: '100%', height: 3, borderRadius: 2 }} />
        <div className="skeleton skeleton-text" style={{ width: 220, height: 12 }} />
      </div>
    </div>
  )
}
