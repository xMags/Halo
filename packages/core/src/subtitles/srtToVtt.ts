/**
 * SRT → WebVTT: browsers only render VTT in <track>, while subtitle addons
 * overwhelmingly serve SRT. VTT is nearly a superset — prepend the header,
 * switch the timestamp comma to a dot, drop bare cue numbers.
 */
export function srtToVtt(srt: string): string {
  const body = srt
    .replace(/^﻿/, '')
    .replace(/\r\n/g, '\n')
    .split('\n\n')
    .map(convertCue)
    .filter((cue) => cue.length > 0)
    .join('\n\n')
  return `WEBVTT\n\n${body}\n`
}

const TIMESTAMP_LINE = /(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})/

function convertCue(cue: string): string {
  const lines = cue.split('\n').filter((l) => l.trim().length > 0)
  const tsIndex = lines.findIndex((l) => TIMESTAMP_LINE.test(l))
  if (tsIndex === -1) return ''
  const timestamp = lines[tsIndex]!.replace(
    TIMESTAMP_LINE,
    (_, h1, m1, s1, ms1, h2, m2, s2, ms2) =>
      `${pad(h1)}:${m1}:${s1}.${ms1} --> ${pad(h2)}:${m2}:${s2}.${ms2}`,
  )
  const text = lines.slice(tsIndex + 1)
  if (text.length === 0) return ''
  return [timestamp, ...text].join('\n')
}

function pad(h: string): string {
  return h.length === 1 ? `0${h}` : h
}

/** Detects whether a subtitle payload is already WebVTT. */
export function isVtt(content: string): boolean {
  return content.replace(/^﻿/, '').trimStart().startsWith('WEBVTT')
}
