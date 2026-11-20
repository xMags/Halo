/**
 * A small in-memory cache with a time to live and a size cap, for answers from
 * outside services (TMDB) that change rarely and cost a request each.
 *
 * - Expiry is checked on read: an entry past its time is dropped then, never
 *   by a timer, so the cache holds no handles open.
 * - Over `maxEntries`, the least recently used entry goes first. A Map keeps
 *   insertion order, so a read moves its key to the end and the first key is
 *   always the oldest use.
 * - `getOrLoad` shares one load between concurrent callers of the same key,
 *   so a burst of identical requests costs one upstream call. A load that
 *   fails is not cached: the next caller tries again.
 */
export interface TtlCache<V> {
  /** The fresh value for `key`, or undefined when there is none. */
  get(key: string): V | undefined
  set(key: string, value: V): void
  /** The fresh value for `key`, else the result of `loader`, stored on success. */
  getOrLoad(key: string, loader: () => Promise<V>): Promise<V>
  /** Forgets everything, as when the credential the answers came with changes. */
  clear(): void
  readonly size: number
}

export interface TtlCacheOptions {
  maxEntries: number
  ttlMs: number
  /** Clock in epoch milliseconds; tests pass a fake one. */
  now?: () => number
}

interface Entry<V> {
  value: V
  expiresAt: number
}

export function createTtlCache<V>(options: TtlCacheOptions): TtlCache<V> {
  const { maxEntries, ttlMs } = options
  if (!Number.isSafeInteger(maxEntries) || maxEntries < 1) throw new Error('maxEntries must be a positive integer')
  if (!Number.isFinite(ttlMs) || ttlMs <= 0) throw new Error('ttlMs must be a positive number')
  const now = options.now ?? Date.now
  const entries = new Map<string, Entry<V>>()
  const loading = new Map<string, Promise<V>>()
  /** Bumped by `clear`, so a load started before it does not store an answer after it. */
  let generation = 0

  const lookup = (key: string): Entry<V> | undefined => {
    const entry = entries.get(key)
    if (!entry) return undefined
    entries.delete(key)
    if (now() >= entry.expiresAt) return undefined
    // Re-inserting marks it most recently used.
    entries.set(key, entry)
    return entry
  }

  const set = (key: string, value: V): void => {
    entries.delete(key)
    entries.set(key, { value, expiresAt: now() + ttlMs })
    while (entries.size > maxEntries) {
      const oldest = entries.keys().next()
      if (oldest.done) break
      entries.delete(oldest.value)
    }
  }

  return {
    get: (key) => lookup(key)?.value,
    set,
    getOrLoad(key, loader) {
      const hit = lookup(key)
      if (hit) return Promise.resolve(hit.value)
      const pending = loading.get(key)
      if (pending) return pending
      const startedIn = generation
      // The loader starts on the next microtask, so the promise is registered
      // before it can settle (even when the loader throws synchronously).
      const load = Promise.resolve()
        .then(loader)
        .then((value) => {
          if (startedIn === generation) set(key, value)
          return value
        })
        .finally(() => {
          if (loading.get(key) === load) loading.delete(key)
        })
      loading.set(key, load)
      return load
    },
    clear() {
      generation += 1
      entries.clear()
      loading.clear()
    },
    get size() {
      return entries.size
    },
  }
}
