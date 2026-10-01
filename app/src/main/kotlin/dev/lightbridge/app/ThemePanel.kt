// SPDX-License-Identifier: MIT
package dev.lightbridge.app

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.lightbridge.app.AppIcons
import dev.youniversal.theme.YouniversalBackgroundStyle
import dev.youniversal.theme.YouniversalCard
import dev.youniversal.theme.YouniversalContrast
import dev.youniversal.theme.YouniversalDivider
import dev.youniversal.theme.YouniversalMetrics
import dev.youniversal.theme.YouniversalSectionHeader
import dev.youniversal.theme.YouniversalSegmentedControl
import dev.youniversal.theme.YouniversalSettingRow
import dev.youniversal.theme.YouniversalSlider
import dev.youniversal.theme.YouniversalSwitch
import dev.youniversal.theme.YouniversalThemeState
import dev.youniversal.theme.youniversalReadableContentColor

/** The accent seeds offered by the showcase. `Unspecified` means "use the Youniversal accent". */
private val AccentSeeds =
    listOf(
        "Brand" to Color.Unspecified,
        "Teal" to Color(0xFF0F9C8E),
        "Rose" to Color(0xFFC2185B),
        "Forest" to Color(0xFF1B7F3B),
        "Amber" to Color(0xFFB26A00),
        "Violet" to Color(0xFF6A3FB5),
    )

/**
 * Every theme setting in one card.
 *
 * Each control writes straight to [YouniversalThemeState], which is the same object the activity
 * themed itself with — so a change repaints the app immediately and survives a process restart.
 */
@Composable
internal fun ThemePanel(
    state: YouniversalThemeState,
    modifier: Modifier = Modifier,
) {
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val seedActive = state.accentSeed != Color.Unspecified
    val scheme = MaterialTheme.colorScheme

    YouniversalCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(YouniversalMetrics.SpacingXl),
            verticalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingMd),
        ) {
            YouniversalSectionHeader(
                title = "Theme",
                subtitle = "Live, and saved between launches.",
                action = {
                    Text(
                        text = "Reset",
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.primary,
                        modifier =
                            Modifier
                                .clip(MaterialTheme.shapes.small)
                                .clickable(onClick = state::reset)
                                .padding(
                                    horizontal = YouniversalMetrics.SpacingSm,
                                    vertical = YouniversalMetrics.SpacingXs,
                                ),
                    )
                },
            )

            YouniversalSettingRow(
                title = "Youniversal",
                subtitle =
                    if (state.enabled) "Applied to the whole app"
                    else "Off — the app falls back to baseline Material 3",
                leadingIcon = AppIcons.Mark,
                trailing = {
                    YouniversalSwitch(checked = state.enabled, onCheckedChange = state::setEnabled)
                },
            )

            YouniversalDivider()

            Text(
                text = "Background",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
            )
            YouniversalSegmentedControl(
                options = YouniversalBackgroundStyle.entries.toList(),
                selected = state.backgroundStyle,
                onOptionSelected = state::setBackgroundStyle,
                modifier = Modifier.fillMaxWidth(),
            ) { option, _ ->
                Text(text = option.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }

            Text(
                text = "Contrast",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
            )
            YouniversalSegmentedControl(
                options = YouniversalContrast.entries.toList(),
                selected = state.contrast,
                onOptionSelected = state::setContrast,
                modifier = Modifier.fillMaxWidth(),
            ) { option, _ ->
                Text(text = option.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }

            YouniversalSettingRow(
                title = "Material You",
                subtitle =
                    when {
                        !dynamicAvailable -> "Requires Android 12 or newer"
                        seedActive -> "Paused while an accent seed is active"
                        else -> "Derive the palette from the wallpaper"
                    },
                leadingIcon = AppIcons.Droplet,
                enabled = dynamicAvailable,
                trailing = {
                    YouniversalSwitch(
                        checked = state.dynamicColor,
                        onCheckedChange = state::setDynamicColor,
                        enabled = dynamicAvailable,
                    )
                },
            )

            Text(
                text = "Accent seed",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                maxItemsInEachRow = 3,
            ) {
                AccentSeeds.forEach { (name, color) ->
                    SeedSwatch(
                        name = name,
                        color = color,
                        selected = state.accentSeed == color,
                        onClick = { state.setAccentSeed(color) },
                    )
                }
            }
            Text(
                text =
                    "A seed generates all 48 color roles from one color, so a brand accent works on " +
                        "any Android version — no wallpaper required.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )

            YouniversalDivider()

            YouniversalSettingRow(
                title = "Corner radius",
                subtitle = "%.0f%% of the default scale".format(state.cornerScale * 100f),
            )
            YouniversalSlider(
                value = state.cornerScale,
                onValueChange = state::setCornerScale,
                valueRange = 0f..2f,
            )

            YouniversalSettingRow(
                title = "Text size",
                subtitle = "%.0f%% of the default scale".format(state.fontScale * 100f),
            )
            YouniversalSlider(
                value = state.fontScale,
                onValueChange = state::setFontScale,
                valueRange = 0.8f..1.6f,
            )

            YouniversalSettingRow(
                title = "Animate theme changes",
                subtitle = "Morphs every color role instead of snapping",
                trailing = {
                    YouniversalSwitch(
                        checked = state.animateTransitions,
                        onCheckedChange = state::setAnimateTransitions,
                    )
                },
            )
        }
    }
}

@Composable
private fun SeedSwatch(
    name: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingXs),
    ) {
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (color == Color.Unspecified) scheme.surfaceContainerHighest else color)
                    .then(
                        if (selected) {
                            Modifier.border(width = 2.dp, color = scheme.onSurface, shape = CircleShape)
                        } else {
                            Modifier.border(
                                width = 1.dp,
                                color = scheme.outlineVariant,
                                shape = CircleShape,
                            )
                        },
                    )
                    .clickable(onClick = onClick)
                                .semantics { contentDescription = "$name accent" },
            contentAlignment = Alignment.Center,
        ) {
            when {
                color == Color.Unspecified ->
                    Text(
                        text = "Y",
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurfaceVariant,
                    )

                selected ->
                    Icon(
                        imageVector = AppIcons.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = color.youniversalReadableContentColor(),
                    )
            }
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
