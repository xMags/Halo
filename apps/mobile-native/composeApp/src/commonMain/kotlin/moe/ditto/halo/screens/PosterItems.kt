package moe.ditto.halo.screens

import moe.ditto.halo.api.MetaCard
import moe.ditto.halo.ui.PosterItem

/** A title as the content routes address it: its media type plus the addon's id for it. */
data class MetaRef(val type: String, val metaId: String)

/**
 * Turns anything card-shaped into a poster.
 *
 * The key is the library id form — `"${type}:${metaId}"` — which is what lets
 * [metaRef] read a tapped card back into the title it stands for. The
 * alternative, carrying the DTO alongside every poster so a click can find it
 * again, would type the row against the API shapes the card exists to avoid.
 */
fun MetaCard.posterItem(progress: Float? = null): PosterItem = PosterItem(
    key = "$type:$id",
    title = name,
    posterUrl = poster,
    progress = progress,
)

/**
 * The title a poster stands for.
 *
 * Split on the FIRST colon only. Media types never contain one; addon meta ids
 * routinely do (`kitsu:1234`), so splitting on the last — or on all of them —
 * would silently truncate every anime id.
 */
fun PosterItem.metaRef(): MetaRef = MetaRef(
    type = key.substringBefore(':'),
    metaId = key.substringAfter(':'),
)
