import { lookup } from 'node:dns/promises'
import { createServer, type IncomingMessage, type Server } from 'node:http'
import { connect, isIP, type Socket } from 'node:net'
import type { Duplex } from 'node:stream'
import { isHostname } from './env'
import { isBlockedIp } from './proxyGuard'

/**
 * The other half of the API's addon egress route (`AddonEgress` in
 * safeFetch): a CONNECT-only forward proxy that runs on a machine whose
 * address an addon accepts, reachable from the API over a private network
 * (Tailscale). It tunnels TLS and never sees inside it, so the addon URLs and
 * the API keys in their paths stay unreadable here.
 *
 * Because it sits on someone's network with a residential address, it is as
 * narrow as possible: tunnels only from the listed peers, only to the listed
 * hostnames, only to one port, never to an address the SSRF blocklist covers,
 * and never anything but CONNECT. It resolves each hostname itself and dials
 * the address it checked, so there is no window for DNS rebinding.
 */

export interface EgressProxyOptions {
  /** Peers allowed to open tunnels, by IP address (the API server's private address). */
  allowedClients: ReadonlySet<string>
  /** Lowercase hostnames tunnels may reach. */
  allowedHosts: ReadonlySet<string>
  /** The one destination port tunnels may reach. */
  targetPort?: number
  /** Tunnels open at once; further CONNECTs get 503. */
  maxTunnels?: number
  connectTimeoutMs?: number
  /** A tunnel with no traffic either way for this long is closed. */
  idleTimeoutMs?: number
  /** Resolves a hostname to its addresses. Injectable for tests. */
  resolve?: (host: string) => Promise<string[]>
  /** Which resolved addresses are off limits. Injectable for tests that tunnel to loopback. */
  isBlocked?: (ip: string) => boolean
  log?: (line: string) => void
}

const STATUS_TEXT: Record<number, string> = {
  403: 'Forbidden',
  405: 'Method Not Allowed',
  502: 'Bad Gateway',
  503: 'Service Unavailable',
  504: 'Gateway Timeout',
}

async function resolveAll(host: string): Promise<string[]> {
  return (await lookup(host, { all: true })).map((entry) => entry.address)
}

/** `host:port` from a CONNECT request line; hostnames only, never IP literals. */
function parseAuthority(authority: string): { host: string; port: number } | null {
  const match = /^([a-z0-9.-]+):(\d{1,5})$/i.exec(authority)
  if (!match || isIP(match[1]!) !== 0) return null
  return { host: match[1]!.toLowerCase(), port: Number(match[2]) }
}

/** Node reports IPv4 peers on a dual-stack socket as ::ffff:a.b.c.d. */
function peerAddress(socket: Socket): string {
  const address = socket.remoteAddress ?? ''
  return address.startsWith('::ffff:') && isIP(address.slice(7)) === 4 ? address.slice(7) : address
}

export function createEgressProxy(options: EgressProxyOptions): Server {
  const targetPort = options.targetPort ?? 443
  const maxTunnels = options.maxTunnels ?? 64
  const connectTimeoutMs = options.connectTimeoutMs ?? 10_000
  const idleTimeoutMs = options.idleTimeoutMs ?? 120_000
  const resolve = options.resolve ?? resolveAll
  const isBlocked = options.isBlocked ?? isBlockedIp
  const log = options.log ?? (() => undefined)
  let openTunnels = 0

  // Plain forward proxying would put whole URLs, keys included, through here.
  const server = createServer((_req, res) => {
    res.writeHead(405, { Connection: 'close' }).end()
  })
  server.on('clientError', (_err, socket) => socket.destroy())
  server.on('connect', (req: IncomingMessage, client: Duplex, head: Buffer) => {
    // Node hands CONNECT the raw client socket; typed as a Duplex only.
    void openTunnel(req, client as Socket, head)
  })

  async function openTunnel(req: IncomingMessage, client: Socket, head: Buffer): Promise<void> {
    const started = Date.now()
    const peer = peerAddress(req.socket)
    const target = parseAuthority(req.url ?? '')
    const label = `${peer} ${target ? target.host : '-'}`
    client.on('error', () => client.destroy())

    const refuse = (status: number, reason: string) => {
      if (!client.destroyed) {
        client.end(`HTTP/1.1 ${status} ${STATUS_TEXT[status]}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`)
      }
      log(`${label} ${status} ${reason}`)
    }

    if (!options.allowedClients.has(peer)) return refuse(403, 'client not allowed')
    if (!target || target.port !== targetPort) return refuse(403, 'target not allowed')
    if (!options.allowedHosts.has(target.host)) return refuse(403, 'host not allowed')
    if (openTunnels >= maxTunnels) return refuse(503, 'too many tunnels')

    openTunnels++
    let released = false
    const release = () => {
      if (released) return
      released = true
      openTunnels--
    }

    let addresses: string[]
    try {
      addresses = await resolve(target.host)
    } catch {
      release()
      return refuse(502, 'host does not resolve')
    }
    if (addresses.length === 0 || addresses.some((address) => isBlocked(address))) {
      release()
      return refuse(403, 'host resolves to a non-public address')
    }
    if (client.destroyed) {
      release()
      return
    }

    const upstream = connect({ host: addresses[0]!, port: target.port })
    let phase: 'connecting' | 'open' | 'failed' = 'connecting'
    const fail = (status: number, reason: string) => {
      if (phase !== 'connecting') return
      phase = 'failed'
      upstream.destroy()
      release()
      refuse(status, reason)
    }
    upstream.setTimeout(connectTimeoutMs, () => fail(504, 'connect timed out'))
    upstream.on('error', () => {
      if (phase === 'connecting') fail(502, 'connect failed')
      else upstream.destroy()
    })

    upstream.once('connect', () => {
      if (phase !== 'connecting') return
      phase = 'open'
      upstream.setTimeout(idleTimeoutMs, () => upstream.destroy())
      client.setTimeout(idleTimeoutMs, () => client.destroy())
      client.write('HTTP/1.1 200 Connection Established\r\n\r\n')
      if (head.length > 0) upstream.write(head)
      upstream.pipe(client)
      client.pipe(upstream)

      let closed = false
      const close = () => {
        if (closed) return
        closed = true
        upstream.destroy()
        client.destroy()
        release()
        log(`${label} 200 up ${upstream.bytesWritten}B down ${upstream.bytesRead}B ${Date.now() - started}ms`)
      }
      upstream.once('close', close)
      client.once('close', close)
    })
  }

  return server
}

export interface EgressProxyEnv {
  host: string
  port: number
  allowedClients: Set<string>
  allowedHosts: Set<string>
}

function list(name: string): string[] {
  return (process.env[name] ?? '')
    .split(',')
    .map((item) => item.trim().toLowerCase())
    .filter(Boolean)
}

/** Fail fast: an egress proxy with a missing or malformed allowlist must not start. */
export function loadEgressProxyEnv(): EgressProxyEnv {
  const clients = list('EGRESS_ALLOWED_CLIENTS')
  const hosts = list('EGRESS_ALLOWED_HOSTS')
  if (clients.length === 0 || hosts.length === 0) {
    throw new Error('EGRESS_ALLOWED_CLIENTS and EGRESS_ALLOWED_HOSTS are both required')
  }
  const badClient = clients.find((client) => isIP(client) === 0)
  if (badClient) throw new Error(`EGRESS_ALLOWED_CLIENTS must list IP addresses (got "${badClient}")`)
  const badHost = hosts.find((host) => !isHostname(host))
  if (badHost) throw new Error(`EGRESS_ALLOWED_HOSTS must list hostnames (got "${badHost}")`)

  const port = Number(process.env.EGRESS_PORT ?? 8790)
  if (!Number.isInteger(port) || port < 1 || port > 65_535) throw new Error('EGRESS_PORT must be a TCP port')
  return {
    host: process.env.EGRESS_HOST ?? '0.0.0.0',
    port,
    allowedClients: new Set(clients),
    allowedHosts: new Set(hosts),
  }
}
