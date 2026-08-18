package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.api.AddonEntry
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.HaloType

internal val SettingsCardShape = RoundedCornerShape(HaloRadius.Lg)
private val AddonInputShape = RoundedCornerShape(HaloRadius.Sm + 1.dp)

@Composable
internal fun SettingsGroupLabel(label: String) {
    Text(
        text = label.uppercase(),
        style = HaloType.Overline.copy(color = HaloColors.Accent),
        modifier = Modifier.padding(start = HaloSpacing.Xs, top = HaloSpacing.Md, bottom = HaloSpacing.Sm),
    )
}

@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SettingsCardShape)
            .background(HaloColors.Glass)
            .border(1.dp, HaloColors.GlassBorder, SettingsCardShape),
        content = content,
    )
}

@Composable
internal fun SettingsLoadingCard(label: String) {
    SettingsCard {
        Row(
            Modifier.fillMaxWidth().padding(HaloSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
        ) {
            CircularProgressIndicator(color = HaloColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Text(label, style = HaloType.Body.copy(color = HaloColors.TextDim))
        }
    }
}

@Composable
internal fun SettingsStateCard(message: String) {
    SettingsCard {
        Text(
            text = message,
            style = HaloType.Body.copy(color = HaloColors.TextDim),
            modifier = Modifier.padding(HaloSpacing.Md),
        )
    }
}

@Composable
internal fun ErrorBanner(message: String) {
    Text(
        text = message,
        color = HaloColors.Danger,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = HaloSpacing.Sm)
            .clip(RoundedCornerShape(HaloRadius.Md))
            .background(HaloColors.Danger.copy(alpha = 0.1f))
            .border(1.dp, HaloColors.Danger.copy(alpha = 0.25f), RoundedCornerShape(HaloRadius.Md))
            .padding(horizontal = HaloSpacing.Md, vertical = HaloSpacing.Sm + 2.dp),
    )
}

@Composable
internal fun AddonUrlRow(
    value: String,
    label: String,
    enabled: Boolean,
    busy: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(HaloSpacing.Sm + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(color = HaloColors.Text, fontSize = 13.5.sp),
            cursorBrush = SolidColor(HaloColors.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier
                .weight(1f)
                .clip(AddonInputShape)
                .background(Color.White.copy(alpha = 0.06f))
                .semantics { contentDescription = label }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            decorationBox = { input ->
                Box {
                    if (value.isBlank()) Text("https://.../manifest.json", color = HaloColors.TextDim, fontSize = 13.5.sp)
                    input()
                }
            },
        )
        Box(
            modifier = Modifier
                .height(40.dp)
                .width(58.dp)
                .clip(AddonInputShape)
                .background(if (enabled) HaloColors.Accent else HaloColors.Accent.copy(alpha = 0.45f))
                .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onSubmit)
                .semantics { contentDescription = "Add $label" },
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(color = HaloColors.OnAccent, strokeWidth = 2.dp, modifier = Modifier.size(17.dp))
            } else {
                Text("Add", color = HaloColors.OnAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
internal fun AddonRow(
    addon: AddonEntry,
    ownership: AddonOwnership,
    enabled: Boolean,
    busy: Boolean,
    onToggleCatalogs: (AddonEntry, AddonOwnership) -> Unit,
    onRemove: (AddonEntry, AddonOwnership) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = HaloSpacing.Sm + 4.dp, vertical = HaloSpacing.Sm + 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm + 4.dp),
    ) {
        Box(
            Modifier.size(34.dp).clip(AddonInputShape).background(HaloColors.Accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = HaloIcons.SettingsOutline,
                contentDescription = null,
                tint = HaloColors.Accent,
                modifier = Modifier.size(19.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Xs)) {
                Text(
                    text = addon.manifest.name,
                    color = HaloColors.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text("v${addon.manifest.version}", color = HaloColors.TextDim, fontSize = 12.sp)
            }
            addon.manifest.description?.let { description ->
                Text(
                    text = description,
                    color = HaloColors.TextDim,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        if (busy) {
            CircularProgressIndicator(color = HaloColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        } else if (enabled) {
            if (addon.manifest.catalogs.isNotEmpty() || addon.hideCatalogs) {
                Text(
                    text = if (addon.hideCatalogs) "Show" else "Hide",
                    color = if (addon.hideCatalogs) HaloColors.TextDim else HaloColors.Accent,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable(role = Role.Button) { onToggleCatalogs(addon, ownership) }
                        .padding(HaloSpacing.Xs)
                        .semantics {
                            contentDescription = if (addon.hideCatalogs) {
                                "Show ${addon.manifest.name} catalogs"
                            } else {
                                "Hide ${addon.manifest.name} catalogs"
                            }
                        },
                )
            }
            Icon(
                imageVector = HaloIcons.Close,
                contentDescription = "Remove ${addon.manifest.name}",
                tint = HaloColors.TextDim,
                modifier = Modifier
                    .clickable(role = Role.Button) { onRemove(addon, ownership) }
                    .padding(HaloSpacing.Xs)
                    .size(19.dp),
            )
        } else {
            Text("Managed", color = HaloColors.TextDim, fontSize = 11.5.sp)
        }
    }
}

@Composable
internal fun EmptySettingsRow(message: String) {
    Text(
        text = message,
        color = HaloColors.TextDim,
        fontSize = 12.5.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = HaloSpacing.Md, vertical = HaloSpacing.Sm + 4.dp),
    )
}

@Composable
internal fun SettingValueRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = HaloSpacing.Md, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (enabled) HaloColors.Text else HaloColors.TextDim, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = HaloColors.TextDim, fontSize = 14.sp, maxLines = 1)
        Icon(
            imageVector = HaloIcons.ChevronDown,
            contentDescription = null,
            tint = HaloColors.TextDim,
            modifier = Modifier.padding(start = HaloSpacing.Xs).size(15.dp).rotate(-90f),
        )
    }
}

@Composable
internal fun AutoplayRow(enabled: Boolean, interactive: Boolean, busy: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = HaloSpacing.Md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Autoplay next episode",
            color = if (interactive) HaloColors.Text else HaloColors.TextDim,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        if (busy) {
            CircularProgressIndicator(color = HaloColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
        } else {
            HaloSwitch(
                checked = enabled,
                enabled = interactive,
                contentDescription = "Autoplay next episode",
                onCheckedChange = onChange,
            )
        }
    }
}

@Composable
internal fun HaloSwitch(
    checked: Boolean,
    enabled: Boolean,
    contentDescription: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Box(
        modifier = Modifier
            .width(48.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(HaloRadius.Pill))
            .background(
                when {
                    checked && enabled -> HaloColors.Accent
                    checked -> HaloColors.Accent.copy(alpha = 0.45f)
                    else -> Color.White.copy(alpha = 0.16f)
                },
            )
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics { this.contentDescription = contentDescription }
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .size(22.dp)
                .clip(RoundedCornerShape(HaloRadius.Pill))
                .background(Color.White),
        )
    }
}

@Composable
internal fun StaticSettingRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = HaloSpacing.Md, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = HaloColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        // The value fills its half and aligns to the far edge, so it lands on the
        // same right margin as the status dot and the switches in the rows around
        // it. Shrinking the slot to the text instead would strand it mid-row: a
        // Row places weighted children in sequence and leaves the slack at the end.
        Text(
            value,
            color = HaloColors.TextDim,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = HaloSpacing.Md).weight(1f),
        )
    }
}

@Composable
internal fun StatusRow(connected: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = HaloSpacing.Md, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Status", color = HaloColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val color = if (connected) HaloColors.Success else HaloColors.Danger
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(HaloRadius.Pill)).background(color))
            Text(if (connected) "Connected" else "Unavailable", color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
internal fun ActionSettingRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = HaloSpacing.Md, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = HaloColors.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Icon(
            imageVector = HaloIcons.ChevronDown,
            contentDescription = null,
            tint = HaloColors.TextDim,
            modifier = Modifier.size(15.dp).rotate(-90f),
        )
    }
}

@Composable
internal fun SettingsDivider() {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Hairline))
}
