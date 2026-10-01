import assert from 'node:assert/strict'
import test from 'node:test'
import { HaloApiError, HaloClient } from '@halo/core'

const ME = { id: 'u1', username: 'kenneth', isAdmin: false, createdAt: 1 }

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/**
 * A client whose server answers each request from its bearer token, with a
 * `refresh` that hands out the next token (null = the server rejected it).
 */
function clientWith(
  respond: (bearer: string | null) => Response,
  refresh: () => Promise<string | null> = async () => 'access-2',
) {
  let token = 'access-1'
  const calls = { refresh: 0, unauthorized: 0, bearers: [] as (string | null)[] }
  const client = new HaloClient({
    baseUrl: 'https://halo.example',
    fetch: async (_url, init) => {
      const bearer = (init?.headers as Record<string, string> | undefined)?.Authorization ?? null
      calls.bearers.push(bearer)
      return respond(bearer)
    },
    getAccessToken: async () => token,
    refreshAccessToken: async () => {
      calls.refresh += 1
      const next = await refresh()
      if (next) token = next
      return next
    },
    onUnauthorized: () => {
      calls.unauthorized += 1
    },
  })
  return { client, calls }
}

const isUnauthorized = (err: unknown) => err instanceof HaloApiError && err.status === 401

test('a 401 is refreshed once and retried with the new token', async () => {
  const { client, calls } = clientWith((bearer) =>
    bearer === 'Bearer access-2' ? json(200, ME) : json(401, { error: 'expired' }),
  )

  assert.deepEqual(await client.getMe(), ME)
  assert.deepEqual(calls.bearers, ['Bearer access-1', 'Bearer access-2'])
  assert.equal(calls.refresh, 1)
  assert.equal(calls.unauthorized, 0)
})

test('a rejected refresh ends the session', async () => {
  const { client, calls } = clientWith(
    () => json(401, { error: 'nope' }),
    async () => null,
  )

  await assert.rejects(client.getMe(), isUnauthorized)
  assert.equal(calls.unauthorized, 1)
  assert.equal(calls.bearers.length, 1)
})

test('a 401 after a successful refresh keeps the session', async () => {
  // The refresh proved the session alive; the server just refused the new
  // token (e.g. right after a signing-key rotation). Signing out here would
  // also pause every active download.
  const { client, calls } = clientWith(() => json(401, { error: 'unknown signing key' }))

  await assert.rejects(client.getMe(), isUnauthorized)
  assert.equal(calls.unauthorized, 0)
  assert.equal(calls.refresh, 1)
  assert.equal(calls.bearers.length, 2)
})

test('a later request succeeds once the server accepts the new token', async () => {
  let serverAcceptsNewToken = false
  const { client, calls } = clientWith((bearer) =>
    bearer === 'Bearer access-2' && serverAcceptsNewToken ? json(200, ME) : json(401, {}),
  )

  await assert.rejects(client.getMe(), isUnauthorized)
  serverAcceptsNewToken = true

  assert.deepEqual(await client.getMe(), ME)
  assert.equal(calls.unauthorized, 0)
})

test('a network failure during refresh propagates without signing out', async () => {
  const { client, calls } = clientWith(
    () => json(401, {}),
    async () => {
      throw new TypeError('network down')
    },
  )

  await assert.rejects(client.getMe(), TypeError)
  assert.equal(calls.unauthorized, 0)
})
