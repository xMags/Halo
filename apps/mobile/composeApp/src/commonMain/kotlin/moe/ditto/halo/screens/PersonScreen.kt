package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.HaloApiException
import moe.ditto.halo.api.MetaPreview
import moe.ditto.halo.browse.personSearchUrl
import moe.ditto.halo.people.FilmographyFilter
import moe.ditto.halo.people.PersonDetail
import moe.ditto.halo.people.creditLine
import moe.ditto.halo.people.departmentLabel
import moe.ditto.halo.people.exactNamePerson
import moe.ditto.halo.people.filmography
import moe.ditto.halo.people.filmographyFilters
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.PosterCard
import moe.ditto.halo.ui.PosterItem
import moe.ditto.halo.ui.monoStyle
import moe.ditto.halo.ui.rememberResponsive

/*
 * A person page: their portrait, who they are and their biography, then their
 * films and series with role chips. Opened by TMDB id (the people row, a cast
 * card that had one), or by name, when the page finds them first; a name
 * nobody has exactly opens IMDb's people search instead. The desktop's page is
 * the same (`person/Person.tsx`).
 */

private const val TmdbImage = "https://image.tmdb.org/t/p/"
private val PortraitWidth = 112.dp

/** TMDB's thumbnail profile photo at a size fit for the portrait. */
private fun portraitUrl(url: String?): String? {
    if (url == null || !url.startsWith("https://")) return null
    val thumbnail = "${TmdbImage}w185/"
    return if (url.startsWith(thumbnail)) "${TmdbImage}h632/${url.substring(thumbnail.length)}" else url
}

private enum class PersonError(val title: String, val text: String) {
    Failed("Couldn't load this page", "The server did not answer. Try again in a moment."),
    NotFound("This person isn't here", "The server doesn't know this person."),
    Unavailable("This page can't be shown", "This kind of page needs a Halo server that supports it."),
}

private sealed interface PersonLoad {
    data object Loading : PersonLoad
    data class Loaded(val detail: PersonDetail) : PersonLoad
    data class Error(val kind: PersonError) : PersonLoad
    /** Nobody has exactly that name: IMDb's search has been opened instead. */
    data object NobodyByName : PersonLoad
}

private fun errorOf(failure: Exception): PersonError =
    if (failure is HaloApiException && failure.status == 404) PersonError.NotFound else PersonError.Failed

@Composable
internal fun PersonScreen(
    graph: SessionGraph,
    tmdbId: Long,
    name: String,
    photo: String?,
    onBack: () -> Unit,
    onOpenDetail: (MetaRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val responsive = rememberResponsive()
    val uriHandler = LocalUriHandler.current
    val capabilitiesState by graph.capabilities.state.collectAsState()
    var attempt by remember { mutableIntStateOf(0) }
    var load by remember(tmdbId, name) { mutableStateOf<PersonLoad>(PersonLoad.Loading) }
    var filter by rememberSaveable { mutableStateOf(FilmographyFilter.All) }

    LaunchedEffect(graph, tmdbId, name, capabilitiesState.settled, capabilitiesState.capabilities.people, attempt) {
        if (!capabilitiesState.settled) return@LaunchedEffect
        if (!capabilitiesState.capabilities.people) {
            load = PersonLoad.Error(PersonError.Unavailable)
            return@LaunchedEffect
        }
        load = PersonLoad.Loading
        load = try {
            // Only a name: the person with exactly that name, the most popular of several.
            val id = if (tmdbId > 0) tmdbId else exactNamePerson(graph.backend.searchPeople(name).people, name)?.tmdbId
            if (id == null) PersonLoad.NobodyByName else PersonLoad.Loaded(graph.backend.getPerson(id))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            PersonLoad.Error(errorOf(failure))
        }
    }
    // Nobody has exactly that name: IMDb's search finds them instead, and this page goes.
    LaunchedEffect(load) {
        if (load != PersonLoad.NobodyByName) return@LaunchedEffect
        runCatching { uriHandler.openUri(personSearchUrl(name)) }
        onBack()
    }

    val detail = (load as? PersonLoad.Loaded)?.detail
    val shownName = detail?.person?.name ?: name
    val portrait = portraitUrl(detail?.person?.photo ?: photo)
    val kicker = departmentLabel(detail?.person?.knownFor)
    val credits = detail?.credits.orEmpty()
    val filters = filmographyFilters(credits)
    val shownFilter = if (filter in filters) filter else FilmographyFilter.All
    val shown = filmography(credits, shownFilter)
    val gutter = responsive.gutter

    LazyVerticalGrid(
        columns = GridCells.Fixed(responsive.posterColumns),
        modifier = modifier.fillMaxSize().background(HaloColors.Background).padding(start = responsive.contentInsetStart),
        contentPadding = PaddingValues(
            start = gutter,
            end = gutter,
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + HaloSpacing.Sm,
            // Room under the last row for the floating tab bar, which this page keeps.
            bottom = responsive.bottomContentPadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(responsive.posterGridGap),
        verticalArrangement = Arrangement.spacedBy(responsive.posterGridRowGap),
    ) {
        item(key = "head", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(HaloSpacing.Md)) {
                Row(
                    Modifier.clickable(role = Role.Button, onClick = onBack).padding(vertical = HaloSpacing.Xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(HaloIcons.ChevronLeft, contentDescription = null, tint = HaloColors.Accent, modifier = Modifier.size(20.dp))
                    Text("Back", color = HaloColors.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Md), verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .width(PortraitWidth)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(HaloRadius.Lg))
                            .border(1.dp, HaloColors.GlassBorder, RoundedCornerShape(HaloRadius.Lg)),
                    ) {
                        HaloAsyncImage(
                            url = portrait,
                            contentDescription = shownName,
                            modifier = Modifier.matchParentSize(),
                            failure = { Initials(shownName) },
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (kicker.isNotEmpty()) {
                            Text(kicker.uppercase(), style = monoStyle(11.sp, FontWeight.Bold, HaloColors.AccentLight), letterSpacing = 1.76.sp)
                        }
                        Text(shownName, color = HaloColors.Text, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
                        detail?.person?.biography?.let { Biography(it) }
                    }
                }
            }
        }

        when (val state = load) {
            PersonLoad.Loading, PersonLoad.NobodyByName -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(vertical = HaloSpacing.Xl), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = HaloColors.Accent)
                }
            }
            is PersonLoad.Error -> item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                SearchEmptyState(
                    glyph = HaloIcons.Person,
                    title = state.kind.title,
                    text = state.kind.text,
                    action = if (state.kind == PersonError.Failed) "Try again" to { attempt += 1 } else null,
                )
            }
            is PersonLoad.Loaded -> {
                if (state.detail.partial) {
                    item(key = "partial", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "Still gathering this filmography. Some titles may be missing; open the page again in a moment.",
                            color = HaloColors.Gold,
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }
                if (credits.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        Text("No films or series to show for this person.", color = HaloColors.TextDim, fontSize = 13.5.sp, modifier = Modifier.padding(vertical = HaloSpacing.Lg))
                    }
                } else {
                    item(key = "films-head", span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.padding(top = HaloSpacing.Md), verticalArrangement = Arrangement.spacedBy(HaloSpacing.Sm)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm)) {
                                Text("Films and series", color = HaloColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text("${shown.size}", color = HaloColors.TextFaint, fontSize = 13.sp)
                            }
                            // All and one role say the same thing; chips are worth showing from two roles on.
                            if (filters.size > 2) {
                                Row(horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm)) {
                                    filters.forEach { option -> RoleChip(option.label, option == shownFilter) { filter = option } }
                                }
                            }
                        }
                    }
                    items(shown, key = { "${it.type}:${it.imdbId}" }) { credit ->
                        val meta = MetaPreview(id = credit.imdbId, type = credit.type, name = credit.name, poster = credit.poster, releaseInfo = credit.year)
                        val menus = LocalTitleMenus.current
                        PosterCard(
                            item = PosterItem(
                                key = "${credit.type}:${credit.imdbId}",
                                title = credit.name,
                                posterUrl = credit.poster,
                                subtitle = creditLine(credit),
                                softPlaceholder = true,
                            ),
                            onClick = { onOpenDetail(MetaRef(credit.type, credit.imdbId)) },
                            showLabel = true,
                            onLongPress = menus?.let { { it.openPoster(meta) } },
                        )
                    }
                }
            }
        }
        item(key = "end", span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.size(1.dp)) }
    }
}

@Composable
private fun BoxScope.Initials(name: String) {
    val initials = name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }
    Text(initials, color = HaloColors.TextFaint, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
}

@Composable
private fun RoleChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (on) HaloColors.Text else HaloColors.TextDim,
        fontSize = 13.sp,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(HaloRadius.Pill))
            .background(if (on) HaloColors.FieldFill else HaloColors.Background)
            .border(1.dp, if (on) HaloColors.GlassBorder else HaloColors.Hairline, RoundedCornerShape(HaloRadius.Pill))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** Four lines until Read more opens it; Read more shows only while the text is cut short, or open. */
@Composable
private fun Biography(text: String) {
    var open by remember(text) { mutableStateOf(false) }
    var clipped by remember(text) { mutableStateOf(false) }
    Text(
        text = text,
        color = HaloColors.TextMeta,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
        maxLines = if (open) Int.MAX_VALUE else 4,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout -> if (!open) clipped = layout.hasVisualOverflow },
    )
    if (clipped || open) {
        Text(
            text = if (open) "Show less" else "Read more",
            color = HaloColors.Accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(role = Role.Button, onClick = { open = !open }).padding(vertical = 2.dp),
        )
    }
}
