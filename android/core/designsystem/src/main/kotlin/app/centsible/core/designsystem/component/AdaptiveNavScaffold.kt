package app.centsible.core.designsystem.component

import app.centsible.core.designsystem.motion.bounceOnSelect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.theme.CentsibleTheme

data class NavTab(val key: String, val label: String, val icon: ImageVector)

/** Tablets, unfolded foldables and landscape phones: rail on the side, content kept readable. */
const val WIDE_LAYOUT_DP = 600
private val MAX_CONTENT_WIDTH = 840.dp

/**
 * Phone: bottom navigation bar, add button bottom-right. Wide screens: a navigation rail
 * with the add button on top (Material's pattern) and content centered at a readable
 * width instead of cards stretched edge to edge. [selected] null hides the navigation
 * (detail screens).
 */
@Composable
fun AdaptiveNavScaffold(
    tabs: List<NavTab>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    addButton: (@Composable () -> Unit)? = null,
    wide: Boolean = LocalConfiguration.current.screenWidthDp >= WIDE_LAYOUT_DP,
    content: @Composable (PaddingValues) -> Unit,
) {
    val colors = CentsibleTheme.colors
    if (!wide) {
        Scaffold(
            modifier = modifier,
            containerColor = colors.canvas,
            snackbarHost = snackbarHost,
            topBar = topBar,
            floatingActionButton = { addButton?.invoke() },
            bottomBar = {
                if (selected != null) {
                    NavigationBar(containerColor = colors.card) {
                        tabs.forEach { t ->
                            NavigationBarItem(
                                selected = t.key == selected,
                                onClick = { onSelect(t.key) },
                                icon = { Icon(t.icon, contentDescription = null, modifier = Modifier.bounceOnSelect(t.key == selected)) },
                                label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = colors.accent,
                                    selectedTextColor = colors.accent,
                                    indicatorColor = colors.accentSoft,
                                    unselectedIconColor = colors.textTertiary,
                                    unselectedTextColor = colors.textTertiary,
                                ),
                            )
                        }
                    }
                }
            },
            content = content,
        )
        return
    }
    Row(modifier.fillMaxSize()) {
        if (selected != null) {
            NavigationRail(containerColor = colors.card, header = { addButton?.invoke() }) {
                tabs.forEach { t ->
                    NavigationRailItem(
                        selected = t.key == selected,
                        onClick = { onSelect(t.key) },
                        icon = { Icon(t.icon, contentDescription = null, modifier = Modifier.bounceOnSelect(t.key == selected)) },
                        label = { Text(t.label) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = colors.accent,
                            selectedTextColor = colors.accent,
                            indicatorColor = colors.accentSoft,
                            unselectedIconColor = colors.textTertiary,
                            unselectedTextColor = colors.textTertiary,
                        ),
                    )
                }
            }
        }
        Scaffold(containerColor = colors.canvas, snackbarHost = snackbarHost, topBar = topBar) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxWidth()) { content(PaddingValues()) }
            }
        }
    }
}
