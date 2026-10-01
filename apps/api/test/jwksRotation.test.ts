import { createServer, type Server } from 'node:http'
import type { AddressInfo } from 'node:net'
import { exportJWK, generateKeyPair, SignJWT, type JWK } from 'jose'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp } from '../src/app'
import { createDb } from '../src/db'
import { ADMIN_GROUP, authed, CLIENT_ID, type App } from './helpers'

// These tests run the real remote key-set path (no getKey override) against a
// local stand-in for the IdP's JWKS endpoint, so jose's caching and refetch
// cooldown are exercised exactly as in production.

type PrivateKey = Awaited<ReturnType<typeof generateKeyPair>>['privateKey']

interface SigningKey {
  kid: string
  privateKey: PrivateKey
  jwk: JWK
}

async function signingKey(kid: string): Promise<SigningKey> {
  const { publicKey, privateKey } = await generateKeyPair('RS256')
  return { kid, privateKey, jwk: { ...(await exportJWK(publicKey)), kid, alg: 'RS256', use: 'sig' } }
}

describe('OIDC signing-key rotation', () => {
  let server: Server
  let issuer: string
  let published: JWK[]
  let jwksFetches: number
  let app: App

  beforeEach(async () => {
    // Only Date is faked: jose's cooldown reads Date.now(), while the HTTP
    // round trips below still need real timers.
    vi.useFakeTimers({ toFake: ['Date'] })
    published = []
    jwksFetches = 0
    server = createServer((req, res) => {
      if (!req.url?.endsWith('/jwks/')) {
        res.writeHead(404).end()
        return
      }
      jwksFetches += 1
      res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ keys: published }))
    })
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
    issuer = `http://127.0.0.1:${(server.address() as AddressInfo).port}/application/o/halo/`
    app = createApp({
      db: createDb(':memory:'),
      auth: { mode: 'oidc', issuer, clientId: CLIENT_ID, adminGroupId: ADMIN_GROUP },
      corsOrigins: ['http://localhost:5173'],
      addonFailureLogger: () => {},
    })
  })

  afterEach(async () => {
    vi.useRealTimers()
    await new Promise<void>((resolve) => server.close(() => resolve()))
  })

  function tokenSignedWith(key: SigningKey): Promise<string> {
    return new SignJWT({ preferred_username: 'admin', groups: [ADMIN_GROUP] })
      .setProtectedHeader({ alg: 'RS256', kid: key.kid })
      .setSubject('admin-sub')
      .setIssuer(issuer)
      .setAudience(CLIENT_ID)
      .setIssuedAt()
      .setExpirationTime('1h')
      .sign(key.privateKey)
  }

  async function statusFor(token: string): Promise<number> {
    const res = await app.request('/watch-state', authed(token))
    return res.status
  }

  it('accepts tokens from a rotated key within seconds of the rotation', async () => {
    const oldKey = await signingKey('old')
    const newKey = await signingKey('new')
    published = [oldKey.jwk]
    expect(await statusFor(await tokenSignedWith(oldKey))).toBe(200)

    // The IdP switches keys; the API has just fetched the old set.
    published = [newKey.jwk]
    const fresh = await tokenSignedWith(newKey)
    expect(await statusFor(fresh)).toBe(401)

    // A fixed 6 s, not the constant, so a return to jose's 30 s default fails here.
    vi.setSystemTime(Date.now() + 6_000)
    expect(await statusFor(fresh)).toBe(200)
    expect(jwksFetches).toBe(2)
  })

  it('does not refetch the key set for every unknown key id', async () => {
    const key = await signingKey('real')
    published = [key.jwk]
    expect(await statusFor(await tokenSignedWith(key))).toBe(200)

    // Tokens with made-up key ids must not each trigger a fetch from the IdP.
    for (let i = 0; i < 5; i += 1) {
      const forged = await tokenSignedWith(await signingKey(`forged-${i}`))
      expect(await statusFor(forged)).toBe(401)
    }
    expect(jwksFetches).toBe(1)
  })
})
