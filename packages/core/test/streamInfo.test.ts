import assert from 'node:assert/strict'
import test from 'node:test'
import { parseStreamInfo } from '../src/index'

// Moved here with the parser from the desktop app, unchanged.

test('audio codecs read with their channel count glued on', () => {
  const audio = (title: string) => parseStreamInfo({ url: 'https://cdn.test/x', title }).audio
  assert.equal(audio('Show.S01E01.1080p.WEB-DL.DDP5.1.H.264'), 'DDP 5.1')
  assert.equal(audio('Film.2160p.DD+5.1.HEVC'), 'DDP 5.1')
  assert.equal(audio('Film.1080p.DD+.x264'), 'DDP')
  assert.equal(audio('Film.720p.DD5.1.x264'), 'DD 5.1')
  assert.equal(audio('Film.720p.WEB.AAC2.0.H.264'), 'AAC 2.0')
  assert.equal(audio('Film.2160p.DTS-HD.MA7.1'), 'DTS-HD')
  assert.equal(audio('Film.2160p.TrueHD7.1.HEVC'), 'TRUEHD')
  // A letter after the name is another word, not this codec.
  assert.equal(audio('Film.1080p.Ddplus.x264'), null)
})

test('the DTS-HD codec and a .ts extension are not read as a quality', () => {
  const quality = (title: string) => parseStreamInfo({ url: 'https://cdn.test/x', title }).quality
  assert.equal(quality('Movie.BluRay.DTS-HD.MA.5.1-GRP'), null)
  assert.equal(quality('Movie.BluRay.DTSHD-GRP'), null)
  assert.equal(quality('Movie.Remux-GRP.ts\n💾 20 GB'), null)
  // The real tokens still count.
  assert.equal(quality('Movie.HD.WEB.DTS-HD-GRP'), '720p')
  assert.equal(quality('Movie.2023.TS.x264-GRP'), 'SD')
  assert.equal(quality('Movie.1080p.DTS-HD.MA.5.1'), '1080p')
})

test('a source size is always whole bytes, as the download engine requires', () => {
  const size = (title: string, videoSize?: number) =>
    parseStreamInfo({
      url: 'https://cdn.test/x',
      title,
      ...(videoSize != null ? { behaviorHints: { videoSize } } : {}),
    }).sizeBytes

  // 29.35 GB multiplies out to 31514322534.4 bytes.
  assert.equal(size('Inception.2010.2160p.mkv 29.35 GB'), 31514322534)
  assert.equal(size('Movie.mkv 700.5 MB'), 734527488)
  // An addon's own fractional figure is rounded too, and one of zero means unknown.
  assert.equal(size('Movie.mkv', 1234.6), 1235)
  assert.equal(size('Movie.mkv 2 GB', 0), 2 * 1024 ** 3)
  assert.equal(size('Movie.mkv'), null)
})
