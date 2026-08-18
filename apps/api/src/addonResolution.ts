import {
  AddonRequestError,
  type AddonError,
  type AddonErrorCode,
  type Stream,
  type Subtitle,
} from '@halo/core'
import { ProxyTargetError } from './proxyGuard'

const MAX_RESULTS = 500
const MAX_URL_LENGTH = 16_384
const MAX_LABEL_LENGTH = 4_096
const MAX_FILENAME_LENGTH = 1_024
const MAX_BINGE_GROUP_LENGTH = 512
const MAX_HASH_LENGTH = 128
const MAX_SUBTITLE_ID_LENGTH = 512
const MAX_LANGUAGE_LENGTH = 64
const MAX_HEADERS = 64
const MAX_HEADER_NAME_LENGTH = 128
const MAX_HEADER_VALUE_LENGTH = 4_096
const MAX_ADDON_NAME_LENGTH = 80

export class InvalidAddonResponseError extends Error {
  constructor() {
    super('Invalid addon response')
    this.name = 'InvalidAddonResponseError'
  }
}

export interface AddonFailureLog {
  route: '/streams' | '/subtitles' | '/next-episode'
  addonId: string
  addonName: string
  code: AddonErrorCode
  status?: number
  durationMs: number
}

export type AddonFailureLogger = (event: AddonFailureLog) => void

interface ClassifiedFailure {
  code: AddonErrorCode
  message: string
  status?: number
}

export function safeAddonName(value: unknown): string {
  if (typeof value !== 'string') return 'Addon'
  const cleaned = value.replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim()
  if (/https?:\/\/|[/\\]|[A-Za-z0-9_-]{32,}/i.test(cleaned)) return 'Addon'
  return cleaned.slice(0, MAX_ADDON_NAME_LENGTH) || 'Addon'
}

export function addonError(id: string, name: unknown, reason: unknown): AddonError {
  const safeName = safeAddonName(name)
  const failure = classifyAddonFailure(reason)
  return {
    id,
    name: safeName,
    code: failure.code,
    ...(failure.status !== undefined ? { status: failure.status } : {}),
    message: failure.message,
  }
}

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

export function normalizeStreamsResponse(value: unknown): Stream[] {
  if (!isRecord(value) || !Array.isArray(value.streams)) throw new InvalidAddonResponseError()
  return value.streams.slice(0, MAX_RESULTS).flatMap((entry) => {
    const stream = normalizeStream(entry)
    return stream ? [stream] : []
  })
}

export function normalizeSubtitlesResponse(value: unknown): Subtitle[] {
  if (!isRecord(value) || !Array.isArray(value.subtitles)) throw new InvalidAddonResponseError()
  return normalizeSubtitles(value.subtitles)
}

function classifyAddonFailure(reason: unknown): ClassifiedFailure {
  if (isTimeout(reason)) return { code: 'timeout', message: 'Addon timed out.' }
  if (reason instanceof AddonRequestError) {
    const status = safeHttpStatus(reason.status)
    return {
      code: 'upstream_http',
      ...(status !== undefined ? { status } : {}),
      message: status === undefined ? 'Addon returned an HTTP error.' : `Addon returned HTTP ${status}.`,
    }
  }
  if (reason instanceof ProxyTargetError) {
    return { code: 'blocked_target', message: 'Addon target was blocked for safety.' }
  }
  if (reason instanceof InvalidAddonResponseError || reason instanceof SyntaxError) {
    return { code: 'invalid_response', message: 'Addon returned an invalid response.' }
  }
  return { code: 'unavailable', message: 'Addon is unavailable.' }
}

function normalizeStream(value: unknown): Stream | null {
  if (!isRecord(value)) return null
  const url = validHttpUrl(value.url)
  if (url === undefined || typeof value.infoHash === 'string') return null

  const subtitles = Array.isArray(value.subtitles) ? normalizeSubtitles(value.subtitles) : undefined
  const behaviorHints = normalizeBehaviorHints(value.behaviorHints)
  return {
    url,
    ...optionalString('name', value.name, MAX_LABEL_LENGTH),
    ...optionalString('title', value.title, MAX_LABEL_LENGTH),
    ...optionalString('description', value.description, MAX_LABEL_LENGTH),
    ...(subtitles !== undefined ? { subtitles } : {}),
    ...(behaviorHints !== undefined ? { behaviorHints } : {}),
  }
}

function normalizeBehaviorHints(value: unknown): Stream['behaviorHints'] | undefined {
  if (!isRecord(value)) return undefined
  const proxyHeaders = normalizeProxyHeaders(value.proxyHeaders)
  const videoSize = positiveSafeInteger(value.videoSize)
  const result = {
    ...(typeof value.notWebReady === 'boolean' ? { notWebReady: value.notWebReady } : {}),
    ...optionalString('bingeGroup', value.bingeGroup, MAX_BINGE_GROUP_LENGTH),
    ...(proxyHeaders !== undefined ? { proxyHeaders } : {}),
    ...optionalString('filename', value.filename, MAX_FILENAME_LENGTH),
    ...(videoSize !== undefined ? { videoSize } : {}),
    ...optionalString('videoHash', value.videoHash, MAX_HASH_LENGTH),
  }
  return Object.keys(result).length > 0 ? result : undefined
}

function normalizeProxyHeaders(value: unknown): NonNullable<NonNullable<Stream['behaviorHints']>['proxyHeaders']> | undefined {
  if (!isRecord(value)) return undefined
  const request = normalizeHeaderMap(value.request)
  const response = normalizeHeaderMap(value.response)
  if (request === undefined && response === undefined) return undefined
  return {
    ...(request !== undefined ? { request } : {}),
    ...(response !== undefined ? { response } : {}),
  }
}

function normalizeHeaderMap(value: unknown): Record<string, string> | undefined {
  if (!isRecord(value)) return undefined
  const result: Record<string, string> = {}
  for (const [name, headerValue] of Object.entries(value).slice(0, MAX_HEADERS)) {
    if (name.length === 0 || name.length > MAX_HEADER_NAME_LENGTH || /[\r\n\u0000]/.test(name)) continue
    if (typeof headerValue !== 'string' || headerValue.length > MAX_HEADER_VALUE_LENGTH || /[\r\n\u0000]/.test(headerValue)) continue
    result[name] = headerValue
  }
  return Object.keys(result).length > 0 ? result : undefined
}

function normalizeSubtitles(values: unknown[]): Subtitle[] {
  return values.slice(0, MAX_RESULTS).flatMap((value) => {
    if (!isRecord(value)) return []
    const id = requiredString(value.id, MAX_SUBTITLE_ID_LENGTH)
    const url = validHttpUrl(value.url)
    const lang = requiredString(value.lang, MAX_LANGUAGE_LENGTH)
    return id && url && lang ? [{ id, url, lang }] : []
  })
}

function optionalString<K extends string>(key: K, value: unknown, maxLength: number): Partial<Record<K, string>> {
  return typeof value === 'string' && value.length <= maxLength ? ({ [key]: value } as Record<K, string>) : {}
}

function requiredString(value: unknown, maxLength: number): string | undefined {
  return typeof value === 'string' && value.length > 0 && value.length <= maxLength ? value : undefined
}

function validHttpUrl(value: unknown): string | undefined {
  if (typeof value !== 'string' || value.length === 0 || value.length > MAX_URL_LENGTH) return undefined
  try {
    const url = new URL(value)
    return (url.protocol === 'http:' || url.protocol === 'https:') && url.hostname ? value : undefined
  } catch {
    return undefined
  }
}

function positiveSafeInteger(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : undefined
}

function safeHttpStatus(value: number): number | undefined {
  return Number.isInteger(value) && value >= 100 && value <= 599 ? value : undefined
}

function isTimeout(reason: unknown): boolean {
  return isRecord(reason) && (reason.name === 'TimeoutError' || reason.name === 'AbortError')
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
