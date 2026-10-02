import type { AddonError, AddonErrorCode, ResolutionRoute } from '@halo/core'

/**
 * Server-side observability for addon failures. Resolution itself (which
 * addons to ask, normalizing their answers, classifying failures) lives in
 * `@halo/core`, shared with the on-device backend.
 */

export interface AddonFailureLog {
  route: ResolutionRoute
  addonId: string
  addonName: string
  code: AddonErrorCode
  status?: number
  durationMs: number
}

export type AddonFailureLogger = (event: AddonFailureLog) => void

export function logAddonFailure(
  logger: AddonFailureLogger,
  route: AddonFailureLog['route'],
  error: AddonError,
  durationMs: number,
): void {
  const code = error.code ?? 'unavailable'
  try {
    logger({
      route,
      addonId: error.id,
      addonName: error.name ?? 'Addon',
      code,
      ...(error.status !== undefined ? { status: error.status } : {}),
      durationMs: Math.max(0, Math.round(durationMs)),
    })
  } catch {
    // Observability must never turn an addon failure into a route failure.
  }
}
