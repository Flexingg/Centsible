package app.centsible.core.ui.chart

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.designsystem.theme.rememberMaterialExpressiveTokens
import app.centsible.core.domain.ChartWidgetConfig
import app.centsible.core.domain.Glanceable
import app.centsible.core.domain.WidgetTheme
import java.time.LocalDate

/**
 * Shared live chart card used by home-screen widget previews, Settings → Widgets & charts,
 * and custom charts pinned to the in-app Home dashboard.
 */
@Composable
fun ChartPreviewCard(
    config: ChartWidgetConfig,
    data: Glanceable?,
    today: LocalDate,
    height: Dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    compact: Boolean = false,
    widgetTheme: WidgetTheme = WidgetTheme.Classic,
) {
    val effectiveTheme = config.theme ?: widgetTheme
    if (effectiveTheme == WidgetTheme.MaterialExpressive) {
        ExpressiveChartPreviewCard(config, data, today, height, modifier, onClick, compact)
        return
    }
    val colors = CentsibleTheme.colors
    val density = LocalDensity.current.density
    val art = remember(colors, density) { ChartArt(colors, density) }
    val face = data?.let { ChartFaces.face(config, it, today, art) }
    Column(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.card)
            .border(1.dp, colors.border, RoundedCornerShape(24.dp))
            .let { m -> onClick?.let { m.clickable(onClick = it) } ?: m }
            .padding(16.dp),
    ) {
        if (face == null) {
            Text(config.title?.takeIf { it.isNotBlank() } ?: config.kind.title, style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
            Text("Open the app's Home once to fill the preview.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
            return@Column
        }
        Text(face.title, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val draw = face.draw
        if (draw == null) {
            Text(face.emptyMessage.orEmpty(), style = MaterialTheme.typography.bodySmall, color = colors.textTertiary, modifier = Modifier.padding(top = 6.dp))
            return@Column
        }
        val tone = when (face.tone) {
            ChartFace.Tone.Good -> colors.positive
            ChartFace.Tone.Bad -> colors.negative
            ChartFace.Tone.Neutral -> colors.textSecondary
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            if (face.square) {
                val ring = minOf(maxHeight, maxWidth * 0.45f)
                val ringPx = with(LocalDensity.current) { ring.toPx() }.toInt()
                val bmp = remember(face, ringPx, colors) { draw(art, ringPx, ringPx) }
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Image(bmp.asImageBitmap(), contentDescription = "${face.title}: ${face.headline}", modifier = Modifier.size(ring))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(face.headline, fontSize = if (compact) 16.sp else 22.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary, maxLines = 1)
                        Text(face.subtitle, style = MaterialTheme.typography.labelSmall, color = tone, maxLines = 2)
                        if (!compact) face.legend.take(4).forEach { item ->
                            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(item.color, CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text(item.label, style = MaterialTheme.typography.labelSmall, color = colors.textPrimary, maxLines = 1, modifier = Modifier.weight(1f))
                                Text(item.value, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Text(face.headline, fontSize = if (compact) 16.sp else 22.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary, maxLines = 1)
                    Text(face.subtitle, style = MaterialTheme.typography.labelSmall, color = tone, maxLines = 1)
                    if (face.legend.isNotEmpty()) {
                        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            face.legend.forEach { item ->
                                Box(Modifier.size(7.dp).background(item.color, CircleShape))
                                Text(" ${item.label}   ", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                        val cw = with(LocalDensity.current) { maxWidth.toPx() }.toInt().coerceAtLeast(40)
                        val ch = with(LocalDensity.current) { maxHeight.toPx() }.toInt().coerceAtLeast(30)
                        val bmp = remember(face, cw, ch, colors) { draw(art, cw, ch) }
                        Image(bmp.asImageBitmap(), contentDescription = "${face.title}: ${face.headline}, ${face.subtitle}", modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpressiveChartPreviewCard(
    config: ChartWidgetConfig,
    data: Glanceable?,
    today: LocalDate,
    height: Dp,
    modifier: Modifier,
    onClick: (() -> Unit)?,
    compact: Boolean,
) {
    val m3 = rememberMaterialExpressiveTokens(darkTheme = CentsibleTheme.colors.isDark)
    val density = LocalDensity.current.density
    val art = remember(m3, density) { ChartArt(m3.colors, density, expressive = true, customPalette = m3.palette) }
    val face = data?.let { ChartFaces.face(config, it, today, art) }
    Column(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(28.dp))
            .background(m3.surfaceContainer)
            .let { m -> onClick?.let { m.clickable(onClick = it) } ?: m }
            .padding(if (compact) 12.dp else 16.dp),
    ) {
        val titleText = face?.title ?: config.title?.takeIf { it.isNotBlank() } ?: config.kind.title
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(m3.primaryContainer)
                .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = 3.dp),
        ) {
            Text(
                titleText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = m3.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (face == null) {
            Spacer(Modifier.height(6.dp))
            Text("Open the app's Home once to fill the preview.", style = MaterialTheme.typography.bodySmall, color = m3.colors.textSecondary)
            return@Column
        }
        val draw = face.draw
        if (draw == null) {
            Text(face.emptyMessage.orEmpty(), style = MaterialTheme.typography.bodySmall, color = m3.colors.textSecondary, modifier = Modifier.padding(top = 8.dp))
            return@Column
        }
        val (pillBg, pillFg) = when (face.tone) {
            ChartFace.Tone.Good -> m3.tertiaryContainer to m3.onTertiaryContainer
            ChartFace.Tone.Bad -> m3.errorContainer to m3.onErrorContainer
            ChartFace.Tone.Neutral -> m3.secondaryContainer to m3.onSecondaryContainer
        }
        Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            if (face.square) {
                val ring = minOf(maxHeight, maxWidth * 0.44f)
                val ringPx = with(LocalDensity.current) { ring.toPx() }.toInt().coerceAtLeast(24)
                val bmp = remember(face, ringPx, m3) { draw(art, ringPx, ringPx) }
                Row(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(20.dp))
                        .background(m3.surfaceContainerLow)
                        .padding(if (compact) 8.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(bmp.asImageBitmap(), contentDescription = "${face.title}: ${face.headline}", modifier = Modifier.size(ring))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(face.headline, fontSize = if (compact) 17.sp else 23.sp, fontWeight = FontWeight.ExtraBold, color = m3.colors.textPrimary, maxLines = 1)
                        if (face.subtitle.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Box(Modifier.clip(RoundedCornerShape(50)).background(pillBg).padding(horizontal = 8.dp, vertical = 2.dp)) {
                                Text(face.subtitle, style = MaterialTheme.typography.labelSmall, color = pillFg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (!compact) face.legend.take(3).forEach { item ->
                            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(item.color, CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text(item.label, style = MaterialTheme.typography.labelSmall, color = m3.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(item.value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = m3.colors.textSecondary)
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            face.headline,
                            fontSize = if (compact) 17.sp else 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = m3.colors.textPrimary,
                            maxLines = 1,
                        )
                        if (!compact && face.subtitle.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.clip(RoundedCornerShape(50)).background(pillBg).padding(horizontal = 8.dp, vertical = 2.dp)) {
                                Text(face.subtitle, style = MaterialTheme.typography.labelSmall, color = pillFg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (compact && face.subtitle.isNotBlank()) {
                        Text(face.subtitle, style = MaterialTheme.typography.labelSmall, color = pillFg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!compact && face.legend.isNotEmpty()) {
                        Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            face.legend.forEach { item ->
                                Box(Modifier.size(7.dp).background(item.color, CircleShape))
                                Text(" ${item.label}   ", style = MaterialTheme.typography.labelSmall, color = m3.colors.textSecondary)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    BoxWithConstraints(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(m3.surfaceContainerLow)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        val cw = with(LocalDensity.current) { maxWidth.toPx() }.toInt().coerceAtLeast(40)
                        val ch = with(LocalDensity.current) { maxHeight.toPx() }.toInt().coerceAtLeast(30)
                        val bmp = remember(face, cw, ch, m3) { draw(art, cw, ch) }
                        Image(bmp.asImageBitmap(), contentDescription = "${face.title}: ${face.headline}, ${face.subtitle}", modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}
