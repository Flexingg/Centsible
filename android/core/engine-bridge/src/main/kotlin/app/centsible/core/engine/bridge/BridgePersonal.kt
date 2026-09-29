package app.centsible.core.engine.bridge

import app.centsible.core.domain.PersonalGateway
import app.centsible.core.model.Appearance
import app.centsible.core.model.BudgetId
import app.centsible.core.model.HomeLayout
import app.centsible.core.network.AppearanceBodyDto
import app.centsible.core.network.HomeLayoutDto
import app.centsible.core.network.PersonalApi

class BridgePersonal(private val api: PersonalApi, private val onWrite: () -> Unit) : PersonalGateway {
    override suspend fun homeLayout() = api.home().let { HomeLayout(it.order, it.hidden.toSet()) }

    override suspend fun setHomeLayout(layout: HomeLayout) =
        api.setHome(HomeLayoutDto(layout.order, layout.hidden.toList())).let { HomeLayout(it.order, it.hidden.toSet()) }

    override suspend fun appearance(budget: BudgetId) =
        api.appearance(budget.raw).categories.associate { it.categoryId to Appearance(it.color?.let(::parseColor), it.emoji) }

    override suspend fun setAppearance(budget: BudgetId, id: String, appearance: Appearance) {
        api.setAppearance(budget.raw, id, AppearanceBodyDto(appearance.color?.let(::formatColor), appearance.emoji))
        onWrite()
    }
}

/** "#7FD1A8" → 0xFF7FD1A8 (opaque ARGB). */
internal fun parseColor(hex: String): Long? = hex.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { 0xFF000000L or it }

internal fun formatColor(argb: Long): String = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
