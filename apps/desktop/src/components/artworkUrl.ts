/**
 * TMDB serves every image at named widths under `/t/p/<size>/`, and some
 * addons (DesiFlix, for one) hand out the `original` upload: often 2000×3000
 * and several megabytes. A card shows it a tenth of that size, and WebView2's
 * fast downscale of such a ratio aliases fine detail into a grainy, pixelated
 * poster, while posters other addons send already near card size look sharp.
 * Asking TMDB for a sized copy fixes both the look and the download.
 */
const TMDB_ORIGINAL = /^(https:\/\/image\.tmdb\.org\/t\/p\/)original\//

/**
 * The URL to draw a card-sized piece of art from: a TMDB `original` image as
 * its [width]-pixel copy (500 covers a card of up to 250 CSS px at twice the
 * pixel density). Any other URL, including TMDB sizes already chosen, is
 * returned as given.
 */
export function cardArtworkUrl(url: string, width = 500): string {
  return url.replace(TMDB_ORIGINAL, `$1w${width}/`)
}
