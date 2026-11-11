import type { MetaPreview } from '@halo/core'
import { useEffect, useState } from 'react'
import { cardArtworkUrl, metahubPosterUrl } from '../components/artworkUrl'
import { hasNoPoster, metaKey } from './searchResults'

/**
 * Which search results have no artwork that loads.
 *
 * An addon's answer does not say so: Cinemeta gives every title a poster
 * link, and for the obscure ones (fan films, announced sequels) that link is
 * simply dead. A card without art is the surest sign of a result nobody was
 * looking for, so the results are ordered with those last, and that needs the
 * answer for every result, not only for the cards scrolled into view. Each
 * title's poster is therefore requested as soon as results arrive, the same
 * URLs the cards then draw from the browser's cache.
 */

/** One answer per image URL for the life of the app: a poster that loaded once is not asked again. */
const loads = new Map<string, Promise<boolean>>()

function imageLoads(url: string): Promise<boolean> {
  let pending = loads.get(url)
  if (!pending) {
    pending = new Promise<boolean>((resolve) => {
      const image = new Image()
      image.onload = () => resolve(true)
      image.onerror = () => resolve(false)
      image.src = url
    })
    loads.set(url, pending)
  }
  return pending
}

/** The sources a card tries, in its order: the addon's poster, then Stremio's own for an IMDb title. */
function posterSources(meta: MetaPreview): string[] {
  const sources = [hasNoPoster(meta) ? undefined : meta.poster, metahubPosterUrl(meta.id)]
  return [...new Set(sources.filter((url): url is string => Boolean(url)).map((url) => cardArtworkUrl(url)))]
}

async function hasArtwork(meta: MetaPreview): Promise<boolean> {
  for (const url of posterSources(meta)) {
    if (await imageLoads(url)) return true
  }
  return false
}

/** The keys (`type:id`) of the given titles whose artwork is known not to load. Grows as answers come in. */
export function useMissingArt(metas: readonly MetaPreview[]): ReadonlySet<string> {
  const [missing, setMissing] = useState<ReadonlySet<string>>(() => new Set())

  useEffect(() => {
    let current = true
    for (const meta of metas) {
      const key = metaKey(meta)
      void hasArtwork(meta).then((found) => {
        if (found || !current) return
        setMissing((known) => (known.has(key) ? known : new Set(known).add(key)))
      })
    }
    return () => {
      current = false
    }
  }, [metas])

  return missing
}
