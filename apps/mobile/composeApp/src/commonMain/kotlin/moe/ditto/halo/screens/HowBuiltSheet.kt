package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.browse.MixRow
import moe.ditto.halo.browse.ProviderRow
import moe.ditto.halo.browse.QualityTier
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.SheetScaffold

/** One rule the ranking followed, as the sheet lists it. */
internal class ChoiceRule(val name: String, val value: String)

/** What the list knew when the sheet was opened; the sheet stays still while it is read. */
internal class HowBuiltFacts(val providers: List<ProviderRow>, val mix: List<MixRow>)

/**
 * "How this list was built": who answered, what quality came back, and the
 * settings that chose. It explains the list rather than being part of it, so it
 * opens over the list and leaves it where it was.
 *
 * Sits where every sheet sits (see [SheetScaffold]): the last child of the
 * picker's root box, beside the list it frosts.
 */
// BackHandler is still marked experimental in Compose 1.11; see SourcesRail.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun HowBuiltSheet(
    facts: HowBuiltFacts?,
    rules: List<ChoiceRule>,
    onOpenPlayback: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(enabled = facts != null, onBack = onDismiss)
    // Kept while the sheet slides away, so it does not empty on the way out.
    var shown by remember { mutableStateOf(facts) }
    if (facts != null) shown = facts

    SheetScaffold(
        visible = facts != null,
        onDismiss = onDismiss,
        bottomPadding = HaloSpacing.Lg + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
    ) {
        val current = shown ?: return@SheetScaffold
        Text(
            text = "How this list was built",
            color = HaloColors.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = HaloSpacing.Md, end = HaloSpacing.Md, bottom = HaloSpacing.Md),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HaloSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            ProvidersSection(current.providers)
            MixSection(current.mix)
            RulesSection(
                rules = rules,
                onOpenPlayback = {
                    onDismiss()
                    onOpenPlayback()
                },
            )
        }
    }
}

@Composable
private fun ProvidersSection(providers: List<ProviderRow>) {
    SheetSection("Providers") {
        providers.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (row.answered) SourcesPalette.StatusGreen else SourcesPalette.Faint))
                Text(
                    text = row.name,
                    color = HaloColors.Text,
                    fontSize = 14.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(row.value, color = HaloColors.TextDim, fontSize = 13.5.sp)
            }
        }
    }
}

@Composable
private fun MixSection(mix: List<MixRow>) {
    SheetSection("Quality mix") {
        mix.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(row.tier.label, color = HaloColors.Text, fontSize = 14.5.sp, modifier = Modifier.width(64.dp))
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(SourcesPalette.Track)) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(row.share)
                            .clip(CircleShape)
                            .background(if (row.tier == QualityTier.UltraHd) HaloColors.AccentLight else MixFill),
                    )
                }
                Text(
                    text = "${row.count}",
                    color = HaloColors.TextDim,
                    fontSize = 13.5.sp,
                    modifier = Modifier.width(36.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun RulesSection(rules: List<ChoiceRule>, onOpenPlayback: () -> Unit) {
    SheetSection("How Halo chose") {
        rules.forEach { rule ->
            Row(horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Md)) {
                Text(rule.name, color = HaloColors.Text, fontSize = 14.5.sp, modifier = Modifier.weight(1f))
                Text(rule.value, color = HaloColors.Text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Text(
            text = "Change these in Playback",
            color = HaloColors.AccentLight,
            fontSize = 14.5.sp,
            modifier = Modifier
                .padding(top = 4.dp)
                .clip(SourcesPalette.ControlShape)
                .clickable(role = Role.Button, onClick = onOpenPlayback)
                .padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun SheetSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = HaloColors.TextDim, fontSize = 13.5.sp)
        content()
    }
}

private val MixFill = HaloColors.AccentLight.copy(alpha = 0.55f)
