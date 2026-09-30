package app.centsible.feature.budget

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.Appearance
import app.centsible.core.model.Category
import app.centsible.core.model.CategoryGroup
import app.centsible.core.testing.SampleHousehold
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class CategoriesScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val groups = SampleHousehold.budgetMonth.groups.filter { !it.isIncome }.map { g ->
        CategoryGroup(g.id, g.name, g.isIncome, g.hidden, g.categories.map { Category(it.id, it.name, g.id, g.isIncome, it.hidden) })
    }
    private val looks = mapOf(
        groups[0].id.raw to Appearance(0xFFB79CE8),
        groups[1].categories[0].id.raw to Appearance(0xFFEFA3C1, "🥑"),
    )

    @Test fun categories_list() {
        compose.setContent { CentsibleTheme(darkTheme = false) { CategoryManagerScreen(CategoryManagerUiState(Loadable.Ready(groups), looks, canEdit = true), CategoryManagerActions()) } }
        compose.onRoot().captureRoboImage("screenshots/categories_list.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }
}
