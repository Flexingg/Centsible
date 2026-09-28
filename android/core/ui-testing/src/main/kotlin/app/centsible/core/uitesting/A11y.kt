package app.centsible.core.uitesting

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.SemanticsMatcher
import org.junit.Assert.assertEquals

/**
 * Checks what a screen reader and a thumb meet on a screen:
 * - everything tappable announces something (text, a content description, or a state
 *   description), so TalkBack never says just "button";
 * - tap targets are at least 48dp in both directions (Material's minimum).
 * Run it right after rendering; it looks at every window, dialogs and sheets included.
 */
object A11y {
    private const val MIN_TARGET_DP = 48f

    fun problems(rule: ComposeContentTestRule): List<String> {
        rule.waitForIdle()
        val density = rule.density.density
        val tappable = SemanticsMatcher("is tappable") { n ->
            n.config.getOrNull(SemanticsActions.OnClick) != null && n.config.getOrNull(SemanticsProperties.Disabled) == null
        }
        val nodes = rule.onAllNodes(tappable, useUnmergedTree = false).fetchSemanticsNodes(atLeastOneRootRequired = false)
        // Skip nodes scrolled out of view (clipped to nothing).
        return nodes.filter { it.touchBoundsInRoot.width > 0f && it.touchBoundsInRoot.height > 0f }.flatMap { node ->
            val label = label(node)
            buildList {
                if (label.isBlank()) add("unlabeled ${role(node)} at ${node.boundsInRoot}")
                // Touch bounds, not drawn size: Material widgets draw at 40dp but take touches over 48dp.
                val touch = node.touchBoundsInRoot
                val w = touch.width / density
                val h = touch.height / density
                // Allow a hair under 48 for rounding; anything smaller than 47 is a real miss.
                if (w < MIN_TARGET_DP - 1 || h < MIN_TARGET_DP - 1) {
                    add("small tap target \"${label.ifBlank { role(node) }}\": ${"%.0f".format(w)}x${"%.0f".format(h)}dp")
                }
            }
        }.distinct()
    }

    fun assertOk(rule: ComposeContentTestRule) = assertEquals("Accessibility problems", emptyList<String>(), problems(rule))

    private fun label(node: SemanticsNode): String = listOfNotNull(
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
        node.config.getOrNull(SemanticsProperties.EditableText)?.text,
        node.config.getOrNull(SemanticsProperties.StateDescription),
    ).joinToString(" ").trim()

    private fun role(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Role)?.toString() ?: "element"
}

/** Renders [content] as if the phone's font size were set to [scale] (200% is Android's largest). */
@androidx.compose.runtime.Composable
fun LargeText(scale: Float = 2f, content: @androidx.compose.runtime.Composable () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, scale),
        content = content,
    )
}
