package dev.oxype.client.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.oxype.client.ui.BottomTab
import dev.oxype.client.ui.theme.oxypeColors

/**
 * The selection capsule's horizontal offset, counted in **slots**.
 *
 * One slot per tab, so tab `n` of `count` sits `n` slots to the right. Counted in slots
 * and not in capsule widths on purpose: the capsule is inset from its slot by 6dp on each
 * side, and advancing it by its own narrower width leaves a growing gap to the left of the
 * selected item (12dp on the middle tab, 24dp on the last). A negative index (a detail
 * screen, where the bar is hidden) or a bar with no slots keeps it at the left edge rather
 * than letting it fly off.
 */
internal fun bottomBarFraction(index: Int, count: Int): Float = when {
    count <= 0 -> 0f
    index <= 0 -> 0f
    index >= count -> (count - 1).toFloat()
    else -> index.toFloat()
}

/**
 * The same offset, named for what it is where the bar is drawn.
 *
 * `BoxWithConstraints` multiplies it by the measured slot width; keeping the name
 * separate from [bottomBarFraction] means the composable cannot accidentally multiply by
 * the capsule width instead.
 */
internal fun selectionCapsuleOffsetInSlots(index: Int, count: Int): Float =
    bottomBarFraction(index, count)

/**
 * The floating capsule bar for the three top-level sections.
 *
 * The selected item's highlight is a single pill that springs between slots while the
 * icon and label fade from secondary to accent; each slot dips slightly while pressed,
 * because the bar is the only way between sections and the press has to feel
 * acknowledged before the destination's own transition starts.
 *
 * Icons are drawn here as [ImageVector]s instead of pulling in the extended icon pack:
 * three glyphs at one size do not justify a dependency, and the shapes are ours.
 */
@Composable
internal fun OxypeBottomBar(
    selected: BottomTab,
    onSelect: (BottomTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = oxypeColors
    val tabs = BottomTab.entries
    val selectedIndex = tabs.indexOf(selected).coerceAtLeast(0)
    val shape = RoundedCornerShape(22.dp)

    // Slot units, not pixels: the pill is placed from the measured bar width, so a
    // rotation or a split-screen resize re-places it instead of leaving it stranded.
    // `selectionCapsuleOffsetInSlots` keeps an unknown tab (a detail screen) from aiming
    // off the bar.
    val targetSlot = selectionCapsuleOffsetInSlots(selectedIndex, tabs.size)
    val pillSlot = remember { Animatable(targetSlot) }
    LaunchedEffect(targetSlot) {
        pillSlot.animateTo(
            targetValue = targetSlot,
            animationSpec = spring(dampingRatio = 0.72f, stiffness = 600f)
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                horizontal = 12.dp,
                vertical = BottomBarMetrics.BAR_PADDING
            )
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(BottomBarMetrics.BAR_HEIGHT)
                .shadow(elevation = 12.dp, shape = shape, clip = false)
                .clip(shape)
                .background(colors.surface)
                .border(width = 1.dp, color = colors.divider, shape = shape)
        ) {
            val slotWidth = maxWidth / tabs.size
            Box(
                modifier = Modifier
                    .padding(horizontal = 6.dp, vertical = 6.dp)
                    // One *slot* per tab, not one capsule width: the capsule is inset
                    // from its slot, so advancing it by its own width would leave it a
                    // growing gap to the left of the selected item.
                    .offset(x = slotWidth * pillSlot.value)
                    .width(slotWidth - 12.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.accent.copy(alpha = 0.14f))
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                tabs.forEach { tab ->
                    BarItem(
                        tab = tab,
                        selected = tab == selected,
                        onClick = { onSelect(tab) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** One slot: icon above a small label, with a press dip and a colour transition. */
@Composable
private fun BarItem(
    tab: BottomTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = oxypeColors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val tint by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.textSecondary,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "bottom-bar-tint"
    )
    // Scaling with a graphics layer keeps the measured size, so the row cannot reflow
    // while a finger is down.
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "bottom-bar-press"
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null
            )
            .graphicsLayer(scaleX = scale, scaleY = scale),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = tab.icon(),
            contentDescription = tab.label,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = tab.label,
            fontSize = 10.5.sp,
            color = tint,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/** The glyph for each tab, drawn as a stroked path at a 24-unit viewport. */
private fun BottomTab.icon(): ImageVector = when (this) {
    BottomTab.CHATS -> ChatGlyph
    BottomTab.SETTINGS -> SettingsGlyph
    BottomTab.PROFILE -> ProfileGlyph
}

private const val GLYPH_STROKE = 1.8f

/** Builds a stroke-only glyph: every tab icon is an outline at the same weight. */
private fun strokedPath(build: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = GLYPH_STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = build
        )
    }.build()

/** A speech bubble: the conversation list this tab returns to. */
private val ChatGlyph: ImageVector by lazy {
    strokedPath {
        moveTo(21f, 15f)
        arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = -2f, dy1 = 2f)
        horizontalLineTo(7f)
        lineTo(3f, 21f)
        verticalLineTo(5f)
        arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = 2f, dy1 = -2f)
        horizontalLineToRelative(14f)
        arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = 2f, dy1 = 2f)
        close()
    }
}

/** A gear: a hub inside a ring of teeth. */
private val SettingsGlyph: ImageVector by lazy {
    strokedPath {
        moveTo(12f, 9f)
        arcToRelative(3f, 3f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 0f, dy1 = 6f)
        arcToRelative(3f, 3f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 0f, dy1 = -6f)
        close()
        moveTo(19.4f, 15f)
        lineTo(21f, 16.4f)
        lineTo(19f, 19.8f)
        lineTo(17.1f, 18.9f)
        lineTo(15.6f, 19.9f)
        lineTo(15.3f, 22f)
        lineTo(11.3f, 22f)
        lineTo(11f, 19.9f)
        lineTo(9.4f, 18.9f)
        lineTo(7.5f, 19.8f)
        lineTo(5.5f, 16.4f)
        lineTo(7.1f, 15f)
        lineTo(7.1f, 13f)
        lineTo(5.5f, 11.6f)
        lineTo(7.5f, 8.2f)
        lineTo(9.4f, 9.1f)
        lineTo(11f, 8.1f)
        lineTo(11.3f, 6f)
        lineTo(15.3f, 6f)
        lineTo(15.6f, 8.1f)
        lineTo(17.1f, 9.1f)
        lineTo(19f, 8.2f)
        lineTo(21f, 11.6f)
        lineTo(19.4f, 13f)
        close()
    }
}

/** A head and shoulders: the account this tab belongs to. */
private val ProfileGlyph: ImageVector by lazy {
    strokedPath {
        moveTo(12f, 4f)
        arcToRelative(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 0f, dy1 = 8f)
        arcToRelative(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 0f, dy1 = -8f)
        close()
        moveTo(4f, 21f)
        curveToRelative(0f, -4f, 3.6f, -6f, 8f, -6f)
        reflectiveCurveToRelative(8f, 2f, 8f, 6f)
    }
}
