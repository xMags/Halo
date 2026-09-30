import assert from 'node:assert/strict'
import test from 'node:test'
import { placeContextMenu } from '../src/components/menuPlacement.ts'

const viewport = { width: 1600, height: 1000 }
const menu = { width: 200, height: 180 }

test('opens down and to the right of the pointer when it fits', () => {
  assert.deepEqual(placeContextMenu({ x: 300, y: 400 }, menu, viewport), { x: 300, y: 400 })
})

test('flips to the left near the right edge and upward near the bottom', () => {
  assert.deepEqual(placeContextMenu({ x: 1500, y: 400 }, menu, viewport), { x: 1300, y: 400 })
  assert.deepEqual(placeContextMenu({ x: 300, y: 900 }, menu, viewport), { x: 300, y: 720 })
  assert.deepEqual(placeContextMenu({ x: 1500, y: 900 }, menu, viewport), { x: 1300, y: 720 })
})

test('keeps an 8px margin from the edge before flipping', () => {
  // 1392 + 200 = 1592, exactly the last allowed pixel.
  assert.deepEqual(placeContextMenu({ x: 1392, y: 400 }, menu, viewport), { x: 1392, y: 400 })
  assert.deepEqual(placeContextMenu({ x: 1393, y: 400 }, menu, viewport), { x: 1193, y: 400 })
})

test('a menu that fits on neither side is pushed back inside the window', () => {
  const short = { width: 1600, height: 240 }
  // 240 - 8 - 180: as low as it can sit and still clear the bottom margin.
  assert.deepEqual(placeContextMenu({ x: 300, y: 100 }, menu, short), { x: 300, y: 52 })
  // Taller than the window: pinned to the top so the first items stay visible.
  assert.deepEqual(placeContextMenu({ x: 300, y: 100 }, { width: 200, height: 400 }, short), { x: 300, y: 8 })
})
