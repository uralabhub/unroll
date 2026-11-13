package app.unroll.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Unroll's visual language: black, white and greys only. Type and hairlines do the work — no
 * gradients, glows, tinted icon bubbles or decorative colour. Data (EXIF, counts, times) is set
 * in monospace, like a camera's top-plate display. Ported from Mono.swift.
 */
@Immutable
data class MonoPalette(
    val bg: Color,
    val raised: Color,
    val ink: Color,
    val dim: Color,
    val faint: Color,
    val line: Color,
    /** Neutral grey surface for tiles. */
    val tile: Color,
    /** Near-black feature surface, same in both modes. */
    val hero: Color,
)

private val Light = MonoPalette(
    bg = Color.White, raised = Color(0xFFF2F2F2), ink = Color(0xFF000000), dim = Color(0xFF6B6B6B),
    faint = Color(0xFFB4B4B4), line = Color(0xFFDADADA), tile = Color(0xFFF4F4F4), hero = Color(0xFF121212),
)
private val Dark = MonoPalette(
    bg = Color.Black, raised = Color(0xFF1C1C1C), ink = Color(0xFFFFFFFF), dim = Color(0xFF9B9B9B),
    faint = Color(0xFF575757), line = Color(0xFF2E2E2E), tile = Color(0xFF1A1A1A), hero = Color(0xFF242424),
)

val LocalMono = staticCompositionLocalOf { Light }

object Mono {
    val c: MonoPalette @Composable get() = LocalMono.current
    val gutter = 20.dp
    val radius = 10.dp
    val mono = FontFamily.Monospace

    /** Small uppercase monospaced label used for section headers and data captions. */
    fun caps(size: TextUnit = 11.sp) = TextStyle(fontFamily = mono, fontWeight = FontWeight.Medium, fontSize = size, letterSpacing = 1.2.sp)
    fun data(size: TextUnit = 15.sp, weight: FontWeight = FontWeight.Normal) = TextStyle(fontFamily = mono, fontWeight = weight, fontSize = size)
}

@Composable
fun UnrollTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) Dark else Light
    // Material components (text fields, switches, progress) resolve to the same greys.
    val scheme = if (dark) darkColorScheme(
        primary = p.ink, onPrimary = p.bg, secondary = p.dim, onSecondary = p.bg, tertiary = p.dim,
        background = p.bg, onBackground = p.ink, surface = p.bg, onSurface = p.ink, surfaceVariant = p.raised,
        onSurfaceVariant = p.dim, outline = p.faint, outlineVariant = p.line, primaryContainer = p.raised,
        onPrimaryContainer = p.ink, secondaryContainer = p.raised, onSecondaryContainer = p.ink,
        surfaceContainer = p.raised, surfaceContainerHigh = p.raised, surfaceContainerLow = p.bg,
        surfaceContainerHighest = p.raised, surfaceContainerLowest = p.bg, error = p.ink, onError = p.bg,
    ) else lightColorScheme(
        primary = p.ink, onPrimary = p.bg, secondary = p.dim, onSecondary = p.bg, tertiary = p.dim,
        background = p.bg, onBackground = p.ink, surface = p.bg, onSurface = p.ink, surfaceVariant = p.raised,
        onSurfaceVariant = p.dim, outline = p.faint, outlineVariant = p.line, primaryContainer = p.raised,
        onPrimaryContainer = p.ink, secondaryContainer = p.raised, onSecondaryContainer = p.ink,
        surfaceContainer = p.raised, surfaceContainerHigh = p.raised, surfaceContainerLow = p.bg,
        surfaceContainerHighest = p.raised, surfaceContainerLowest = p.bg, error = p.ink, onError = p.bg,
    )
    CompositionLocalProvider(LocalMono provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Forces the dark palette for a subtree (shot pager, frame studio), like `.environment(\.colorScheme, .dark)`. */
@Composable
fun DarkSurface(content: @Composable () -> Unit) = UnrollTheme(dark = true, content = content)

// Text

@Composable
fun CapsLabel(text: String, modifier: Modifier = Modifier, color: Color = Mono.c.dim, size: TextUnit = 11.sp) {
    Text(text.uppercase(), modifier, style = Mono.caps(size), color = color, maxLines = 1)
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { 1f.toDp() }
    Box(modifier.fillMaxWidth().height(px).background(Mono.c.line))
}

// Sections and rows

/** A titled block of rows separated by hairlines. No card, no fill. */
@Composable
fun MonoSection(title: String? = null, trailing: String? = null, rows: List<@Composable () -> Unit>) {
    Column {
        if (title != null) {
            Row(Modifier.padding(horizontal = Mono.gutter).padding(bottom = 8.dp)) {
                CapsLabel(title)
                Spacer(Modifier.weight(1f))
                trailing?.let { CapsLabel(it) }
            }
        }
        Hairline()
        rows.forEachIndexed { i, row ->
            row()
            Hairline(if (i == rows.lastIndex) Modifier else Modifier.padding(start = Mono.gutter))
        }
    }
}

/** Standard list row: title, optional detail underneath, optional value on the right, chevron. */
@Composable
fun MonoRow(
    title: String,
    detail: String? = null,
    value: String? = null,
    chevron: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp).padding(horizontal = Mono.gutter, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 16.sp, color = Mono.c.ink)
            detail?.let { Text(it, fontSize = 13.sp, color = Mono.c.dim, maxLines = 3) }
        }
        value?.let { Text(it, style = Mono.data(14.sp), color = Mono.c.dim) }
        trailing()
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = Mono.c.faint)
    }
}

/** Key / value line for EXIF and recipe data. */
@Composable
fun DataRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key.uppercase(), style = Mono.caps(10.sp).copy(letterSpacing = 1.sp), color = Mono.c.dim)
        Spacer(Modifier.width(12.dp).weight(1f))
        Text(value, style = Mono.data(13.sp, FontWeight.Medium), color = Mono.c.ink, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

// Buttons

@Composable
private fun pressed(source: MutableInteractionSource): Boolean = source.collectIsPressedAsState().value

/** Solid ink button (black on light, white on dark). */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val p = pressed(src)
    Box(
        modifier.defaultMinSize(minHeight = 50.dp)
            .background(Mono.c.ink.copy(alpha = if (enabled) (if (p) 0.75f else 1f) else 0.1f), RoundedCornerShape(Mono.radius))
            .clickable(src, null, enabled = enabled && !busy, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Mono.c.bg, strokeWidth = 2.dp)
        else Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = if (enabled) Mono.c.bg else Mono.c.ink.copy(alpha = 0.45f), maxLines = 1)
    }
}

/** Outlined button, same footprint as primary. */
@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val p = pressed(src)
    Box(
        modifier.defaultMinSize(minHeight = 50.dp)
            .background(Mono.c.ink.copy(alpha = if (p) 0.08f else 0f), RoundedCornerShape(Mono.radius))
            .border(1.dp, Mono.c.ink.copy(alpha = if (enabled) 0.35f else 0.12f), RoundedCornerShape(Mono.radius))
            .clickable(src, null, enabled = enabled && !busy, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Mono.c.ink, strokeWidth = 2.dp)
        else Text(text, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = Mono.c.ink.copy(alpha = if (enabled) 1f else 0.3f), maxLines = 1)
    }
}

/** Small capsule button used inline (Start / Stop, Cancel, Open Photos…). */
@Composable
fun OutlineChip(text: String, filled: Boolean = false, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier.alpha(if (pressed(src)) 0.6f else 1f).heightIn(min = 32.dp)
            .background(if (filled) Mono.c.ink else Color.Transparent, CircleShape)
            .border(1.dp, Mono.c.ink.copy(alpha = if (filled) 0f else 0.35f), CircleShape)
            .clickable(src, null, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (filled) Mono.c.bg else Mono.c.ink) }
}

/** On/off chip for option toggles. */
@Composable
fun ChipToggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier.heightIn(min = 32.dp)
            .background(if (on) Mono.c.ink else Color.Transparent, CircleShape)
            .border(1.dp, Mono.c.ink.copy(alpha = if (on) 0f else 0.3f), CircleShape)
            .clickable { onChange(!on) }.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (on) Mono.c.bg else Mono.c.dim, maxLines = 1) }
}

/** Text tabs with an underline for the selected one. Used for filters and pickers. */
@Composable
fun <T> TabStrip(items: List<T>, selection: T, label: (T) -> String, count: ((T) -> Int?)? = null, onSelect: (T) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Mono.gutter),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        for (item in items) {
            val on = item == selection
            Column(Modifier.clickable { onSelect(item) }.padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(label(item), fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) Mono.c.ink else Mono.c.dim)
                    count?.invoke(item)?.let { Text("$it", style = Mono.data(11.sp), color = if (on) Mono.c.ink else Mono.c.dim, modifier = Modifier.padding(bottom = 1.dp)) }
                }
                Spacer(Modifier.height(7.dp))
                Box(Modifier.height(1.5.dp).fillMaxWidth().background(if (on) Mono.c.ink else Color.Transparent))
            }
        }
    }
}

/** Five stars as plain glyphs. Filled = ink, empty = faint. */
@Composable
fun StarRow(rating: Int, size: TextUnit = 11.sp, onTap: ((Int) -> Unit)? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(if (onTap != null) 6.dp else 2.dp)) {
        for (n in 1..5) {
            Text(
                if (n <= rating) "★" else "☆", fontSize = size, color = if (n <= rating) Mono.c.ink else Mono.c.faint,
                modifier = if (onTap != null) Modifier.clickable { onTap(n) }.padding(4.dp) else Modifier,
            )
        }
    }
}

/** Empty / status message: plain type, no illustration. */
@Composable
fun QuietMessage(title: String, message: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Mono.gutter, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Mono.c.ink)
        message?.let { Text(it, fontSize = 15.sp, color = Mono.c.dim) }
    }
}

/** Plain top bar: back arrow, title, actions. Hairline underneath. */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, backIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack, actions: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.background(Mono.c.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                Box(Modifier.size(44.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(backIcon, "Back", tint = Mono.c.ink)
                }
            } else Spacer(Modifier.width(14.dp))
            Text(title, Modifier.weight(1f).padding(horizontal = 6.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Mono.c.ink)
            actions()
            Spacer(Modifier.width(8.dp))
        }
        Hairline()
    }
}

@Composable
fun TextAction(text: String, bold: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text, Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 12.dp),
        fontSize = 16.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        color = if (enabled) Mono.c.ink else Mono.c.faint,
    )
}

/** Pressed state for tiles and cards: a slight sink, no colour. */
@Composable
fun Modifier.tilePress(onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val p by src.collectIsPressedAsState()
    return this.scale(if (p) 0.97f else 1f).alpha(if (p) 0.85f else 1f).clickable(src, null, onClick = onClick)
}

// Glyphs: thin monochrome line icons drawn in code (no icon bubbles, no colour).

enum class GlyphKind { USB, CARD, WIFI, RECIPES, CLOCK, FRAME, MAP, GEAR, VIDEO }

@Composable
fun Glyph(kind: GlyphKind, modifier: Modifier = Modifier.size(22.dp), color: Color = Mono.c.ink) {
    Canvas(modifier) { drawGlyph(kind, color) }
}

private fun DrawScope.drawGlyph(kind: GlyphKind, color: Color) {
    val w = size.width; val h = size.height
    val s = Stroke(width = w * 0.075f, cap = StrokeCap.Round)
    when (kind) {
        GlyphKind.USB -> {
            drawRoundRect(color, Offset(w * 0.3f, h * 0.08f), Size(w * 0.4f, h * 0.34f), CornerRadius(w * 0.05f), style = s)
            drawRoundRect(color, Offset(w * 0.22f, h * 0.42f), Size(w * 0.56f, h * 0.5f), CornerRadius(w * 0.08f), style = s)
            drawLine(color, Offset(w * 0.42f, h * 0.2f), Offset(w * 0.42f, h * 0.28f), s.width, StrokeCap.Round)
            drawLine(color, Offset(w * 0.58f, h * 0.2f), Offset(w * 0.58f, h * 0.28f), s.width, StrokeCap.Round)
        }
        GlyphKind.CARD -> {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.42f, h * 0.08f); lineTo(w * 0.78f, h * 0.08f); lineTo(w * 0.78f, h * 0.92f)
                lineTo(w * 0.22f, h * 0.92f); lineTo(w * 0.22f, h * 0.28f); close()
            }
            drawPath(path, color, style = s)
            for (i in 0..2) drawLine(color, Offset(w * (0.42f + i * 0.11f), h * 0.18f), Offset(w * (0.42f + i * 0.11f), h * 0.3f), s.width, StrokeCap.Round)
        }
        GlyphKind.WIFI -> {
            for (i in 0..2) {
                val r = w * (0.16f + i * 0.17f)
                drawArc(color, 225f, 90f, false, Offset(w / 2 - r, h * 0.78f - r), Size(r * 2, r * 2), style = s)
            }
            drawCircle(color, w * 0.06f, Offset(w / 2, h * 0.78f))
        }
        GlyphKind.RECIPES -> {
            val r = w * 0.24f
            drawCircle(color, r, Offset(w * 0.5f, h * 0.33f), style = s)
            drawCircle(color, r, Offset(w * 0.33f, h * 0.63f), style = s)
            drawCircle(color, r, Offset(w * 0.67f, h * 0.63f), style = s)
        }
        GlyphKind.CLOCK -> {
            drawCircle(color, w * 0.42f, Offset(w / 2, h / 2), style = s)
            drawLine(color, Offset(w / 2, h / 2), Offset(w / 2, h * 0.24f), s.width, StrokeCap.Round)
            drawLine(color, Offset(w / 2, h / 2), Offset(w * 0.7f, h * 0.6f), s.width, StrokeCap.Round)
        }
        GlyphKind.FRAME -> {
            drawRect(color, Offset(w * 0.1f, h * 0.12f), Size(w * 0.8f, h * 0.76f), style = s)
            drawRect(color, Offset(w * 0.24f, h * 0.24f), Size(w * 0.52f, h * 0.38f), style = s)
            drawLine(color, Offset(w * 0.32f, h * 0.74f), Offset(w * 0.68f, h * 0.74f), s.width, StrokeCap.Round)
        }
        GlyphKind.MAP -> {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.12f, h * 0.82f); lineTo(w * 0.36f, h * 0.48f); lineTo(w * 0.56f, h * 0.62f); lineTo(w * 0.86f, h * 0.18f)
            }
            drawPath(path, color, style = s)
            drawCircle(color, w * 0.07f, Offset(w * 0.12f, h * 0.82f))
            drawRect(color, Offset(w * 0.8f, h * 0.12f), Size(w * 0.12f, w * 0.12f))
        }
        GlyphKind.GEAR -> {
            drawCircle(color, w * 0.16f, Offset(w / 2, h / 2), style = s)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val c = Offset(w / 2, h / 2)
                drawLine(
                    color,
                    c + Offset((kotlin.math.cos(a) * w * 0.28f).toFloat(), (kotlin.math.sin(a) * w * 0.28f).toFloat()),
                    c + Offset((kotlin.math.cos(a) * w * 0.42f).toFloat(), (kotlin.math.sin(a) * w * 0.42f).toFloat()),
                    s.width * 1.6f, StrokeCap.Round,
                )
            }
            drawCircle(color, w * 0.3f, Offset(w / 2, h / 2), style = s)
        }
        GlyphKind.VIDEO -> {
            drawRoundRect(color, Offset(w * 0.08f, h * 0.26f), Size(w * 0.6f, h * 0.48f), CornerRadius(w * 0.06f), style = s)
            val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.72f, h * 0.42f); lineTo(w * 0.92f, h * 0.3f); lineTo(w * 0.92f, h * 0.7f); lineTo(w * 0.72f, h * 0.58f); close() }
            drawPath(p, color, style = s)
        }
    }
}

/** Square-ish grey tile: glyph top-left, count or tag top-right, title and caption at the bottom. */
@Composable
fun Tile(glyph: GlyphKind, title: String, caption: String? = null, count: Int = 0, tag: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.tilePress(onClick).heightIn(min = 116.dp).background(Mono.c.tile, RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Glyph(glyph)
            Spacer(Modifier.weight(1f))
            if (tag != null) {
                Text(tag.uppercase(), style = Mono.caps(9.sp).copy(letterSpacing = 1.sp), color = Mono.c.ink,
                    modifier = Modifier.border(BorderStroke(1.dp, Mono.c.ink.copy(alpha = 0.3f)), CircleShape).padding(horizontal = 6.dp, vertical = 3.dp))
            } else if (count > 0) {
                Text("$count", style = Mono.data(13.sp, FontWeight.Medium), color = Mono.c.dim)
            }
        }
        Column(Modifier.padding(top = 18.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Mono.c.ink)
            caption?.let { Text(it, fontSize = 13.sp, color = Mono.c.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp)) }
        }
    }
}

@Composable
fun Spinner(color: Color = Mono.c.dim, size: Dp = 22.dp) = CircularProgressIndicator(Modifier.size(size), color = color, strokeWidth = 2.dp)

