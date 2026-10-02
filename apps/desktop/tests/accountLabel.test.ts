import assert from 'node:assert/strict'
import test from 'node:test'
import { accountLabel } from '../src/auth/accountLabel'

test("a server's account tier replaces the account caption", () => {
  assert.equal(accountLabel({ isAdmin: true, plan: 'premium' }), 'PREMIUM')
  assert.equal(accountLabel({ isAdmin: false, plan: 'free' }), 'FREE')
})

test('without a tier the caption names the account and its admin role', () => {
  assert.equal(accountLabel({ isAdmin: true }), 'ADMIN · HALO ACCOUNT')
  assert.equal(accountLabel({ isAdmin: false }), 'HALO ACCOUNT')
  assert.equal(accountLabel(undefined), 'HALO ACCOUNT')
})
