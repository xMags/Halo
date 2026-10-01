import { afterEach, describe, expect, it, vi } from 'vitest'
import { loadEnv } from '../src/env'
import { ProxyTargetError } from '../src/proxyGuard'
import { createSafeFetch, pinnedLookup, routesThroughEgress, safeFetch, type AddonEgress } from '../src/safeFetch'

describe('safeFetch pre-check', () => {
  it('rejects private, reserved and non-http targets before any network', async () => {
    await expect(safeFetch('http://127.0.0.1/x')).rejects.toBeInstanceOf(ProxyTargetError)
    await expect(safeFetch('http://[::1]/x')).rejects.toBeInstanceOf(ProxyTargetError)
    await expect(safeFetch('http://169.254.169.254/latest/meta-data')).rejects.toBeInstanceOf(ProxyTargetError)
    await expect(safeFetch('http://10.1.2.3/x')).rejects.toBeInstanceOf(ProxyTargetError)
    await expect(safeFetch('ftp://example.com/x')).rejects.toBeInstanceOf(ProxyTargetError)
  })
})

describe('pinnedLookup connect-time guard', () => {
  // dns.lookup on an IP literal resolves to itself without network, so this
  // exercises the blocklist decision the hook enforces at connect time.
  const run = (host: string) =>
    new Promise<unknown>((resolve) => pinnedLookup(host, { all: true }, (err: unknown) => resolve(err)))

  it('fails the connection when the resolved address is blocked', async () => {
    expect(await run('127.0.0.1')).toBeInstanceOf(Error)
    expect(await run('10.0.0.1')).toBeInstanceOf(Error)
  })

  it('allows a public resolved address', async () => {
    expect(await run('1.1.1.1')).toBeNull()
  })
})

describe('addon egress routing', () => {
  const egress: AddonEgress = { proxyUrl: 'http://100.64.0.2:8790', hosts: new Set(['torrentio.strem.fun']) }
  const route = (url: string) => routesThroughEgress(new URL(url), egress)

  it('sends only the listed hosts through the proxy', () => {
    expect(route('https://torrentio.strem.fun/key/stream/movie/tt1.json')).toBe(true)
    expect(route('https://TORRENTIO.strem.fun/manifest.json')).toBe(true)
    expect(route('https://v3-cinemeta.strem.io/manifest.json')).toBe(false)
    expect(route('https://strem.fun/manifest.json')).toBe(false)
  })

  it('dials everything directly without an egress setting', () => {
    expect(routesThroughEgress(new URL('https://torrentio.strem.fun/manifest.json'), undefined)).toBe(false)
  })

  it('refuses a listed host over plain http or another port, which would expose the URL', () => {
    expect(() => route('http://torrentio.strem.fun/key/manifest.json')).toThrow(ProxyTargetError)
    expect(() => route('https://torrentio.strem.fun:8443/manifest.json')).toThrow(ProxyTargetError)
    expect(route('https://torrentio.strem.fun:443/manifest.json')).toBe(true)
  })

  it('keeps the private-address guard on direct hops when egress is set', async () => {
    const routed = createSafeFetch(egress)
    await expect(routed('http://127.0.0.1/x')).rejects.toBeInstanceOf(ProxyTargetError)
    await expect(routed('http://169.254.169.254/latest/meta-data')).rejects.toBeInstanceOf(ProxyTargetError)
  })
})

describe('addon egress env', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
  })

  function envWith(proxy: string | undefined, hosts: string | undefined) {
    vi.stubEnv('AUTH_MODE', 'local')
    vi.stubEnv('JWT_SECRET', 'x'.repeat(32))
    vi.stubEnv('ADMIN_PASSWORD', 'fixture-pass')
    vi.stubEnv('ADDON_EGRESS_PROXY', proxy)
    vi.stubEnv('ADDON_EGRESS_HOSTS', hosts)
    return () => loadEnv().addonEgress
  }

  it('is off unless configured', () => {
    expect(envWith(undefined, undefined)()).toBeUndefined()
  })

  it('reads the proxy origin and lowercased hosts', () => {
    expect(envWith('http://100.64.0.2:8790', 'Torrentio.Strem.Fun, other.example ')()).toEqual({
      proxyUrl: 'http://100.64.0.2:8790',
      hosts: new Set(['torrentio.strem.fun', 'other.example']),
    })
  })

  it('fails fast on half a setting or a malformed one', () => {
    expect(envWith('http://100.64.0.2:8790', undefined)).toThrow(/set both or neither/)
    expect(envWith(undefined, 'torrentio.strem.fun')).toThrow(/set both or neither/)
    expect(envWith('not a url', 'torrentio.strem.fun')).toThrow(/not a valid URL/)
    expect(envWith('https://100.64.0.2:8790', 'torrentio.strem.fun')).toThrow(/http:\/\/host:port/)
    expect(envWith('http://100.64.0.2', 'torrentio.strem.fun')).toThrow(/http:\/\/host:port/)
    expect(envWith('http://user:pw@100.64.0.2:8790', 'torrentio.strem.fun')).toThrow(/no credentials/)
    expect(envWith('http://100.64.0.2:8790', 'https://torrentio.strem.fun')).toThrow(/hostnames only/)
    expect(envWith('http://100.64.0.2:8790', '104.21.0.1')).toThrow(/hostnames only/)
  })
})
