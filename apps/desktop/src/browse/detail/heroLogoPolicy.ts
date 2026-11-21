/*
 * How the title page draws a title's logo in each theme, as the native app's
 * `Views/HeroLogoPolicy` decides it. Logos are drawn for dark backdrops, so a
 * mostly white one would vanish on the light theme's pale page: there it is
 * judged first, then drawn as its artist drew it, in the page's ink, or
 * replaced by the title's name. No DOM here, so the tests run it directly.
 */

/** The most a logo download may be: logos are small marks, anything larger is refused. */
export const HERO_LOGO_MAX_BYTES = 4 * 1024 * 1024
/** The width a logo is sampled at for its brightness: enough to tell a white mark from a dark one. */
export const HERO_LOGO_SAMPLE_WIDTH = 96
/** Brighter than this, a logo reads as white on the light page. */
export const HERO_LOGO_LIGHT_MAX_LUMINANCE = 0.55
/**
 * Covering more of its box than this, a logo is a picture with its own
 * background, not a mark: inked, it would be a solid block. Heavy block
 * lettering covers up to about 70%.
 */
export const HERO_LOGO_INK_MAX_COVERAGE = 0.9
/** Below this share of opacity the logo is too faint to judge. */
const MINIMUM_COVERAGE = 0.002

export type HeroLogoTreatment = 'original' | 'inked' | 'name'

/** Only an absolute https address is fetched. */
export function isHeroLogoUrl(url: string): boolean {
  return /^https:\/\/[^\s]+$/i.test(url)
}

/**
 * The mean brightness (0 black to 1 white) of the logo's visible pixels, each
 * weighted by its opacity, from straight-alpha RGBA pixels (what a canvas's
 * `getImageData` returns). Null when nothing is visible or the bytes are not
 * whole pixels.
 */
export function heroLogoLuminance(rgba: ArrayLike<number>): number | null {
  if (rgba.length === 0 || rgba.length % 4 !== 0) return null
  let weighted = 0
  let weight = 0
  for (let index = 0; index < rgba.length; index += 4) {
    const alpha = rgba[index + 3]! / 255
    if (alpha <= 0) continue
    // Rec. 709 luma of the pixel's own colour.
    const luma = (0.2126 * rgba[index]! + 0.7152 * rgba[index + 1]! + 0.0722 * rgba[index + 2]!) / 255
    weighted += luma * alpha
    weight += alpha
  }
  const pixels = rgba.length / 4
  if (weight <= 0 || weight / pixels < MINIMUM_COVERAGE) return null
  return weighted / weight
}

/** The share of the box the logo covers: its pixels' mean opacity, 0 to 1. Null when the bytes are not whole pixels. */
export function heroLogoCoverage(rgba: ArrayLike<number>): number | null {
  if (rgba.length === 0 || rgba.length % 4 !== 0) return null
  let opacity = 0
  for (let index = 3; index < rgba.length; index += 4) opacity += rgba[index]! / 255
  return opacity / (rgba.length / 4)
}

/**
 * How a logo of that brightness and coverage is drawn. The dark theme always
 * shows it as drawn. The light theme shows a dark enough one as drawn, inks a
 * light mark, and keeps the name for a light logo that fills its box, or one
 * it could not judge.
 */
export function chooseHeroLogoTreatment(
  luminance: number | null,
  coverage: number | null,
  lightTheme: boolean,
): HeroLogoTreatment {
  if (!lightTheme) return 'original'
  if (luminance === null) return 'name'
  if (luminance <= HERO_LOGO_LIGHT_MAX_LUMINANCE) return 'original'
  if (coverage !== null && coverage <= HERO_LOGO_INK_MAX_COVERAGE) return 'inked'
  return 'name'
}
