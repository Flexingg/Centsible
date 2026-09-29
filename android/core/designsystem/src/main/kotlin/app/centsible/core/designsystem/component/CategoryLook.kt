package app.centsible.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import app.centsible.core.model.Appearance

/**
 * The household's category colors and emoji (see the bridge's /appearance), by id and by
 * name, for everything that draws a category: avatars, chips, the dial, report bars.
 */
data class CategoryLook(val byId: Map<String, Appearance> = emptyMap(), val byName: Map<String, Appearance> = emptyMap()) {
    fun forName(name: String) = byName[name.lowercase()]
    fun forId(id: String?) = id?.let { byId[it] }

    companion object {
        /** Builds the name index from id → name, so screens that only know names still match. */
        fun of(byId: Map<String, Appearance>, names: Map<String, String>) =
            CategoryLook(byId, byId.mapNotNull { (id, a) -> names[id]?.let { it.lowercase() to a } }.toMap())
    }
}

val LocalCategoryLook = staticCompositionLocalOf { CategoryLook() }

/** The category's emoji: the household's choice, else one picked from the name. */
@Composable
@ReadOnlyComposable
fun categoryEmoji(name: String): String = LocalCategoryLook.current.forName(name)?.emoji ?: CategoryEmoji.forName(name)

/** The category's color, if the household chose one. */
@Composable
@ReadOnlyComposable
fun categoryColor(name: String): Color? = LocalCategoryLook.current.forName(name)?.color?.let { Color(it) }

/** The color chosen for a category or group id, if any. */
@Composable
@ReadOnlyComposable
fun appearanceColor(id: String?): Color? = LocalCategoryLook.current.forId(id)?.color?.let { Color(it) }
