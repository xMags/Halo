import { describe, expect, it } from 'vitest'
import { assertSafeProxyTarget, ProxyTargetError } from '../src/proxyGuard'

describe('assertSafeProxyTarget', () => {
  const rejects = (url: string) => expect(assertSafeProxyTarget(url)).rejects.toBeInstanceOf(ProxyTargetError)

  it('rejects non-http schemes', async () => {
    await rejects('file:///etc/passwd')
    await rejects('ftp://example.com/x')
    await rejects('not a url')
  })

  it('rejects loopback and private IPv4 literals', async () => {
    await rejects('http://127.0.0.1/x')
    await rejects('http://127.1.2.3:8787/x')
    await rejects('http://10.0.0.5/x')
    await rejects('http://172.16.0.1/x')
    await rejects('http://172.31.255.255/x')
    await rejects('http://192.168.1.1/x')
    await rejects('http://169.254.169.254/latest/meta-data')
    await rejects('http://0.0.0.0/x')
    await rejects('http://100.64.0.1/x')
  })

  it('rejects IPv6 loopback, unique-local, link-local and v4-mapped literals', async () => {
    await rejects('http://[::1]/x')
    await rejects('http://[fc00::1]/x')
    await rejects('http://[fd12:3456::1]/x')
    await rejects('http://[fe80::1]/x')
    await rejects('http://[::ffff:127.0.0.1]/x')
    await rejects('http://[::ffff:192.168.0.1]/x')
  })

  it('accepts public IP literals without DNS', async () => {
    const url = await assertSafeProxyTarget('https://1.1.1.1/file.srt')
    expect(url.hostname).toBe('1.1.1.1')
  })

  it('rejects hostnames resolving to loopback', async () => {
    // localhost resolves to 127.0.0.1/::1 on any sane system.
    await rejects('http://localhost:8787/x')
  })

  it('allows adjacent public boundaries of blocked ranges', async () => {
    await expect(assertSafeProxyTarget('http://172.32.0.1/x')).resolves.toBeInstanceOf(URL)
    await expect(assertSafeProxyTarget('http://11.0.0.1/x')).resolves.toBeInstanceOf(URL)
    await expect(assertSafeProxyTarget('http://192.169.0.1/x')).resolves.toBeInstanceOf(URL)
  })
})
