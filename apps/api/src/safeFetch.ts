import { lookup as dnsLookup } from 'node:dns'
import { Agent } from 'undici'
import { assertSafeProxyTarget, isBlockedIp, ProxyTargetError } from './proxyGuard'

const MAX_REDIRECTS = 5

// Untyped shim: undici calls this exactly like node's dns.lookup, but with
// `all: true`, so the second callback arg can be a string or an address array.
const dnsLookupAny = dnsLookup as (
  hostname: string,
  options: unknown,
  callback: (err: NodeJS.ErrnoException | null, address: unknown, family?: number) => void,
) => void

/**
 * dns.lookup wrapper that fails the connection if ANY resolved address is
 * blocked. Because undici runs this at connect time on the address it is about
 * to dial, it closes the DNS-rebinding TOCTOU that a lookup-then-fetch design
 * leaves open (the pre-check's answer can change before the socket opens).
 */
export function pinnedLookup(hostname: string, options: unknown, callback: (...args: unknown[]) => void): void {
  dnsLookupAny(hostname, options, (err, address, family) => {
    if (err) return callback(err, address, family)
    const list = Array.isArray(address)
      ? (address as Array<{ address: string }>)
      : [{ address: address as string }]
    const blocked = list.find((a) => isBlockedIp(a.address))
    if (blocked) return callback(new Error(`blocked address ${blocked.address}`), address, family)
    callback(null, address, family)
  })
}

// One agent, reused across requests, that enforces the blocklist at connect time.
const pinnedAgent = new Agent({ connect: { lookup: pinnedLookup as never } })

/**
 * SSRF-safe fetch used for all server-side addon traffic. Drop-in for global
 * fetch: validates the URL/protocol up front, follows redirects manually so
 * every hop is re-validated (MAX_REDIRECTS cap), and dials through an agent
 * that re-checks the resolved address as the socket connects. Throws
 * ProxyTargetError on any unsafe target or redirect.
 */
export const safeFetch: typeof fetch = async (input, init) => {
  const target = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
  let url = await assertSafeProxyTarget(target)
  for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
    const upstream = await fetch(url, { redirect: 'manual', signal: init?.signal ?? undefined, dispatcher: pinnedAgent })
    if (upstream.status >= 300 && upstream.status < 400) {
      const location = upstream.headers.get('location')
      await upstream.body?.cancel()
      if (!location) throw new ProxyTargetError('redirect without location')
      url = await assertSafeProxyTarget(new URL(location, url).toString())
      continue
    }
    const headers = new Headers()
    for (const name of ['content-type', 'content-length', 'cache-control']) {
      const value = upstream.headers.get(name)
      if (value) headers.set(name, value)
    }
    return new Response(upstream.body, { status: upstream.status, headers })
  }
  throw new ProxyTargetError('too many redirects')
}
