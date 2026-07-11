import { describe, expect, it } from 'vitest'
import { ProxyTargetError } from '../src/proxyGuard'
import { pinnedLookup, safeFetch } from '../src/safeFetch'

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
