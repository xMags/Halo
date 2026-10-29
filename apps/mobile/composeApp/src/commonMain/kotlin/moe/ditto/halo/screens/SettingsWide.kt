package moe.ditto.halo.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import moe.ditto.halo.resources.Res
import moe.ditto.halo.resources.halo_mark
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.ResponsiveInfo
import moe.ditto.halo.ui.monoStyle
import moe.ditto.halo.ui.rememberResponsive
import org.jetbrains.compose.resources.painterResource

/*
 * Settings as the desktop app lays it out, for a tablet held sideways: one
 * scrolling form beside a fixed rail. The rail does not switch pages; it
 * lights the section being read and jumps to one when tapped. Measurements
 * follow the desktop's stylesheet (`settings.css`, `tokens.css`).
 */

/** Whether Settings gets the desktop's layout instead of the phone's column. */
internal fun usesWideSettings(responsive: ResponsiveInfo): Boolean = responsive.isTablet && responsive.isLandscape

/**
 * How far below the top of the form a section has to reach before it counts
 * as the one being read, as a fraction of the visible height. A band rather
 * than the edge itself, so a section boundary resting on the top of the view
 * does not flip the selection back and forth. The desktop's figure.
 */
internal const val RailAnchorFraction = 0.25f

/**
 * Which rail entry is lit. [sectionTops] holds each section's top edge from
 * the top of the visible form, in order, negative once it has scrolled past.
 * The active entry is the last one to have reached [anchor]; at the end of the
 * form the last section wins even though it never reaches the anchor, because
 * the content ran out first. The desktop's rule, from the native app.
 */
internal fun activeSettingsSection(sectionTops: List<Float>, anchor: Float, atEnd: Boolean): Int {
    if (sectionTops.isEmpty()) return 0
    if (atEnd) return sectionTops.lastIndex
    var active = 0
    sectionTops.forEachIndexed { index, top -> if (top <= anchor) active = index }
    return active
}

private val RailWidth = 260.dp
private val ColumnGap = 44.dp
private val PagePadding = 24.dp

/**
 * The form's widest. Between the desktop's default (664) and its maximum
 * (1000): the full desktop width read as too wide on a tablet, where setting
 * rows stretched so far their values sat a long way from their labels.
 */
private val FormMaxWidth = 800.dp
private val PageMaxWidth = PagePadding + RailWidth + ColumnGap + FormMaxWidth + PagePadding

private val RailEntryShape = RoundedCornerShape(6.dp)
private val AboutCardShape = RoundedCornerShape(8.dp)

private class RailEntry(val section: SettingsSection, val label: String, val hint: String)

private fun railEntry(section: SettingsSection, signedIn: Boolean): RailEntry = when (section) {
    SettingsSection.Addons -> RailEntry(section, "Addons", "Catalogs, streams, subtitles")
    SettingsSection.Home -> RailEntry(section, "Home", "Language rows")
    SettingsSection.Playback -> RailEntry(section, "Playback", "Languages, autoplay")
    SettingsSection.Account -> if (signedIn) {
        RailEntry(section, "Server & account", "Sync, identity, latency")
    } else {
        RailEntry(section, "Account", "Sign in to sync")
    }
    SettingsSection.Developer -> RailEntry(section, "Developer", "Debug gate")
}

@Composable
internal fun WideSettings(blocks: List<SettingsBlock>, signedIn: Boolean, modifier: Modifier = Modifier) {
    val responsive = rememberResponsive()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // Where each section begins in the list; only the sections the form has.
    // Keyed on the shape of the form, not the list itself, which is rebuilt
    // with new content on every recomposition.
    val plan = blocks.map { it.starts }
    val starts = remember(plan) { plan.mapIndexedNotNull { index, section -> section?.let { it to index } } }
    val entries = remember(starts, signedIn) { starts.map { (section, _) -> railEntry(section, signedIn) } }

    // A section just jumped to stays lit until the reader scrolls for
    // themselves: near the end of the form it cannot reach the top, and the
    // scroll rule alone would light the last section instead of the one tapped.
    var jumped by remember { mutableStateOf<SettingsSection?>(null) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) jumped = null }
    }
    val reading by remember(listState, starts) {
        derivedStateOf { sectionBeingRead(listState, starts) }
    }
    val active = jumped ?: reading

    Box(modifier.fillMaxSize().padding(start = responsive.contentInsetStart), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier.fillMaxHeight().widthIn(max = PageMaxWidth).fillMaxWidth().padding(horizontal = PagePadding),
            horizontalArrangement = Arrangement.spacedBy(ColumnGap),
        ) {
            SettingsRail(
                entries = entries,
                active = active,
                onSelect = { section ->
                    val index = starts.firstOrNull { it.first == section }?.second ?: return@SettingsRail
                    jumped = section
                    // An immediate jump, as the desktop's rail makes.
                    scope.launch { listState.scrollToItem(index) }
                },
                modifier = Modifier.width(RailWidth).fillMaxHeight().padding(top = top + 8.dp),
            )
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                state = listState,
                contentPadding = PaddingValues(top = top + 8.dp, bottom = responsive.bottomContentPadding + 24.dp),
            ) {
                items(items = blocks, key = { it.key }) { it.content() }
            }
        }
    }
}

/** The section the reader is in, from where the list has scrolled. */
private fun sectionBeingRead(listState: LazyListState, starts: List<Pair<SettingsSection, Int>>): SettingsSection? {
    if (starts.isEmpty()) return null
    val info = listState.layoutInfo
    val visible = info.visibleItemsInfo
    val first = visible.firstOrNull() ?: return starts.first().first
    val tops = starts.map { (_, index) ->
        visible.firstOrNull { it.index == index }?.offset?.toFloat()
            ?: if (index < first.index) Float.NEGATIVE_INFINITY else Float.POSITIVE_INFINITY
    }
    val height = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    // Only a form that can scroll counts as scrolled to its end; otherwise a
    // form short enough to fit would sit on its last section for good.
    val atEnd = !listState.canScrollForward && listState.canScrollBackward
    return starts[activeSettingsSection(tops, height * RailAnchorFraction, atEnd)].first
}

@Composable
private fun SettingsRail(
    entries: List<RailEntry>,
    active: SettingsSection?,
    onSelect: (SettingsSection) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        Text(
            text = "Settings",
            color = HaloColors.Text,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.3).sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            entries.forEach { entry ->
                val lit = entry.section == active
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RailEntryShape)
                        .background(if (lit) HaloColors.Accent.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable(role = Role.Tab, onClickLabel = "Go to ${entry.label}") { onSelect(entry.section) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(entry.label, color = if (lit) HaloColors.Accent else HaloColors.Text, fontSize = 15.sp)
                    Text(entry.hint, color = HaloColors.TextDim, fontSize = 13.sp)
                }
            }
        }
        AboutCard(Modifier.padding(top = 18.dp))
    }
}

/** Who made it and under what terms, as the desktop's rail says under its entries. */
@Composable
private fun AboutCard(modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(AboutCardShape)
            .background(HaloColors.Glass)
            .border(1.dp, HaloColors.GlassBorder, AboutCardShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(
                painter = painterResource(Res.drawable.halo_mark),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(20.dp),
            )
            Text("Halo", color = HaloColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.GlassBorder))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("© 2026 Last Projects & Shy", style = monoStyle(11.5.sp, color = HaloColors.TextDim))
            Text("GPL-3.0 license", style = monoStyle(11.5.sp, color = HaloColors.TextDim))
        }
    }
}
