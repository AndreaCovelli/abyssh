package io.github.andreacovelli.ctulssh.ui.screens.Settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.andreacovelli.ctulssh.ui.components.CtulButton
import io.github.andreacovelli.ctulssh.ui.components.CtulButtonVariant
import io.github.andreacovelli.ctulssh.ui.components.CtulSwitch
import io.github.andreacovelli.ctulssh.ui.components.CtulText
import io.github.andreacovelli.ctulssh.ui.components.CtulTextField
import io.github.andreacovelli.ctulssh.ui.theme.CtulColors
import io.github.andreacovelli.ctulssh.ui.theme.CtulTypography
import io.github.andreacovelli.ctulssh.ui.theme.GhosttyTheme
import io.github.andreacovelli.ctulssh.ui.theme.GhosttyThemeRegistry
import io.github.andreacovelli.ctulssh.ui.theme.ThemeMode

@Composable
internal fun ThemeSelectorSection(
    darkThemeName: String,
    onDarkThemeSelected: (String) -> Unit,
    lightThemeName: String,
    onLightThemeSelected: (String) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
) {
    val colors = CtulColors.current
    val typography = CtulTypography.current
    val context = LocalContext.current
    val availableThemes = remember { GhosttyThemeRegistry.availableThemeNames }
    val themeByName = remember(context, availableThemes) {
        availableThemes.associateWith { themeName -> GhosttyThemeRegistry.getTheme(context, themeName) }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        CtulText("── ", style = typography.labelSmall, color = colors.textMuted)
        CtulText("THEME", style = typography.labelSmall, color = colors.textMuted)
        CtulText(" ", style = typography.labelSmall, color = colors.textMuted)
        Box(modifier = Modifier.height(1.dp).background(colors.textMuted).fillMaxWidth())
    }
    Spacer(modifier = Modifier.height(12.dp))

    ThemeModePills(themeMode = themeMode, onThemeModeChanged = onThemeModeChanged)
    Spacer(modifier = Modifier.height(16.dp))

    when (themeMode) {
        ThemeMode.Dark -> SingleThemePicker(
            currentTheme = darkThemeName,
            onThemeSelected = onDarkThemeSelected,
            themeByName = themeByName,
        )
        ThemeMode.Light -> SingleThemePicker(
            currentTheme = lightThemeName,
            onThemeSelected = onLightThemeSelected,
            themeByName = themeByName,
        )
        ThemeMode.System -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CtulText("dark", style = typography.labelSmall, color = colors.textMuted)
            SingleThemePicker(
                currentTheme = darkThemeName,
                onThemeSelected = onDarkThemeSelected,
                themeByName = themeByName,
            )
            Spacer(modifier = Modifier.height(8.dp))
            CtulText("light", style = typography.labelSmall, color = colors.textMuted)
            SingleThemePicker(
                currentTheme = lightThemeName,
                onThemeSelected = onLightThemeSelected,
                themeByName = themeByName,
            )
        }
    }
}

@Composable
private fun ThemeModePills(
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
) {
    val colors = CtulColors.current
    val typography = CtulTypography.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface),
    ) {
        ThemeMode.entries.forEach { mode ->
            val isSelected = mode == themeMode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        color = if (isSelected) colors.accent else Color.Transparent,
                    )
                    .clickable { onThemeModeChanged(mode) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                CtulText(
                    mode.label,
                    style = typography.label,
                    color = if (isSelected) colors.background else colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun SingleThemePicker(
    currentTheme: String,
    onThemeSelected: (String) -> Unit,
    themeByName: Map<String, GhosttyTheme?>,
) {
    val colors = CtulColors.current
    val typography = CtulTypography.current
    var themeQuery by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val filteredThemes = remember(themeByName.keys, themeQuery) {
        val query = themeQuery.trim()
        if (query.isEmpty()) themeByName.keys.toList() else themeByName.keys.filter { it.contains(query, ignoreCase = true) }
    }
    var showPreview by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(expanded) {
        if (expanded && themeQuery.isBlank()) {
            val index = filteredThemes.indexOf(currentTheme)
            if (index >= 0) {
                listState.scrollToItem(index)
            }
        }
    }

    LaunchedEffect(themeQuery) {
        if (themeQuery.isNotBlank()) {
            listState.scrollToItem(0)
        }
    }

    val previewTheme = remember(currentTheme, themeByName) { themeByName[currentTheme] }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CtulText("show preview", style = typography.label, color = colors.textSecondary)
                CtulSwitch(checked = showPreview, onCheckedChange = { showPreview = it })
            }
        }

        if (showPreview && previewTheme != null) ThemePreview(theme = previewTheme, name = currentTheme)

        ThemePickerButton(
            currentTheme = currentTheme,
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            ThemePickerDropdown(
                query = themeQuery,
                onQueryChange = { themeQuery = it },
                filteredThemes = filteredThemes,
                currentTheme = currentTheme,
                onThemeSelected = onThemeSelected,
                themeByName = themeByName,
                listState = listState,
            )
        }
    }
}

// ─── Shared widgets ─────────────────────────────────────────────────────────

@Composable
private fun ThemePickerButton(
    currentTheme: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = CtulColors.current
    val typography = CtulTypography.current
    CtulButton(
        onClick = onToggle,
        variant = CtulButtonVariant.Outlined,
        bracketed = true,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CtulText(currentTheme, style = typography.label)
            CtulText(if (expanded) "▲" else "▼", style = typography.labelSmall, color = colors.textMuted)
        }
    }
}

@Composable
private fun ThemePickerDropdown(
    query: String,
    onQueryChange: (String) -> Unit,
    filteredThemes: List<String>,
    currentTheme: String,
    onThemeSelected: (String) -> Unit,
    themeByName: Map<String, GhosttyTheme?>,
    listState: LazyListState = rememberLazyListState(),
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CtulTextField(
            value = query,
            onValueChange = onQueryChange,
            label = "Search themes",
            placeholder = "Type to filter",
            singleLine = true,
            autoFocus = false,
        )

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
        ) {
            items(filteredThemes, key = { it }) { themeName ->
                ThemePickerRow(
                    themeName = themeName,
                    currentTheme = currentTheme,
                    rowTheme = themeByName[themeName],
                    onThemeSelected = { onThemeSelected(themeName) },
                )
            }
        }
    }
}

@Composable
private fun ThemePickerRow(
    themeName: String,
    currentTheme: String,
    rowTheme: GhosttyTheme?,
    onThemeSelected: () -> Unit,
) {
    val colors = CtulColors.current
    val typography = CtulTypography.current
    val isSelected = themeName == currentTheme

    Box(
        modifier =
            Modifier.fillMaxWidth()
                .background(if (isSelected) colors.surface else colors.surfaceVariant)
                .clickable(onClick = onThemeSelected)
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                if (isSelected) {
                    Box(modifier = Modifier.width(3.dp).height(24.dp).background(colors.accent))
                }
                if (rowTheme != null) PaletteSwatchStrip(theme = rowTheme)
                CtulText(
                    themeName,
                    style = typography.label,
                    color = if (isSelected) colors.accent else colors.textPrimary,
                )
            }
            if (isSelected) CtulText("●", style = typography.label, color = colors.accent)
        }
    }
}

@Composable
private fun ThemePreview(theme: GhosttyTheme, name: String) {
    val colors = CtulColors.current
    val typography = CtulTypography.current

    Column(
        modifier = Modifier.fillMaxWidth().background(colors.surface).padding(1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.width(6.dp).height(6.dp).background(colors.accent),
                )
                CtulText("preview", style = typography.labelSmall, color = colors.textSecondary)
            }
            CtulText(name, style = typography.labelSmall, color = colors.textMuted)
        }

        ThemePreviewBody(theme = theme)
    }
}

@Composable
private fun ThemePreviewBody(theme: GhosttyTheme) {
    val typography = CtulTypography.current
    val mono = typography.body.copy(fontSize = 9.sp, lineHeight = 12.sp)

    Column(
        modifier = Modifier.fillMaxWidth().background(theme.background).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CtulText("user", style = mono, color = theme.palette[2])
            CtulText("@", style = mono, color = theme.foreground)
            CtulText("host", style = mono, color = theme.palette[3])
            CtulText(" ", style = mono, color = theme.foreground)
            CtulText("~/code", style = mono, color = theme.palette[4])
            CtulText(" $ ", style = mono, color = theme.foreground)
            CtulText("ls -la", style = mono, color = theme.foreground)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CtulText("README.md", style = mono, color = theme.foreground)
            CtulText("src/", style = mono, color = theme.palette[4])
            CtulText("build.zig", style = mono, color = theme.palette[2])
        }
        CtulText("error: undefined symbol", style = mono, color = theme.palette[1])
        CtulText("warning: implicit conversion", style = mono, color = theme.palette[3])

        Row(verticalAlignment = Alignment.CenterVertically) {
            CtulText("→ ", style = mono, color = theme.palette[2])
            CtulText("bat ", style = mono, color = theme.palette[4])
            CtulText("main.zig", style = mono.copy(textDecoration = TextDecoration.Underline), color = theme.palette[6])
        }
        BatPreview(theme = theme, style = mono)

        Row(verticalAlignment = Alignment.CenterVertically) {
            CtulText("$ ", style = mono, color = theme.foreground)
            Box(modifier = Modifier.height(10.dp).width(5.dp).background(theme.cursorColor))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            theme.palette.forEach { paletteColor ->
                Box(modifier = Modifier.weight(1f).height(10.dp).background(paletteColor))
            }
        }
    }
}

@Composable
private fun BatPreview(theme: GhosttyTheme, style: TextStyle) {
    val chromeColor = theme.palette[8]
    val rule = "─".repeat(40)

    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        BasicText(text = "───────┬$rule", style = style.copy(color = chromeColor))
        BasicText(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = chromeColor)) { append("       │ ") }
                withStyle(SpanStyle(color = theme.foreground)) { append("File: ") }
                withStyle(SpanStyle(color = theme.foreground, fontWeight = FontWeight.Bold)) { append("main.zig") }
            },
            style = style,
        )
        BasicText(text = "───────┼$rule", style = style.copy(color = chromeColor))
        ZigPreviewLines.forEach { (lineNo, segments) ->
            BasicText(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = chromeColor)) { append(String.format("%4d   │ ", lineNo)) }
                    segments.forEach { seg -> withStyle(SpanStyle(color = seg.role.color(theme))) { append(seg.text) } }
                },
                style = style,
            )
        }
        BasicText(text = "───────┴$rule", style = style.copy(color = chromeColor))
    }
}

private enum class TokenRole { Standard, Keyword, Type, String, Number, Function;
    fun color(theme: GhosttyTheme): Color = when (this) {
        Standard -> theme.foreground
        Keyword -> theme.palette[5]
        Type -> theme.palette[12]
        String -> theme.palette[10]
        Number -> theme.palette[4]
        Function -> theme.palette[2]
    }
}

private data class Seg(val text: String, val role: TokenRole)

private fun s(text: String) = Seg(text, TokenRole.Standard)
private fun k(text: String) = Seg(text, TokenRole.Keyword)
private fun t(text: String) = Seg(text, TokenRole.Type)
private fun str(text: String) = Seg(text, TokenRole.String)
private fun n(text: String) = Seg(text, TokenRole.Number)
private fun fn(text: String) = Seg(text, TokenRole.Function)

private val ZigPreviewLines: List<Pair<Int, List<Seg>>> = listOf(
    1 to listOf(k("const"), s(" std "), k("= @import"), s("("), str("\"std\""), s(");")),
    2 to emptyList(),
    3 to listOf(k("pub "), t("fn "), fn("main"), s("(n: "), t("u32"), s(") "), t("void"), s(" {")),
    4 to listOf(k("    var "), s("i: "), t("u32"), s(" = "), n("0"), s(";")),
    5 to listOf(k("    while "), s("(i < n) : (i "), k("+= "), n("1"), s(") {")),
    6 to listOf(s("        std.debug.print("), str("\"zig"), t("\\n"), str("\""), s(", .{});")),
    7 to listOf(s("    }")),
    8 to listOf(s("}")),
)

private val SwatchIndices = intArrayOf(1, 2, 3, 4, 5, 6)

@Composable
private fun PaletteSwatchStrip(theme: GhosttyTheme) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.background(theme.background).padding(horizontal = 3.dp, vertical = 3.dp),
    ) {
        SwatchIndices.forEach { idx ->
            val color: Color = theme.palette.getOrNull(idx) ?: theme.foreground
            Box(modifier = Modifier.width(8.dp).height(12.dp).background(color))
        }
    }
}
