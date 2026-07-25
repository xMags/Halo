package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.SignedInGraph
import moe.ditto.halo.api.MetaCard
import moe.ditto.halo.browse.CatalogRowSpec
import moe.ditto.halo.browse.catalogRows
import moe.ditto.halo.browse.homeShelves
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.ui.CatalogRow
import moe.ditto.halo.ui.CenterMessage
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloDimensions
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.HeroScrim
import moe.ditto.halo.ui.MetaLine
import moe.ditto.halo.ui.rememberResponsive

/**
 * Home: a featured title, the three personal shelves, then one row per
 * browsable catalog.
 *
 * The header stays mounted through every state, including the failure one. It
 * owns no server data, so there is nothing about it that a failed fetch makes
 * wrong — and losing the search field because a catalog request timed out is a
 * worse outcome than an empty body.
 */
@Composable
internal fun HomeScreen(
    graph: SignedInGraph,
    onOpenSearch: () -> Unit,
    onOpenDetail: (MetaRef) -> Unit,
    /** Movies play straight from the featured card; series open their detail screen instead. */
    onPlayMovie: (MetaCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val responsive = rememberResponsive()
    // Continue Watching deliberately ignores this — see homeShelves — while
    // every other shelf and every catalog row honours it.
    var filter by remember { mutableStateOf(MediaTypeFilter.All) }

    val addons by remember(graph) { graph.addons.observeEffective() }.collectAsState(QueryState())
    val watchStates by remember(graph) { graph.watchStates.observe() }.collectAsState(QueryState())
    // Tombstones included: the shelf derivation filters them itself, because it
    // also has to keep a removed entry from naming a watch-state row.
    val library by remember(graph) { graph.library.observe() }.collectAsState(QueryState())

    val allRows = remember(addons.value) { catalogRows(addons.value.orEmpty()) }
    val rows = remember(allRows, filter) {
        allRows.filter { filter.type == null || it.type == filter.type }
    }
    val shelves = remember(watchStates.value, library.value, filter) {
        homeShelves(watchStates.value, library.value, filter.type)
    }

    // The featured title is the first entry of the first visible row, then its
    // full meta for wide art and a rating. That catalog request shares a cache
    // key with the row below, so this costs one fetch, not two.
    val lead = rows.firstOrNull()
    val leadCatalog by remember(graph, lead?.key) {
        graph.browse.catalog(
            addonId = lead?.addonId.orEmpty(),
            type = lead?.type.orEmpty(),
            catalogId = lead?.catalogId.orEmpty(),
            enabled = lead != null,
        )
    }.collectAsState(QueryState())
    val preview = leadCatalog.value?.firstOrNull()
    val featuredMeta by remember(graph, preview?.type, preview?.id) {
        graph.browse.meta(
            type = preview?.type.orEmpty(),
            metaId = preview?.id.orEmpty(),
            enabled = preview != null,
        )
    }.collectAsState(QueryState())
    // The preview carries a name and a poster already, so it stands in until the
    // richer record lands rather than leaving the hero blank.
    val featured: MetaCard? = featuredMeta.value ?: preview

    val shelfPosterWidth = responsive.pick(132.dp, 150.dp, 168.dp)
    // Catalog rows run narrower than the personal shelves on purpose: those are
    // a handful of cards, these are an endless strip.
    val rowPosterWidth = responsive.pick(HaloDimensions.PosterWidth, 140.dp, 156.dp)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        // The header owns the status-bar inset; only the floating tab bar has to
        // be allowed for here.
        contentPadding = PaddingValues(bottom = HaloDimensions.TabBarSpace),
    ) {
        item(key = "header") {
            ScreenHeader(
                title = "Watch",
                modifier = Modifier.padding(horizontal = HaloSpacing.Md),
                onOpenSearch = onOpenSearch,
                filter = filter,
                onFilterChange = { filter = it },
            )
        }

        // Body states. A failure with nothing cached is the only one that
        // replaces the content; a failure with a cached list keeps rendering it
        // and revalidates, which is what the cache retains values for.
        if (addons.value == null) {
            item(key = "body-state") {
                Box(Modifier.fillMaxWidth().height(BodyStateHeight), contentAlignment = Alignment.Center) {
                    when {
                        addons.error != null -> CenterMessage("Could not reach your Halo server.")
                        else -> CircularProgressIndicator(color = HaloColors.Accent)
                    }
                }
            }
            return@LazyColumn
        }
        if (allRows.isEmpty()) {
            item(key = "body-state") {
                Box(Modifier.fillMaxWidth().height(BodyStateHeight), contentAlignment = Alignment.Center) {
                    CenterMessage("No catalogs yet — add an addon in Settings.")
                }
            }
            return@LazyColumn
        }

        featured?.let { card ->
            item(key = "featured") {
                FeaturedHero(
                    card = card,
                    height = responsive.pick(210.dp, 300.dp, 340.dp),
                    onOpen = { onOpenDetail(MetaRef(card.type, card.id)) },
                    onPlay = {
                        if (card.type == MovieType) onPlayMovie(card) else onOpenDetail(MetaRef(card.type, card.id))
                    },
                )
            }
        }

        if (shelves.continueWatching.isNotEmpty()) {
            item(key = "shelf-continue") {
                CatalogRow(
                    title = "Continue Watching",
                    items = shelves.continueWatching.map { it.meta.posterItem(progress = it.progress) },
                    onItemClick = { onOpenDetail(it.metaRef()) },
                    posterWidth = shelfPosterWidth,
                )
            }
        }
        if (shelves.recentlyWatched.isNotEmpty()) {
            item(key = "shelf-recent") {
                CatalogRow(
                    title = "Recently Watched",
                    items = shelves.recentlyWatched.map { it.posterItem() },
                    onItemClick = { onOpenDetail(it.metaRef()) },
                    posterWidth = shelfPosterWidth,
                )
            }
        }
        if (shelves.library.isNotEmpty()) {
            item(key = "shelf-library") {
                CatalogRow(
                    title = "My Library",
                    items = shelves.library.map { it.posterItem() },
                    onItemClick = { onOpenDetail(it.metaRef()) },
                    posterWidth = shelfPosterWidth,
                )
            }
        }

        items(items = rows, key = { it.key }) { spec ->
            HomeCatalogRow(
                graph = graph,
                spec = spec,
                posterWidth = rowPosterWidth,
                onOpenDetail = onOpenDetail,
            )
        }
    }
}

private const val MovieType = "movie"

/** Tall enough that a spinner or message lands near the middle of the screen. */
private val BodyStateHeight = 420.dp

/**
 * One catalog's row, observing its own query.
 *
 * The row is what fetches, so a screen full of them costs one request per row
 * and only for rows that have been scrolled to — the lazy list never composes
 * the ones below the fold. A catalog that fails or comes back empty removes
 * itself; [CatalogRow] handles that, which is why the error is not surfaced
 * here.
 */
@Composable
private fun HomeCatalogRow(
    graph: SignedInGraph,
    spec: CatalogRowSpec,
    posterWidth: Dp,
    onOpenDetail: (MetaRef) -> Unit,
) {
    val state by remember(graph, spec.key) {
        graph.browse.catalog(spec.addonId, spec.type, spec.catalogId)
    }.collectAsState(QueryState())

    val metas = state.value
    CatalogRow(
        title = spec.title,
        items = remember(metas) { metas.orEmpty().map { it.posterItem() } },
        onItemClick = { onOpenDetail(it.metaRef()) },
        posterWidth = posterWidth,
        isLoading = metas == null && state.error == null,
    )
}

@Composable
private fun FeaturedHero(
    card: MetaCard,
    height: Dp,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
) {
    Box(
        Modifier
            .padding(horizontal = HaloSpacing.Md)
            .padding(bottom = HaloSpacing.Lg)
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(HaloRadius.Xl))
            .background(HaloColors.Surface)
            .clickable(onClick = onOpen),
    ) {
        // Wide art when the addon has it, the poster cropped otherwise — a
        // hero with no image at all reads as a broken layout.
        //
        // fillMaxSize, NOT matchParentSize: with matchParentSize the art never
        // loaded on device — the hero stayed empty while every poster on the
        // same screen, same host, loaded fine. The box is already a fixed size,
        // so filling it needs no deferred measurement pass. The scrim below is
        // a plain gradient with nothing to fetch, so it can keep matching.
        HaloAsyncImage(
            url = card.background ?: card.poster,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
        HeroScrim(Modifier.matchParentSize())
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(HaloSpacing.Md),
        ) {
            Text(
                text = card.name,
                color = HaloColors.Text,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.2.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MetaLine(
                parts = listOfNotNull(card.releaseInfo, card.genres.firstOrNull()),
                rating = card.imdbRating,
                modifier = Modifier.padding(top = HaloSpacing.Xs),
            )
            PlayButton(
                onClick = onPlay,
                modifier = Modifier.padding(top = HaloSpacing.Sm + 2.dp),
            )
        }
    }
}

/**
 * Sits inside the hero's own clickable area. The inner press wins in Compose
 * exactly as it does in the shipping client, so Play plays and the surrounding
 * art opens the detail screen.
 */
@Composable
private fun PlayButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(HaloRadius.Pill))
            .background(HaloColors.Primary)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = HaloIcons.Play,
            // The label beside it already says Play.
            contentDescription = null,
            tint = HaloColors.OnPrimary,
            modifier = Modifier.size(16.dp),
        )
        Text(text = "Play", color = HaloColors.OnPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
