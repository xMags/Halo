package moe.ditto.halo.tv.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.HaloApiException
import moe.ditto.halo.people.FilmographyFilter
import moe.ditto.halo.people.PersonDetail
import moe.ditto.halo.people.creditLine
import moe.ditto.halo.people.departmentLabel
import moe.ditto.halo.people.exactNamePerson
import moe.ditto.halo.people.filmography
import moe.ditto.halo.people.filmographyFilters
import moe.ditto.halo.screens.MetaRef
import moe.ditto.halo.tv.kit.TvButton
import moe.ditto.halo.tv.kit.TvButtonStyle
import moe.ditto.halo.tv.kit.TvMetrics
import moe.ditto.halo.tv.kit.TvPosterCard
import moe.ditto.halo.tv.kit.TvType
import moe.ditto.halo.tv.kit.defaultFocus
import moe.ditto.halo.tv.kit.rememberFocus
import moe.ditto.halo.tv.kit.rememberTvFocusMemory
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.metahubPosterUrl

private sealed interface PersonLoad {
    data object Loading : PersonLoad
    data class Loaded(val detail: PersonDetail) : PersonLoad
    data class Failed(val message: String, val canRetry: Boolean) : PersonLoad
}

private const val PersonColumns = 6
private val CreditPosterWidth = 112.dp

/**
 * A person on TV: who they are and their films and series, newest first,
 * with the same role filter as the phone. Opened by TMDB id, or by name when
 * the cast entry gave no id; a name nobody has exactly says so, since a TV
 * cannot open the web search the phone falls back on.
 */
@Composable
internal fun TvPersonScreen(graph: SessionGraph, tmdbId: Long, name: String, photo: String?, navigation: TvBrowseNavigation) {
    val memory = rememberTvFocusMemory()
    val capabilitiesState by graph.capabilities.state.collectAsState()
    var attempt by remember { mutableIntStateOf(0) }
    var load by remember(tmdbId, name) { mutableStateOf<PersonLoad>(PersonLoad.Loading) }
    var filter by rememberSaveable { mutableStateOf(FilmographyFilter.All) }

    LaunchedEffect(graph, tmdbId, name, capabilitiesState.settled, capabilitiesState.capabilities.people, attempt) {
        if (!capabilitiesState.settled) return@LaunchedEffect
        if (!capabilitiesState.capabilities.people) {
            load = PersonLoad.Failed("This page needs a Halo server that supports people search.", canRetry = false)
            return@LaunchedEffect
        }
        load = PersonLoad.Loading
        load = try {
            val id = if (tmdbId > 0) tmdbId else exactNamePerson(graph.backend.searchPeople(name).people, name)?.tmdbId
            if (id == null) {
                PersonLoad.Failed("Nobody named $name was found.", canRetry = false)
            } else {
                PersonLoad.Loaded(graph.backend.getPerson(id))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (failure is HaloApiException && failure.status == 404) {
                PersonLoad.Failed("The server doesn't know this person.", canRetry = false)
            } else {
                PersonLoad.Failed("The server did not answer. Try again in a moment.", canRetry = true)
            }
        }
    }

    val detail = (load as? PersonLoad.Loaded)?.detail
    val shownName = detail?.person?.name ?: name
    val credits = detail?.credits.orEmpty()
    val filters = filmographyFilters(credits)
    val shownFilter = if (filter in filters) filter else FilmographyFilter.All
    val shown = filmography(credits, shownFilter)

    ProvideVerticalPivot(pivot = 40.dp) {
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = TvMetrics.VerticalMargin + 8.dp, bottom = TvMetrics.VerticalMargin + 40.dp),
            verticalArrangement = Arrangement.spacedBy(TvMetrics.RowGap),
        ) {
            item(key = "head") {
                Row(Modifier.padding(start = TvMetrics.SideMargin + 8.dp, end = TvMetrics.SideMargin), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    HaloAsyncImage(
                        url = detail?.person?.photo ?: photo,
                        contentDescription = shownName,
                        modifier = Modifier.size(112.dp, 168.dp).clip(RoundedCornerShape(14.dp)),
                    )
                    Column(Modifier.width(620.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        departmentLabel(detail?.person?.knownFor).takeIf { it.isNotEmpty() }?.let { Text(it.uppercase(), style = TvType.Overline) }
                        Text(shownName, style = TvType.Hero)
                        detail?.person?.biography?.let { Text(it, style = TvType.Body.copy(lineHeight = TvType.Body.lineHeight), maxLines = 4, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            when (val state = load) {
                PersonLoad.Loading -> item(key = "loading") { TvSkeletonRow(6, CreditPosterWidth, CreditPosterWidth * 1.5f) }
                is PersonLoad.Failed -> item(key = "failed") {
                    TvBodyMessage(
                        state.message,
                        retryModifier = Modifier.defaultFocus(memory, "retry"),
                        // Nothing else on the page can take focus, so a failure
                        // that cannot be retried offers the way back instead.
                        onRetry = if (state.canRetry) ({ attempt += 1 }) else navigation.back,
                        actionLabel = if (state.canRetry) "Try again" else "Go back",
                    )
                }
                is PersonLoad.Loaded -> {
                    if (filters.size > 1) {
                        item(key = "filters") {
                            Row(Modifier.padding(start = TvMetrics.SideMargin + 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                filters.forEachIndexed { index, option ->
                                    val key = "filter/${option.name}"
                                    TvButton(
                                        option.label,
                                        onClick = { filter = option },
                                        style = if (option == shownFilter) TvButtonStyle.Primary else TvButtonStyle.Secondary,
                                        modifier = if (index == 0) Modifier.defaultFocus(memory, key) else Modifier.rememberFocus(memory, key),
                                    )
                                }
                            }
                        }
                    }
                    if (shown.isEmpty()) {
                        item(key = "empty") {
                            TvBodyMessage(
                                "No films or series listed for $shownName yet.",
                                retryModifier = Modifier.defaultFocus(memory, "back", ready = filters.size <= 1),
                                // The filters take focus when there are any.
                                onRetry = navigation.back.takeIf { filters.size <= 1 },
                                actionLabel = "Go back",
                            )
                        }
                    }
                    items(shown.chunked(PersonColumns), key = { row -> row.joinToString { "${it.type}:${it.imdbId}" } }) { row ->
                        Row(Modifier.padding(start = TvMetrics.SideMargin + 8.dp), horizontalArrangement = Arrangement.spacedBy(TvMetrics.CardGap)) {
                            row.forEach { credit ->
                                val key = "credit/${credit.type}:${credit.imdbId}"
                                TvPosterCard(
                                    title = credit.name,
                                    type = credit.type,
                                    posterUrl = credit.poster,
                                    fallbackUrl = metahubPosterUrl(credit.imdbId),
                                    subtitle = creditLine(credit),
                                    width = CreditPosterWidth,
                                    onClick = { navigation.openDetail(MetaRef(credit.type, credit.imdbId)) },
                                    // With no role filter to start on, the first title takes focus.
                                    modifier = if (credit == shown.first() && filters.size <= 1) {
                                        Modifier.defaultFocus(memory, key)
                                    } else {
                                        Modifier.rememberFocus(memory, key)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
