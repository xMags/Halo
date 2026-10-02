import { describe, expect, it } from 'vitest'
import { z } from 'zod'
import { parseManifest } from '@halo/core'

// The zod schema the server validated manifests with before validation moved
// into core (so the on-device backend applies the same rule). Kept here as the
// reference the hand-written validator must agree with.
const referenceSchema = z
  .object({
    id: z.string().min(1),
    version: z.string(),
    name: z.string().min(1),
    resources: z.array(z.union([z.string(), z.object({ name: z.string() }).passthrough()])),
    types: z.array(z.string()),
    catalogs: z.array(z.object({ type: z.string(), id: z.string() }).passthrough()),
  })
  .passthrough()

const valid = {
  id: 'org.example',
  version: '1.0.0',
  name: 'Example',
  resources: ['catalog', { name: 'stream', types: ['movie'], idPrefixes: ['tt'] }],
  types: ['movie', 'series'],
  catalogs: [{ type: 'movie', id: 'top', name: 'Top', extra: [{ name: 'search' }] }],
  logo: 'https://example.com/logo.png',
}

const cases: Array<[string, unknown]> = [
  ['a complete manifest', valid],
  ['empty resources, types and catalogs', { ...valid, resources: [], types: [], catalogs: [] }],
  ['an empty version string', { ...valid, version: '' }],
  ['a missing id', { ...valid, id: undefined }],
  ['an empty id', { ...valid, id: '' }],
  ['a numeric id', { ...valid, id: 7 }],
  ['an empty name', { ...valid, name: '' }],
  ['a missing version', { ...valid, version: undefined }],
  ['a numeric version', { ...valid, version: 1 }],
  ['types that are not strings', { ...valid, types: ['movie', 3] }],
  ['types that are not an array', { ...valid, types: 'movie' }],
  ['a resource object without a name', { ...valid, resources: [{ types: ['movie'] }] }],
  ['a resource object with a numeric name', { ...valid, resources: [{ name: 1 }] }],
  ['a resource that is a number', { ...valid, resources: [1] }],
  ['a resource that is null', { ...valid, resources: [null] }],
  ['a resource that is an array', { ...valid, resources: [['stream']] }],
  ['a catalog without an id', { ...valid, catalogs: [{ type: 'movie' }] }],
  ['a catalog without a type', { ...valid, catalogs: [{ id: 'top' }] }],
  ['a catalog that is a string', { ...valid, catalogs: ['top'] }],
  ['catalogs that are not an array', { ...valid, catalogs: {} }],
  ['an array instead of an object', [valid]],
  ['null', null],
  ['a string', 'manifest'],
]

describe('parseManifest', () => {
  it.each(cases)('agrees with the reference schema on %s', (_label, input) => {
    // JSON round trip: manifests arrive as parsed JSON, where undefined keys do not exist.
    const json = input === undefined ? input : JSON.parse(JSON.stringify(input))
    expect(parseManifest(json) !== null).toBe(referenceSchema.safeParse(json).success)
  })

  it('keeps fields it does not check', () => {
    expect(parseManifest(JSON.parse(JSON.stringify(valid)))).toMatchObject({ logo: valid.logo, catalogs: valid.catalogs })
  })
})
