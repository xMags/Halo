import assert from 'node:assert/strict'
import test from 'node:test'
import {
  BrowserSignInError,
  callbackFailure,
  classifySignInFailure,
  localSignInMessage,
  loopbackWaitFailure,
} from '../src/auth/signInFailure'
import { parseServerUrl } from '../src/serverUrl'

// ---------------------------------------------------------------------------
// The built-in server address
// ---------------------------------------------------------------------------

test('a build signs in to the https server it was given, without a trailing slash', () => {
  assert.equal(parseServerUrl('https://halo.example.com/'), 'https://halo.example.com')
  assert.equal(parseServerUrl('  https://halo.example.com/api/ '), 'https://halo.example.com/api')
})

test('plain http is accepted only for a server on this machine', () => {
  assert.equal(parseServerUrl('http://127.0.0.1:18790'), 'http://127.0.0.1:18790')
  assert.equal(parseServerUrl('http://localhost:18790'), 'http://localhost:18790')
  assert.throws(() => parseServerUrl('http://halo.example.com'), /must use https/)
})

test('a build without a usable server address is refused', () => {
  assert.throws(() => parseServerUrl(undefined), /VITE_HALO_SERVER_URL is not set/)
  assert.throws(() => parseServerUrl('   '), /VITE_HALO_SERVER_URL is not set/)
  assert.throws(() => parseServerUrl('halo.example.com'), /not a valid URL/)
  assert.throws(() => parseServerUrl('https://user:pw@halo.example.com'), /credentials/)
  assert.throws(() => parseServerUrl('https://halo.example.com/?x=1'), /query/)
})

// ---------------------------------------------------------------------------
// Failed sign-ins, as the sign-in screen tells them apart
// ---------------------------------------------------------------------------

test("the loopback listener's cancel and timeout become cancelled and expired", () => {
  const cancelled = loopbackWaitFailure('sign-in cancelled')
  assert.ok(cancelled instanceof BrowserSignInError)
  assert.deepEqual(classifySignInFailure(cancelled), { kind: 'cancelled' })

  const timedOut = loopbackWaitFailure('sign-in timed out waiting for the browser redirect')
  assert.deepEqual(classifySignInFailure(timedOut), { kind: 'expired' })
})

test('a listener that could not start is a real failure that keeps its message', () => {
  const bind = loopbackWaitFailure('cannot bind 127.0.0.1:17871: in use (is another sign-in pending?)')
  assert.ok(!(bind instanceof BrowserSignInError))
  assert.deepEqual(classifySignInFailure(bind), {
    kind: 'unreachable',
    detail: 'cannot bind 127.0.0.1:17871: in use (is another sign-in pending?)',
  })
})

test('only access_denied in the callback counts as declined', () => {
  const declined = callbackFailure({ error: 'access_denied', state: 's' })
  assert.ok(declined)
  assert.deepEqual(classifySignInFailure(declined), { kind: 'declined' })
  assert.equal(callbackFailure({ error: 'server_error' }), null)
  assert.equal(callbackFailure({ code: 'c', state: 's' }), null)
})

test('a code redeemed too late reads as an expired request', () => {
  // OIDC's token endpoint error carries the OAuth code.
  const oidc = Object.assign(new Error('Code is expired'), { name: 'TokenEndpointError', code: 'invalid_grant' })
  assert.deepEqual(classifySignInFailure(oidc), { kind: 'expired' })
})

test("a provider's refusal keeps its reason instead of reading as can't connect", () => {
  // OIDC's library refusal is recognised by name.
  const oidc = Object.assign(new Error('invalid_client'), { name: 'TokenEndpointError' })
  assert.deepEqual(classifySignInFailure(oidc), { kind: 'refused', message: 'invalid_client' })
})

test("anything else is can't connect, with the error as the technical detail", () => {
  assert.deepEqual(classifySignInFailure(new TypeError('error sending request')), {
    kind: 'unreachable',
    detail: 'error sending request',
  })
  assert.deepEqual(classifySignInFailure('plugin said no'), { kind: 'unreachable', detail: 'plugin said no' })
})

test("the local form's errors use the native app's words", () => {
  assert.equal(localSignInMessage({ status: 401 }), 'The username or password is incorrect.')
  assert.equal(localSignInMessage({ status: 429 }), 'Too many attempts. Try again later.')
  assert.equal(localSignInMessage(new TypeError('offline')), "Can't connect to Halo. Try again.")
})
