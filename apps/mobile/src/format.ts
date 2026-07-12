/** 2_476_202_311 → "2.3 GB". Sizes come from stream behaviorHints / download progress. */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return ''
  const gb = bytes / 1024 ** 3
  if (gb >= 1) return `${gb.toFixed(gb >= 10 ? 0 : 1)} GB`
  const mb = bytes / 1024 ** 2
  if (mb >= 1) return `${mb.toFixed(0)} MB`
  return `${Math.ceil(bytes / 1024)} KB`
}

/** Clamp to the unit interval — gesture values, seek fractions. */
export function clamp01(value: number): number {
  return Math.max(0, Math.min(1, value))
}
