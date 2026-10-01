import type { Server } from 'node:http'
import { connect, createServer, type AddressInfo, type Server as NetServer, type Socket } from 'node:net'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createEgressProxy, loadEgressProxyEnv, type EgressProxyOptions } from '../src/egressProxy'

// A local TCP echo server stands in for the addon: tunnels are opaque bytes,
// so echo proves both directions without TLS.
let echo: NetServer | undefined
let echoPort = 0
let echoConnections = 0
let proxy: Server | undefined
let proxyPort = 0
const open: Socket[] = []

async function startEcho(): Promise<void> {
  echoConnections = 0
  echo = createServer((socket) => {
    echoConnections++
    socket.on('error', () => undefined)
    socket.pipe(socket)
  })
  await new Promise<void>((resolve) => echo!.listen(0, '127.0.0.1', resolve))
  echoPort = (echo.address() as AddressInfo).port
}

async function startProxy(overrides: Partial<EgressProxyOptions> = {}): Promise<void> {
  await startEcho()
  proxy = createEgressProxy({
    allowedClients: new Set(['127.0.0.1']),
    allowedHosts: new Set(['addon.example']),
    targetPort: echoPort,
    resolve: async () => ['127.0.0.1'],
    // The echo server is on loopback, which the real blocklist refuses.
    isBlocked: () => false,
    ...overrides,
  })
  await new Promise<void>((resolve) => proxy!.listen(0, '127.0.0.1', resolve))
  proxyPort = (proxy.address() as AddressInfo).port
}

/** Sends a raw request line to the proxy and resolves with the response status. */
function request(firstLine: string): Promise<{ status: number; socket: Socket }> {
  return new Promise((resolve, reject) => {
    const socket = connect(proxyPort, '127.0.0.1')
    open.push(socket)
    let buffered = ''
    socket.once('error', reject)
    const onData = (chunk: Buffer) => {
      buffered += chunk.toString('latin1')
      if (!buffered.includes('\r\n\r\n')) return
      socket.off('data', onData)
      resolve({ status: Number(buffered.split(' ')[1]), socket })
    }
    socket.on('data', onData)
    socket.write(`${firstLine}\r\nHost: addon.example\r\n\r\n`)
  })
}

const tunnel = (authority: string) => request(`CONNECT ${authority} HTTP/1.1`)

function roundTrip(socket: Socket, text: string): Promise<string> {
  return new Promise((resolve) => {
    socket.once('data', (chunk: Buffer) => resolve(chunk.toString()))
    socket.write(text)
  })
}

afterEach(async () => {
  for (const socket of open.splice(0)) socket.destroy()
  await new Promise<void>((resolve) => (proxy ? proxy.close(() => resolve()) : resolve()))
  await new Promise<void>((resolve) => (echo ? echo.close(() => resolve()) : resolve()))
  proxy = undefined
  echo = undefined
})

describe('egress proxy', () => {
  it('tunnels both directions for an allowed client and host', async () => {
    await startProxy()
    const { status, socket } = await tunnel(`addon.example:${echoPort}`)
    expect(status).toBe(200)
    expect(await roundTrip(socket, 'ping')).toBe('ping')
  })

  it('matches the hostname case-insensitively', async () => {
    await startProxy()
    expect((await tunnel(`Addon.Example:${echoPort}`)).status).toBe(200)
  })

  it('refuses a client that is not on the list, without dialing out', async () => {
    await startProxy({ allowedClients: new Set(['100.64.0.1']) })
    expect((await tunnel(`addon.example:${echoPort}`)).status).toBe(403)
    expect(echoConnections).toBe(0)
  })

  it('refuses a host that is not on the list', async () => {
    await startProxy()
    expect((await tunnel(`elsewhere.example:${echoPort}`)).status).toBe(403)
    expect(echoConnections).toBe(0)
  })

  it('refuses any other port', async () => {
    await startProxy()
    expect((await tunnel(`addon.example:${echoPort + 1}`)).status).toBe(403)
  })

  it('refuses IP literals, even ones a host would resolve to', async () => {
    await startProxy()
    expect((await tunnel(`127.0.0.1:${echoPort}`)).status).toBe(403)
  })

  it('refuses a listed host that resolves to a private address', async () => {
    await startProxy({ resolve: async () => ['10.0.0.1'], isBlocked: undefined })
    expect((await tunnel(`addon.example:${echoPort}`)).status).toBe(403)
    expect(echoConnections).toBe(0)
  })

  it('answers 502 when the host does not resolve', async () => {
    await startProxy({
      resolve: async () => {
        throw new Error('ENOTFOUND')
      },
    })
    expect((await tunnel(`addon.example:${echoPort}`)).status).toBe(502)
  })

  it('refuses plain forward proxying, which would expose whole URLs', async () => {
    await startProxy()
    expect((await request('GET http://addon.example/secret-key/manifest.json HTTP/1.1')).status).toBe(405)
    expect(echoConnections).toBe(0)
  })

  it('caps open tunnels and frees a slot when one closes', async () => {
    await startProxy({ maxTunnels: 1 })
    const first = await tunnel(`addon.example:${echoPort}`)
    expect(first.status).toBe(200)
    expect((await tunnel(`addon.example:${echoPort}`)).status).toBe(503)

    first.socket.destroy()
    await vi.waitFor(async () => {
      expect((await tunnel(`addon.example:${echoPort}`)).status).toBe(200)
    })
  })
})

describe('egress proxy env', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
  })

  function envWith(clients: string | undefined, hosts: string | undefined) {
    vi.stubEnv('EGRESS_ALLOWED_CLIENTS', clients)
    vi.stubEnv('EGRESS_ALLOWED_HOSTS', hosts)
    return () => loadEgressProxyEnv()
  }

  it('reads both allowlists, lowercased', () => {
    const env = envWith('100.64.0.1', 'Torrentio.Strem.Fun, other.example')()
    expect(env.allowedClients).toEqual(new Set(['100.64.0.1']))
    expect(env.allowedHosts).toEqual(new Set(['torrentio.strem.fun', 'other.example']))
    expect(env.port).toBe(8790)
  })

  it('will not start with either allowlist missing or malformed', () => {
    expect(envWith(undefined, 'torrentio.strem.fun')).toThrow(/both required/)
    expect(envWith('100.64.0.1', undefined)).toThrow(/both required/)
    expect(envWith('aws-vm', 'torrentio.strem.fun')).toThrow(/IP addresses/)
    expect(envWith('100.64.0.1', 'https://torrentio.strem.fun')).toThrow(/hostnames/)
    expect(envWith('100.64.0.1', '104.21.0.1')).toThrow(/hostnames/)
  })
})
