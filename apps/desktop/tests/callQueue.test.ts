import assert from 'node:assert/strict'
import test from 'node:test'
import { createCallQueue } from '../src/callQueue.ts'

const later = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

test('calls run one at a time in the order they were made', async () => {
  const inOrder = createCallQueue()
  const log: string[] = []
  const call = (name: string, ms: number) =>
    inOrder(async () => {
      log.push(`${name} start`)
      await later(ms)
      log.push(`${name} end`)
      return name
    })

  // The first is the slowest: without the queue the others would finish first.
  const results = await Promise.all([call('a', 30), call('b', 5), call('c', 1)])

  assert.deepEqual(results, ['a', 'b', 'c'])
  assert.deepEqual(log, ['a start', 'a end', 'b start', 'b end', 'c start', 'c end'])
})

test('a failed call reports its error and does not hold up the next', async () => {
  const inOrder = createCallQueue()
  const failed = inOrder(async () => {
    throw new Error('no file loaded')
  })
  const next = inOrder(async () => 'played')

  await assert.rejects(failed, /no file loaded/)
  assert.equal(await next, 'played')
})
