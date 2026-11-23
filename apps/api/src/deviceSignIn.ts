import { createHash, randomBytes, randomInt } from 'node:crypto'

/**
 * Signing in on a TV with a code approved elsewhere (RFC 8628's device
 * authorization grant, for local-mode servers): the TV asks for a code, the
 * viewer approves it with their username and password on a phone, and the TV
 * polls until the session is ready.
 *
 * Pending sign-ins live in memory only. A restart forgets them, which costs
 * the viewer nothing but a new code. Only hashes of both codes are kept, the
 * number waiting is capped, and an approved session is handed out once: the
 * record is removed in the same synchronous step that reads it, so two polls
 * arriving together cannot both receive it.
 */

/** Letters a TV viewer reads off the screen: no vowels (no words), no lookalikes. */
const USER_CODE_ALPHABET = 'BCDFGHJKLMNPQRSTVWXZ'
const USER_CODE_LENGTH = 8

export const DEVICE_CODE_TTL_MS = 10 * 60_000
export const DEVICE_POLL_INTERVAL_SEC = 5
const SLOW_DOWN_STEP_SEC = 5
/** A poll this much early still counts as on time, for clock jitter on the device. */
const POLL_TOLERANCE_MS = 1_000

type State = 'pending' | 'approved' | 'denied'

interface Grant {
  deviceHash: string
  userHash: string
  expiresAt: number
  intervalSec: number
  lastPollAt?: number
  state: State
  userId?: string
}

export type DevicePoll =
  | { status: 'pending' | 'slow_down' | 'denied' | 'expired' | 'unknown' }
  | { status: 'approved'; userId: string }

const hash = (value: string): string => createHash('sha256').update(value).digest('hex')

/** `bcdf-ghjk`, `BCDFGHJK` and ` BCDF GHJK ` all name the same code; null when it cannot be one. */
export function normalizeUserCode(raw: string): string | null {
  const letters = raw.toUpperCase().replace(/[\s-]/g, '')
  if (letters.length !== USER_CODE_LENGTH) return null
  for (const ch of letters) if (!USER_CODE_ALPHABET.includes(ch)) return null
  return letters
}

/** `BCDFGHJK` as shown on the TV: `BCDF-GHJK`. */
export function formatUserCode(code: string): string {
  return `${code.slice(0, 4)}-${code.slice(4)}`
}

export class DeviceSignInStore {
  private readonly byDevice = new Map<string, Grant>()
  private readonly byUser = new Map<string, Grant>()

  constructor(
    private readonly maxPending = 200,
    private readonly now: () => number = Date.now,
  ) {}

  /** A new code pair, or null while the cap is reached. */
  start(): { deviceCode: string; userCode: string } | null {
    this.sweep()
    if (this.byDevice.size >= this.maxPending) return null
    const deviceCode = randomBytes(32).toString('base64url')
    let userCode: string
    do {
      userCode = Array.from({ length: USER_CODE_LENGTH }, () => USER_CODE_ALPHABET[randomInt(USER_CODE_ALPHABET.length)]).join('')
    } while (this.byUser.has(hash(userCode)))
    const grant: Grant = {
      deviceHash: hash(deviceCode),
      userHash: hash(userCode),
      expiresAt: this.now() + DEVICE_CODE_TTL_MS,
      intervalSec: DEVICE_POLL_INTERVAL_SEC,
      state: 'pending',
    }
    this.byDevice.set(grant.deviceHash, grant)
    this.byUser.set(grant.userHash, grant)
    return { deviceCode, userCode }
  }

  /** The TV asking. An approved grant is removed as it is read, so it is released once. */
  poll(deviceCode: string): DevicePoll {
    const grant = this.byDevice.get(hash(deviceCode))
    if (!grant) return { status: 'unknown' }
    const now = this.now()
    if (now >= grant.expiresAt) {
      this.remove(grant)
      return { status: 'expired' }
    }
    if (grant.state === 'denied') {
      this.remove(grant)
      return { status: 'denied' }
    }
    if (grant.state === 'approved' && grant.userId) {
      this.remove(grant)
      return { status: 'approved', userId: grant.userId }
    }
    const early = grant.lastPollAt !== undefined && now - grant.lastPollAt < grant.intervalSec * 1000 - POLL_TOLERANCE_MS
    grant.lastPollAt = now
    if (early) {
      grant.intervalSec += SLOW_DOWN_STEP_SEC
      return { status: 'slow_down' }
    }
    return { status: 'pending' }
  }

  /** True when `userCode` (normalized) names a code still waiting for a decision. */
  isPending(userCode: string): boolean {
    const grant = this.byUser.get(hash(userCode))
    return !!grant && grant.state === 'pending' && this.now() < grant.expiresAt
  }

  /** Approves for `userId`, or denies when null. False when the code is not waiting. */
  decide(userCode: string, userId: string | null): boolean {
    if (!this.isPending(userCode)) return false
    const grant = this.byUser.get(hash(userCode))!
    // Decided: the code can no longer be looked up, only collected by the TV.
    this.byUser.delete(grant.userHash)
    grant.state = userId ? 'approved' : 'denied'
    if (userId) grant.userId = userId
    return true
  }

  get size(): number {
    return this.byDevice.size
  }

  private remove(grant: Grant): void {
    this.byDevice.delete(grant.deviceHash)
    this.byUser.delete(grant.userHash)
  }

  /** Drops expired grants; a late poll then gets "unknown", which the TV treats as expired. */
  private sweep(): void {
    const now = this.now()
    for (const grant of this.byDevice.values()) if (now >= grant.expiresAt) this.remove(grant)
  }
}
