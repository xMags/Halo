package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import moe.ditto.halo.SignedInGraph
import moe.ditto.halo.browse.MinSearchTermLength
import moe.ditto.halo.browse.savedSearchMatches
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.ui.CatalogRow
import moe.ditto.halo.ui.CenterMessage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
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
 * Search across every installed addon, one row per catalog that answers.
 *
 * Results stay grouped rather than merged: a catalog is a curated view, so two
 * addons answering the same query are two statements about it, and flattening
 * would throw away which addon vouched for what.
 */
@Composable
internal fun SearchScreen(
    graph: SignedInGraph,
    onOpenDetail: (MetaRef) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val responsive = rememberResponsive()
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

    Column(
        modifier
            .fillMaxSize()
            .background(HaloColors.Background)
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + HaloSpacing.Xs),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HaloSpacing.Md)
                .padding(bottom = HaloSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Md),
        ) {
            Box(Modifier.weight(1f)) {
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
                val listState = rememberLazyListState()
                val focusManager = LocalFocusManager.current
                // Dragging the results dismisses the keyboard, as it does in the
                // shipping client — otherwise it covers half of what was found.
                LaunchedEffect(listState.isScrollInProgress) {
                    if (listState.isScrollInProgress) focusManager.clearFocus()
                }
                val posterWidth = responsive.pick(132.dp, 150.dp, 168.dp)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // No tab-bar allowance: this screen covers the bar.
                    contentPadding = PaddingValues(bottom = HaloSpacing.Xl),
                ) {
                    // What the user already owns leads: it is the one row here
                    // they have definitely chosen before, and it needs no request
                    // to appear.
                    if (saved.isNotEmpty()) {
                        item(key = "saved") {
                            CatalogRow(
                                title = "My Library",
                                items = remember(saved) { saved.map { it.posterItem() } },
                                onItemClick = { item ->
                                    record(trimmed)
                                    onOpenDetail(item.metaRef())
                                },
                                posterWidth = posterWidth,
                                showLabels = true,
                            )
                        }
                    }
                    // The addon rows keep arriving behind it; a spinner under the
                    // saved row says so without hiding what already landed.
                    if (groups == null) {
                        item(key = "addons-pending") {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = HaloSpacing.Lg),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(color = HaloColors.Accent)
                            }
                        }
                    }
                    items(items = groups.orEmpty(), key = { it.key }) { group ->
                        CatalogRow(
                            title = group.title,
                            items = remember(group) { group.metas.map { it.posterItem() } },
                            onItemClick = { item ->
                                // The term earned its place in history: it found
                                // something worth opening.
                                record(trimmed)
                                onOpenDetail(item.metaRef())
                            },
                            posterWidth = posterWidth,
                            showLabels = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHistory(
    terms: List<String>,
    onRunAgain: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    if (terms.isEmpty()) {
        CenterMessage("Search every installed addon — titles, series, anything.")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = HaloSpacing.Md, vertical = HaloSpacing.Xs),
    ) {
        item(key = "recent-head") {
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
        items(items = terms, key = { it }) { entry ->
            HistoryRow(
                term = entry,
                onRunAgain = { onRunAgain(entry) },
                onRemove = { onRemove(entry) },
            )
        }
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
                imageVector = HaloIcons.Close,
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
