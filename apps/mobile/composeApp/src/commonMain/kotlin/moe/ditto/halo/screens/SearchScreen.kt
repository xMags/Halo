package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.browse.MinSearchTermLength
import moe.ditto.halo.browse.mergedSearchResults
import moe.ditto.halo.browse.savedSearchMatches
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.ui.PosterGrid
import moe.ditto.halo.ui.CenterMessage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloLayout
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.HaloType
import moe.ditto.halo.ui.SearchField
import moe.ditto.halo.ui.rememberResponsive

/**
 * Long enough that typing a title does not fan out a request per keystroke,
 * short enough that pausing feels like the search starting on its own.
 */
private const val DebounceMs = 350L

/**
 * Search across every installed addon, shown as one list as on the desktop.
 *
 * The catalogs' answers are merged ([mergedSearchResults]): which addon found
 * a title is not what a viewer is looking for, and a row per catalog showed
 * the same film once per addon that knows it. What the viewer has in their
 * library leads the list.
 */
@Composable
internal fun SearchScreen(
    graph: SessionGraph,
    onOpenDetail: (MetaRef) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val responsive = rememberResponsive()
    // A tablet held sideways gets the desktop's search: one merged grid.
    if (usesWideSearch(responsive)) {
        WideSearchScreen(graph, onOpenDetail, onClose, modifier)
        return
    }
    var term by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }

    // Restarting the effect on every keystroke cancels the pending delay, which
    // is the whole debounce — no timer handle to hold or clear.
    LaunchedEffect(term) {
        delay(DebounceMs)
        debounced = term
    }

    val history by graph.searchHistory.terms.collectAsState()
    val results by remember(graph, debounced) { graph.browse.search(debounced) }.collectAsState(QueryState())
    // Already cached by the time search is opened, so its matches render before
    // any addon answers.
    val library by remember(graph) { graph.library.observe() }.collectAsState(QueryState())

    val trimmed = debounced.trim()
    val searching = trimmed.length >= MinSearchTermLength
    val saved = remember(library.value, trimmed) { savedSearchMatches(library.value, trimmed) }

    // History records deliberate acts only — submitting, re-running a past term,
    // or opening a result. The debounced keystroke stream is not intent.
    val record = { value: String -> graph.searchHistory.add(value); Unit }
    val runAgain = { value: String ->
        term = value
        debounced = value
        record(value)
    }

    val gutter = responsive.gutter

    Column(
        modifier
            .fillMaxSize()
            .background(HaloColors.Background)
            // The rail floats over the leading edge; everything on this screen
            // begins beside it, and the gutter is measured from there.
            .padding(start = responsive.contentInsetStart)
            .padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
                    responsive.pick(HaloSpacing.Xs, HaloSpacing.Sm),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gutter)
                .padding(bottom = responsive.pick(HaloSpacing.Md, HaloSpacing.Lg)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Md),
        ) {
            Box(
                // Capped rather than full-bleed: the clear button on a
                // tablet-wide field ends up a hand's width from the text.
                Modifier.weight(1f, fill = false).widthIn(max = HaloLayout.SearchFieldMaxWidth),
            ) {
                SearchField(
                    value = term,
                    onValueChange = { term = it },
                    onClear = { term = "" },
                    onSubmit = { if (term.trim().length >= MinSearchTermLength) record(term) },
                    autoFocus = true,
                )
            }
            Text(
                text = "Cancel",
                color = HaloColors.Accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onClose)
                    .padding(vertical = HaloSpacing.Xs),
            )
        }

        if (!searching) {
            SearchHistory(
                terms = history,
                gutter = gutter,
                // A stacked list of four words down the side of a tablet is a
                // column of empty space; the same terms wrap as chips instead.
                asChips = responsive.isTablet,
                onRunAgain = runAgain,
                onRemove = { graph.searchHistory.remove(it) },
                onClear = { graph.searchHistory.clear() },
            )
            return@Column
        }

        val groups = results.value
        when {
            // A failed search is not an empty one. Every per-catalog failure is
            // already swallowed inside the fan-out, so an error here means the
            // addon list itself could not be read — saying "no results" would
            // blame the query for a dead connection. Saved matches still show:
            // they came from the cache and are unaffected by that failure.
            groups == null && results.error != null && saved.isEmpty() ->
                CenterMessage("Could not reach your Halo server.")
            groups == null && saved.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = HaloColors.Accent)
                }
            groups.orEmpty().isEmpty() && saved.isEmpty() -> CenterMessage("No results for “$trimmed”.")
            else -> {
                val gridState = rememberLazyGridState()
                val focusManager = LocalFocusManager.current
                // Dragging the results dismisses the keyboard, as it does in the
                // shipping client — otherwise it covers half of what was found.
                LaunchedEffect(gridState.isScrollInProgress) {
                    if (gridState.isScrollInProgress) focusManager.clearFocus()
                }
                // What the viewer already owns leads: it is what they have
                // definitely chosen before, and it needs no request to appear.
                // The addons' answers follow as one list, without those titles.
                val owned = remember(saved) { saved.map { it.metaPreview() } }
                // Which of them have artwork is only known by asking; until the
                // answers are in, the list is judged on what the addons said.
                val candidates = remember(groups, owned, trimmed) {
                    mergedSearchResults(groups.orEmpty(), trimmed, exclude = owned)
                }
                var missingArt by remember(trimmed) { mutableStateOf(emptySet<String>()) }
                LaunchedEffect(candidates) { missingArt = graph.posterProbe.missing(candidates) }
                val found = remember(groups, owned, trimmed, missingArt) {
                    mergedSearchResults(groups.orEmpty(), trimmed, exclude = owned, missingArt = missingArt)
                }
                val titles = remember(owned, found) { owned + found }
                PosterGrid(
                    items = remember(titles) { titles.map { it.posterItem() } },
                    columns = responsive.posterColumns,
                    onItemClick = { item ->
                        // The term earned its place in history: it found
                        // something worth opening.
                        record(trimmed)
                        onOpenDetail(item.metaRef())
                    },
                    modifier = Modifier.fillMaxSize(),
                    // No tab-bar allowance: this screen covers the bar.
                    contentPadding = PaddingValues(start = gutter, end = gutter, bottom = HaloSpacing.Xl),
                    showLabels = true,
                    itemMenu = posterMenu(titles),
                    // The addons' answers keep arriving behind the library's; a
                    // spinner under what has landed says so without hiding it.
                    footer = if (groups != null) {
                        null
                    } else {
                        {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = HaloSpacing.Lg),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(color = HaloColors.Accent)
                            }
                        }
                    },
                    state = gridState,
                )
            }
        }
    }
}

@Composable
private fun SearchHistory(
    terms: List<String>,
    gutter: Dp,
    asChips: Boolean,
    onRunAgain: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    if (terms.isEmpty()) {
        CenterMessage("Search every installed addon — titles, series, anything.")
        return
    }

    val head: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = HaloSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "Recent", style = HaloType.Heading.copy(fontSize = 16.sp, letterSpacing = (-0.2).sp))
            Text(
                text = "Clear",
                color = HaloColors.Accent,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onClear)
                    .padding(HaloSpacing.Xs),
            )
        }
    }

    if (asChips) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = gutter, end = gutter)
                .padding(vertical = HaloSpacing.Xs),
        ) {
            head()
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = HaloSpacing.Sm),
                horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
            ) {
                terms.forEach { entry ->
                    HistoryChip(
                        term = entry,
                        onRunAgain = { onRunAgain(entry) },
                        onRemove = { onRemove(entry) },
                    )
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = gutter, vertical = HaloSpacing.Xs),
    ) {
        item(key = "recent-head") { head() }
        items(items = terms, key = { it }) { entry ->
            HistoryRow(
                term = entry,
                onRunAgain = { onRunAgain(entry) },
                onRemove = { onRemove(entry) },
            )
        }
    }
}

/**
 * One past term as a chip: the tablet's form of the phone's [HistoryRow].
 *
 * The remove affordance rides inside the chip rather than at the end of a row,
 * because there is no row for it to end — but it stays a separate target, so
 * running a term again and forgetting it are not the same tap.
 */
@Composable
private fun HistoryChip(term: String, onRunAgain: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(HaloRadius.Pill))
            .background(HaloColors.Glass)
            .border(1.dp, HaloColors.GlassBorder, RoundedCornerShape(HaloRadius.Pill))
            .clickable(role = Role.Button, onClick = onRunAgain)
            .padding(start = 14.dp, end = HaloSpacing.Sm, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = term, color = HaloColors.TextMeta, fontSize = 12.5.sp, maxLines = 1)
        Icon(
            imageVector = HaloIcons.Trash,
            contentDescription = "Remove $term",
            tint = HaloColors.TextDim,
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onRemove)
                .size(13.dp),
        )
    }
}

@Composable
private fun HistoryRow(term: String, onRunAgain: () -> Unit, onRemove: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(role = Role.Button, onClick = onRunAgain)
                    .padding(vertical = HaloSpacing.Sm + 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm + 2.dp),
            ) {
                Icon(
                    imageVector = HaloIcons.Clock,
                    contentDescription = null,
                    tint = HaloColors.TextDim,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    text = term,
                    color = HaloColors.Text,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = HaloIcons.Trash,
                contentDescription = "Remove $term",
                tint = HaloColors.TextDim,
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onRemove)
                    .padding(HaloSpacing.Sm)
                    .size(16.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Hairline))
    }
}
