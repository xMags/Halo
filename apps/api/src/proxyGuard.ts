import { lookup } from 'node:dns/promises'
import { isIP } from 'node:net'

/**
 * SSRF guard for /addon-proxy. The proxy exists so clients can fetch subtitle
 * files and addon responses from hosts without CORS, so it must accept
 * arbitrary public origins — the guard is therefore about what it must NOT
 * reach: anything inside the network the API runs on.
 */

const BLOCKED_V4 = [
  ['0.0.0.0', 8],
  ['10.0.0.0', 8],
  ['100.64.0.0', 10],
  ['127.0.0.0', 8],
  ['169.254.0.0', 16],
  ['172.16.0.0', 12],
  ['192.168.0.0', 16],
] as const

function v4ToInt(ip: string): number {
  return ip.split('.').reduce((acc, octet) => (acc << 8) | Number(octet), 0) >>> 0
}

function isBlockedV4(ip: string): boolean {
  const addr = v4ToInt(ip)
  return BLOCKED_V4.some(([net, bits]) => {
    const mask = (~0 << (32 - bits)) >>> 0
    return (addr & mask) === (v4ToInt(net) & mask)
  })
}

function isBlockedV6(ip: string): boolean {
  const lower = ip.toLowerCase()
  if (lower === '::1' || lower === '::') return true
  // Unique-local fc00::/7 and link-local fe80::/10.
  if (/^f[cd]/.test(lower) || lower.startsWith('fe8') || lower.startsWith('fe9') || lower.startsWith('fea') || lower.startsWith('feb')) {
    return true
  }
  // IPv4-mapped — defer to the v4 rules. URL parsers normalize the dotted
  // form (::ffff:127.0.0.1) to hex groups (::ffff:7f00:1), so handle both.
  const mapped = lower.match(/^::ffff:(.+)$/)
  if (mapped) {
    const rest = mapped[1]!
    if (isIP(rest) === 4) return isBlockedV4(rest)
    const groups = rest.split(':')
    if (groups.length === 2 && groups.every((g) => /^[0-9a-f]{1,4}$/.test(g))) {
      const hi = parseInt(groups[0]!, 16)
      const lo = parseInt(groups[1]!, 16)
      return isBlockedV4(`${hi >> 8}.${hi & 0xff}.${lo >> 8}.${lo & 0xff}`)
    }
  }
  return false
}

function isBlockedIp(ip: string): boolean {
  return isIP(ip) === 4 ? isBlockedV4(ip) : isBlockedV6(ip)
}

/** Throws unless `raw` is an http(s) URL whose host resolves only to public addresses. */
export async function assertSafeProxyTarget(raw: string): Promise<URL> {
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    throw new ProxyTargetError('invalid URL')
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new ProxyTargetError('only http(s) targets are allowed')
  }

  const host = url.hostname.replace(/^\[|\]$/g, '')
  if (isIP(host)) {
    if (isBlockedIp(host)) throw new ProxyTargetError('target address is not public')
    return url
  }

  let addresses
  try {
    addresses = await lookup(host, { all: true })
  } catch {
    throw new ProxyTargetError('target host does not resolve')
  }
  if (addresses.length === 0 || addresses.some((a) => isBlockedIp(a.address))) {
    throw new ProxyTargetError('target address is not public')
  }
  return url
}

export class ProxyTargetError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ProxyTargetError'
  }
}
