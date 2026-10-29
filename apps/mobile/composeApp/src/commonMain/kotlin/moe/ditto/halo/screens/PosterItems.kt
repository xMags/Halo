package moe.ditto.halo.screens

import androidx.compose.runtime.Composable
import moe.ditto.halo.api.LibraryItem
import moe.ditto.halo.api.MetaCard
import moe.ditto.halo.api.MetaPreview
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
 * A saved row as a card.
 *
 * `LibraryItem.id` is already the `"${type}:${metaId}"` key form, so the key is
 * taken as-is rather than rebuilt — the library and the poster grid agree on
 * identity by construction, not by two spellings that could drift.
 */
fun LibraryItem.posterItem(): PosterItem = PosterItem(
    key = id,
    title = name,
    posterUrl = poster,
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

/** A saved row as the title it stands for, for the menus, which act on titles. */
fun LibraryItem.metaPreview(): MetaPreview =
    MetaPreview(id = id.substringAfter(':'), type = type, name = name, poster = poster)

/**
 * The long-press menu for posters standing for [metas], each found again by
 * its card's `type:id` key. Null outside a shell, leaving long presses inert.
 */
@Composable
internal fun posterMenu(metas: List<MetaCard>): ((PosterItem) -> Unit)? {
    val menus = LocalTitleMenus.current ?: return null
    return { item -> metas.firstOrNull { "${it.type}:${it.id}" == item.key }?.let(menus::openPoster) }
}
