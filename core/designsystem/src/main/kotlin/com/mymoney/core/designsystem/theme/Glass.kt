package com.mymoney.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize

/**
 * "Liquid glass" look (docs/06 §6.9): a soft ambient background, translucent frosted surfaces with a light edge,
 * and real backdrop blur for the bars that content scrolls under (Android 12+, RenderEffect). When [enabled] is
 * false (setting "Glass effects" off) every surface is solid, as in the classic theme.
 */
@Immutable
data class GlassStyle(
    val enabled: Boolean,
    val dark: Boolean,
) {
    /** Card fill: translucent enough to show the ambient colors, opaque enough for 4.5:1 text contrast. */
    val cardTint: Color get() = if (!enabled) (if (dark) Color(0xFF151A19) else Color(0xFFF2F5F3)) else if (dark) Color(0xFF1A2321).copy(alpha = 0.62f) else Color.White.copy(alpha = 0.66f)

    /** Bar fill drawn over the blurred backdrop. */
    val barTint: Color get() = if (dark) Color(0xFF0E1513).copy(alpha = if (blurAvailable) 0.36f else 0.94f) else Color.White.copy(alpha = if (blurAvailable) 0.40f else 0.95f)

    /** Glossy highlight across the top of bars, like light on a curved glass edge. */
    val sheen: Brush get() = Brush.verticalGradient(
        if (dark) listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0f))
        else listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0f)),
    )

    /** Light-catching edge: bright at the top-left, fading out. */
    val edge: Brush get() = Brush.linearGradient(
        if (dark) listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.10f))
        else listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.70f)),
    )

    val shadow: Color get() = if (dark) Color.Black.copy(alpha = 0.45f) else Color(0xFF0F3D35).copy(alpha = 0.10f)

    val blurAvailable: Boolean get() = enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}

val LocalGlass = staticCompositionLocalOf { GlassStyle(enabled = false, dark = false) }

/**
 * Soft, static colour field behind every screen (no animation: zero cost after the first frame).
 * Solid background when glass is off.
 */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    val glass = LocalGlass.current
    val base = if (glass.dark) Color(0xFF0A100F) else Color(0xFFF3F7F6)
    Canvas(modifier.fillMaxSize()) {
        drawRect(base)
        if (!glass.enabled) return@Canvas
        val w = size.width
        val h = size.height
        val r = maxOf(w, h)
        fun blob(cx: Float, cy: Float, radius: Float, color: Color) =
            drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), Offset(cx, cy), radius), radius, Offset(cx, cy))
        if (glass.dark) {
            blob(w * 0.05f, h * 0.08f, r * 0.62f, Color(0xFF0E5A4C).copy(alpha = 0.55f))
            blob(w * 1.0f, h * 0.35f, r * 0.55f, Color(0xFF243A78).copy(alpha = 0.45f))
            blob(w * 0.25f, h * 0.95f, r * 0.60f, Color(0xFF3B2A6B).copy(alpha = 0.40f))
        } else {
            blob(w * 0.05f, h * 0.06f, r * 0.62f, Color(0xFFBDEFD9).copy(alpha = 0.95f))
            blob(w * 1.0f, h * 0.32f, r * 0.55f, Color(0xFFC9DAFF).copy(alpha = 0.85f))
            blob(w * 0.2f, h * 0.98f, r * 0.62f, Color(0xFFE6D9FF).copy(alpha = 0.85f))
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Backdrop blur: a source records what it draws; glass surfaces draw that recording blurred behind them.
// ------------------------------------------------------------------------------------------------

/** Holder for the recorded backdrop. Create with [rememberBackdrop]. */
class Backdrop internal constructor() {
    internal var layer: GraphicsLayer? = null
    internal var origin: Offset = Offset.Zero
    internal val listeners = LinkedHashSet<() -> Unit>()
}

@Composable
fun rememberBackdrop(): Backdrop = androidx.compose.runtime.remember { Backdrop() }

/** Records this node's content as the backdrop (drawn normally as well). */
fun Modifier.backdropSource(backdrop: Backdrop): Modifier = this then BackdropSourceElement(backdrop)

private data class BackdropSourceElement(val backdrop: Backdrop) : ModifierNodeElement<BackdropSourceNode>() {
    override fun create() = BackdropSourceNode(backdrop)
    override fun update(node: BackdropSourceNode) { node.backdrop = backdrop }
    override fun InspectorInfo.inspectableProperties() { name = "backdropSource" }
}

private class BackdropSourceNode(var backdrop: Backdrop) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var layer: GraphicsLayer? = null

    override fun onAttach() {
        layer = requireGraphicsContext().createGraphicsLayer().also { backdrop.layer = it }
    }

    override fun onDetach() {
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        if (backdrop.layer === layer) backdrop.layer = null
        layer = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        backdrop.origin = coordinates.positionInRoot()
    }

    override fun ContentDrawScope.draw() {
        val l = layer ?: return drawContent()
        l.record { this@draw.drawContent() }
        drawLayer(l)
        // Glass surfaces above must redraw with the new content (e.g. while scrolling).
        backdrop.listeners.forEach { it() }
    }
}

/**
 * Frosted glass behind this node: blurred [backdrop] (when available), [tint], and a light edge in [shape].
 * Without a backdrop or below Android 12 it falls back to a more opaque tint.
 */
fun Modifier.glass(
    style: GlassStyle,
    shape: Shape,
    tint: Color = style.barTint,
    backdrop: Backdrop? = null,
    blur: Dp = 28.dp,
    /** Light edge around the whole shape (cards, floating bar); off for edge-to-edge bars. */
    edge: Boolean = true,
    sheen: Boolean = false,
): Modifier = this then GlassElement(style, shape, tint, backdrop, blur, edge, sheen)

private data class GlassElement(
    val style: GlassStyle,
    val shape: Shape,
    val tint: Color,
    val backdrop: Backdrop?,
    val blur: Dp,
    val edge: Boolean,
    val sheen: Boolean,
) : ModifierNodeElement<GlassNode>() {
    override fun create() = GlassNode(style, shape, tint, backdrop, blur, edge, sheen)
    override fun update(node: GlassNode) = node.update(style, shape, tint, backdrop, blur, edge, sheen)
    override fun InspectorInfo.inspectableProperties() { name = "glass" }
}

private class GlassNode(
    var style: GlassStyle,
    var shape: Shape,
    var tint: Color,
    var backdrop: Backdrop?,
    var blur: Dp,
    var edge: Boolean,
    var sheen: Boolean,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var layer: GraphicsLayer? = null
    private var position = Offset.Zero
    private val invalidate: () -> Unit = { if (isAttached) invalidateDraw() }

    fun update(style: GlassStyle, shape: Shape, tint: Color, backdrop: Backdrop?, blur: Dp, edge: Boolean, sheen: Boolean) {
        this.edge = edge; this.sheen = sheen
        if (this.backdrop !== backdrop) {
            this.backdrop?.listeners?.remove(invalidate)
            backdrop?.listeners?.add(invalidate)
        }
        this.style = style; this.shape = shape; this.tint = tint; this.backdrop = backdrop; this.blur = blur
        invalidateDraw()
    }

    override fun onAttach() {
        backdrop?.listeners?.add(invalidate)
        if (style.blurAvailable) layer = requireGraphicsContext().createGraphicsLayer()
    }

    override fun onDetach() {
        backdrop?.listeners?.remove(invalidate)
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        position = coordinates.positionInRoot()
    }

    override fun ContentDrawScope.draw() {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply { addOutline(outline) }
        val source = backdrop?.layer
        val l = layer
        if (style.blurAvailable && source != null && l != null) {
            val px = blur.toPx()
            l.renderEffect = BlurEffect(px, px, TileMode.Clamp)
            // Slightly richer colours through the glass, like iOS/Telegram.
            l.colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.6f) })
            val dx = backdrop!!.origin.x - position.x
            val dy = backdrop!!.origin.y - position.y
            l.record(size.toIntSize()) { translate(dx, dy) { drawLayer(source) } }
            clipPath(path) { drawLayer(l) }
        }
        drawPath(path, tint)
        if (style.enabled && sheen) drawPath(path, style.sheen)
        if (style.enabled && edge) drawPath(path, style.edge, style = Stroke(width = 1.dp.toPx()))
        drawContent()
    }
}
