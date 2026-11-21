import { fetch as nativeFetch } from '@tauri-apps/plugin-http'
import { useEffect, useState } from 'react'
import { useResolvedTheme } from '../../localPrefs'
import {
  chooseHeroLogoTreatment,
  heroLogoCoverage,
  heroLogoLuminance,
  HERO_LOGO_MAX_BYTES,
  HERO_LOGO_SAMPLE_WIDTH,
  isHeroLogoUrl,
} from './heroLogoPolicy'

/*
 * A title's logo as the title page draws it (heroLogoPolicy). The dark theme
 * shows the addon's URL straight away. The light theme shows nothing until
 * the logo has been fetched and judged, so a white mark never flashes on the
 * pale page. The fetch goes through the native HTTP client: the webview's own
 * would need the logo host's CORS consent to read the pixels or mask with
 * them, and the bytes then live in a blob URL the page may use freely.
 */

export type HeroLogo =
  | { kind: 'pending' }
  | { kind: 'name' }
  | { kind: 'original'; src: string }
  /** The logo's shape filled with the page's ink; `ratio` is its width over its height. */
  | { kind: 'inked'; src: string; ratio: number }

interface JudgedLogo {
  /** A blob URL of the fetched bytes. */
  src: string
  ratio: number
  luminance: number | null
  coverage: number | null
}

const FETCH_TIMEOUT_MS = 15_000
/** Judged logos kept, one per URL, so the hero and compact bar share one fetch and a revisit none. */
const CACHE_LIMIT = 24
const judged = new Map<string, Promise<JudgedLogo | null>>()

/** The body, or null when it is larger than a logo may be. */
async function readCapped(response: Response): Promise<Uint8Array<ArrayBuffer> | null> {
  const declared = Number(response.headers.get('content-length'))
  if (Number.isFinite(declared) && declared > HERO_LOGO_MAX_BYTES) {
    await response.body?.cancel()
    return null
  }
  const reader = response.body?.getReader()
  if (!reader) return null
  const chunks: Uint8Array[] = []
  let total = 0
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    total += value.byteLength
    if (total > HERO_LOGO_MAX_BYTES) {
      await reader.cancel()
      return null
    }
    chunks.push(value)
  }
  const bytes = new Uint8Array(total)
  let offset = 0
  for (const chunk of chunks) {
    bytes.set(chunk, offset)
    offset += chunk.byteLength
  }
  return bytes
}

/** Fetches, decodes and samples a logo; null when any step fails (the page then shows the name). */
async function judge(url: string): Promise<JudgedLogo | null> {
  if (!isHeroLogoUrl(url)) return null
  try {
    const response = await nativeFetch(url, { signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) })
    if (!response.ok) {
      await response.body?.cancel()
      return null
    }
    const bytes = await readCapped(response)
    if (!bytes) return null
    const blob = new Blob([bytes], { type: response.headers.get('content-type') ?? '' })
    const bitmap = await createImageBitmap(blob)
    try {
      if (bitmap.width === 0 || bitmap.height === 0) return null
      const width = HERO_LOGO_SAMPLE_WIDTH
      const height = Math.max(1, Math.round((bitmap.height * width) / bitmap.width))
      const context = new OffscreenCanvas(width, height).getContext('2d')
      if (!context) return null
      context.drawImage(bitmap, 0, 0, width, height)
      const pixels = context.getImageData(0, 0, width, height).data
      return {
        src: URL.createObjectURL(blob),
        ratio: bitmap.width / bitmap.height,
        luminance: heroLogoLuminance(pixels),
        coverage: heroLogoCoverage(pixels),
      }
    } finally {
      bitmap.close()
    }
  } catch {
    return null
  }
}

function judgedLogo(url: string): Promise<JudgedLogo | null> {
  const cached = judged.get(url)
  if (cached) {
    // Most recently used goes last, so the oldest is the one let go.
    judged.delete(url)
    judged.set(url, cached)
    return cached
  }
  const pending = judge(url)
  judged.set(url, pending)
  while (judged.size > CACHE_LIMIT) {
    const oldest = judged.keys().next().value as string
    const dropped = judged.get(oldest)
    judged.delete(oldest)
    void dropped?.then((logo) => logo && URL.revokeObjectURL(logo.src))
  }
  return pending
}

export function useHeroLogo(url: string | undefined): HeroLogo {
  const light = useResolvedTheme() === 'light'
  const [result, setResult] = useState<{ url: string; logo: JudgedLogo | null } | null>(null)

  useEffect(() => {
    if (!url || !light) return
    let current = true
    void judgedLogo(url).then((logo) => {
      if (current) setResult({ url, logo })
    })
    return () => {
      current = false
    }
  }, [url, light])

  if (!url) return { kind: 'name' }
  if (!light) return { kind: 'original', src: url }
  if (result?.url !== url) return { kind: 'pending' }
  const logo = result.logo
  if (!logo) return { kind: 'name' }
  switch (chooseHeroLogoTreatment(logo.luminance, logo.coverage, true)) {
    case 'original':
      return { kind: 'original', src: logo.src }
    case 'inked':
      return { kind: 'inked', src: logo.src, ratio: logo.ratio }
    case 'name':
      return { kind: 'name' }
  }
}
