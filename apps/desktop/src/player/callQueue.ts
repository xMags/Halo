/**
 * A queue that sends calls one at a time, in the order they are made: each
 * call starts only once the one before it has settled. A failed call does not
 * hold up the ones behind it.
 */
export function createCallQueue(): <T>(call: () => Promise<T>) => Promise<T> {
  let tail: Promise<unknown> = Promise.resolve()
  return <T>(call: () => Promise<T>): Promise<T> => {
    const result = tail.then(call)
    tail = result.catch(() => undefined)
    return result
  }
}
