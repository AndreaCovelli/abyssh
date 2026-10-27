package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.service.terminal.SessionStatus
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt
import io.github.andreacovelli.abyssh.service.terminal.TabSession
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

// Approximates the visible title width before the fixed-size tab ellipsizes it.
private const val TAB_TITLE_COLLISION_PREFIX_LENGTH = 14

/**
 * Human-readable status text for accessibility.
 */
@Composable
internal fun statusLabel(status: SessionStatus): String = stringResource(
    when (status) {
        SessionStatus.Connected -> R.string.session_status_connected
        SessionStatus.Connecting -> R.string.session_status_connecting
        SessionStatus.Reconnecting -> R.string.session_status_reconnecting
        SessionStatus.Disconnected -> R.string.session_status_disconnected
        SessionStatus.Error -> R.string.session_status_error
    },
)

/**
 * Always-visible global top tab strip for strip mode.
 *
 * Shows all active terminal sessions in a compact top strip.
 * The tab list scrolls; primary actions stay pinned on the right.
 */
@Composable
fun TerminalTabStrip(
    tabs: List<TabSession>,
    activeTabId: String?,
    onTabSelected: (String) -> Unit,
    onAddTab: () -> Unit,
    onOpenManager: () -> Unit,
    newTabPicker: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val scrollState = rememberScrollState()
    val trailingActionWidth = if (tabs.isNotEmpty()) 72.dp else 40.dp
    val tabOffsets = remember { mutableStateMapOf<String, Int>() }
    val rowRootLeft = remember { mutableStateOf(0) }
    val titles = tabs.map { tab ->
        val title by remember(tab) {
            tab.sessionState.map { it.title?.takeIf(String::isNotBlank) }
        }.collectAsStateWithLifecycle(initialValue = null)
        title
    }
    val titlePrefixCounts = titles
        .filterNotNull()
        .groupingBy { it.take(TAB_TITLE_COLLISION_PREFIX_LENGTH) }
        .eachCount()

    val activeTabOffset = activeTabId?.let { tabOffsets[it] }

    LaunchedEffect(activeTabId, activeTabOffset) {
        val target = activeTabOffset ?: return@LaunchedEffect
        scrollState.animateScrollTo(target)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceVariant)
            .padding(start = 4.dp, end = 6.dp, top = 3.dp, bottom = 5.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = trailingActionWidth)
                .horizontalScroll(scrollState)
                .onGloballyPositioned { coords ->
                    rowRootLeft.value = coords.localToRoot(Offset.Zero).x.roundToInt()
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEachIndexed { index, tab ->
                val isActive = tab.id == activeTabId
                val alias = terminalTabDisplayLabel(tab)
                val title = titles[index]
                val label = when {
                    title == null -> alias
                    titlePrefixCounts.getValue(title.take(TAB_TITLE_COLLISION_PREFIX_LENGTH)) == 1 -> title
                    else -> "$alias · $title"
                }

                Box(
                    modifier = Modifier
                        .onGloballyPositioned { coords ->
                            tabOffsets[tab.id] = coords.localToRoot(Offset.Zero).x.roundToInt() - rowRootLeft.value + scrollState.value
                        }
                        .semantics { contentDescription = label }
                        .defaultMinSize(minHeight = 32.dp)
                        .widthIn(min = 44.dp, max = 160.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .background(
                            if (isActive) {
                                colors.accent.copy(alpha = 0.16f)
                            } else {
                                Color.Transparent
                            }
                        )
                        .clickable { onTabSelected(tab.id) }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AbyssText(
                        text = label,
                        style = typography.labelSmall,
                        color = if (isActive) colors.accent else colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isActive) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(start = 2.dp, end = 2.dp, bottom = 1.dp)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(colors.accent),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (tabs.isNotEmpty()) {
                AbyssButton(
                    onClick = onOpenManager,
                    modifier = Modifier.defaultMinSize(minHeight = 32.dp, minWidth = 32.dp),
                    variant = AbyssButtonVariant.Ghost,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    contentDescription = stringResource(R.string.tab_strip_all_tabs),
                ) {
                    AbyssText(
                        if (tabs.size > 99) "≡" else "${tabs.size}",
                        style = typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
            }

            Box {
                AbyssButton(
                    onClick = onAddTab,
                    modifier = Modifier.defaultMinSize(minHeight = 32.dp, minWidth = 32.dp),
                    variant = AbyssButtonVariant.Ghost,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    contentDescription = stringResource(R.string.tab_strip_new_connection),
                ) {
                    AbyssText("+", style = typography.label, color = colors.accent)
                }
                newTabPicker()
            }
        }
    }
}
