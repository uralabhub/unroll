package app.unroll.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.unroll.R

/**
 * Unroll's visual language: a dark, neutral photo tool (think Photos, Lightroom, Halide).
 * The photos carry the colour; the chrome is black, white and greys, with one amber accent
 * reserved for progress and live states. System type (Roboto) for words, tabular digits for data.
 * No shadows, no bounce, no decoration that moves on its own.
 */
@Immutable
data class MonoPalette(
    /** Page canvas. */
    val bg: Color,
    /** Grouped surface on the canvas (panels, list sections, bars). */
    val surface: Color,
    /** Neutral grey for controls and placeholders. */
    val tile: Color,
    val ink: Color,
    val dim: Color,
    val faint: Color,
    val line: Color,
    /** The one accent: progress and the live state only. */
    val accent: Color,
) {
    /** Older call sites: same as [tile]. */
    val raised: Color get() = tile
    val hero: Color get() = surface
}

private val Dark = MonoPalette(
    bg = Color(0xFF000000), surface = Color(0xFF171717), tile = Color(0xFF1F1F1F),
    ink = Color(0xFFFFFFFF), dim = Color(0x99FFFFFF), faint = Color(0x4DFFFFFF), line = Color(0x29FFFFFF),
    accent = Color(0xFFFFC745),
)

val LocalMono = staticCompositionLocalOf { Dark }

object Mono {
    val c: MonoPalette @Composable get() = LocalMono.current
    val gutter = 16.dp
    val radius = 12.dp
    val shape = RoundedCornerShape(12.dp)
    val badgeShape = RoundedCornerShape(4.dp)

    /** System font (Roboto). Kept as a name so renderers and text fields can refer to it. */
    val sans: FontFamily = FontFamily.Default

    // Type scale (sp), modelled on the iOS text styles the reference uses.
    val largeTitle = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
    val title = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 22.sp)
    val subhead = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)

    /** Small section label. Sentence case at footnote size: quiet, not shouty. */
    fun caps(size: TextUnit = 13.sp) = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = size)

    /** EXIF values, counts and times: tabular digits so numbers don't jitter. */
    fun data(size: TextUnit = 15.sp, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontWeight = weight, fontSize = size, fontFeatureSettings = "tnum")
}

/** Phosphor icons (MIT), shipped as vector drawables, plus a few Material paths. */
object Ph {
    val camera = R.drawable.ph_camera
    val sd = R.drawable.ph_sim_card
    val wifi = R.drawable.ph_wifi_high
    val recipes = R.drawable.ph_aperture
    val timer = R.drawable.ph_timer
    val frame = R.drawable.ph_frame_corners
    val path = R.drawable.ph_path
    val gear = R.drawable.ph_gear_six
    val video = R.drawable.ph_video_camera
    val lock = R.drawable.ph_lock_simple_fill
    val star = R.drawable.ph_star_fill
    val starOutline = R.drawable.ph_star
    val check = R.drawable.ph_check_bold
    val checkCircle = R.drawable.ph_check_circle_fill
    val back = R.drawable.ph_arrow_left
    val forward = R.drawable.ph_arrow_right_bold
    val close = R.drawable.ph_x
    val share = R.drawable.ph_export
    val more = R.drawable.ph_dots_three_bold
    val play = R.drawable.ph_play_fill
    val stop = R.drawable.ph_stop_fill
    val chevron = R.drawable.ph_caret_right_bold
    val caretDown = R.drawable.ph_caret_down_bold
    val trash = R.drawable.ph_trash
    val pin = R.drawable.ph_map_pin
    val film = R.drawable.ph_film_strip
    val images = R.drawable.ph_images
    val usb = R.drawable.ph_usb
    val plug = R.drawable.ph_plug
    val sliders = R.drawable.ph_sliders_horizontal
    val copy = R.drawable.ph_copy
    val crown = R.drawable.ph_crown_simple
    val qr = R.drawable.ph_qr_code
    val download = R.drawable.ic_download
    val info = R.drawable.ic_info
    val infoFill = R.drawable.ic_info_fill
    val warning = R.drawable.ic_warning
    val eject = R.drawable.ic_eject
    val filter = R.drawable.ic_filter
    val moreVert = R.drawable.ic_more_vert
    val retry = R.drawable.ic_retry
    val walk = R.drawable.ic_walk
}

@Composable
fun ph(@DrawableRes res: Int): Painter = painterResource(res)

@Composable
fun PhIcon(@DrawableRes res: Int, contentDescription: String? = null, modifier: Modifier = Modifier, size: Dp = 22.dp, tint: Color = Mono.c.ink) =
    Icon(painterResource(res), contentDescription, modifier.size(size), tint = tint)

@Composable
fun VecIcon(icon: ImageVector, contentDescription: String? = null, modifier: Modifier = Modifier, size: Dp = 22.dp, tint: Color = Mono.c.ink) =
    Icon(icon, contentDescription, modifier.size(size), tint = tint)

/** Dark-only theme: a photo tool's chrome stays out of the way of the pictures. */
@Composable
fun UnrollTheme(content: @Composable () -> Unit) {
    val p = Dark
    // Material pieces (menus, switches, progress, text fields) resolve to the same greys.
    val scheme = darkColorScheme(
        primary = p.ink, onPrimary = p.bg, secondary = p.dim, onSecondary = p.bg, tertiary = p.accent, onTertiary = p.bg,
        background = p.bg, onBackground = p.ink, surface = p.surface, onSurface = p.ink, surfaceVariant = p.tile,
        onSurfaceVariant = p.dim, outline = p.faint, outlineVariant = p.line, primaryContainer = p.tile,
        onPrimaryContainer = p.ink, secondaryContainer = p.tile, onSecondaryContainer = p.ink,
        surfaceContainer = p.surface, surfaceContainerHigh = Color(0xFF242424), surfaceContainerLow = p.surface,
        surfaceContainerHighest = Color(0xFF2A2A2A), surfaceContainerLowest = p.bg, surfaceTint = Color.Transparent,
        error = Color(0xFFFF6B5E), onError = p.bg, inverseSurface = p.ink, inverseOnSurface = p.bg,
    )
    CompositionLocalProvider(LocalMono provides p) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}

/** Everything is dark now; kept so screens that force dark (pager, frame studio) read naturally. */
@Composable
fun DarkSurface(content: @Composable () -> Unit) = content()

// Surfaces

/** A flat panel on the canvas: surface fill, 12 dp corners, no shadow. */
@Composable
fun Modifier.card(shape: Shape = Mono.shape, @Suppress("UNUSED_PARAMETER") elevation: Dp = 0.dp): Modifier =
    this.clip(shape).background(Mono.c.surface, shape)

/** Press feedback: a quick dim (120 ms), no scale, no ripple. */
@Composable
fun Modifier.pressDim(enabled: Boolean = true, role: Role? = Role.Button, onClickLabel: String? = null, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val a by animateFloatAsState(if (pressed) 0.6f else 1f, tween(120), label = "press")
    return this.clickable(src, null, enabled = enabled, onClickLabel = onClickLabel, role = role, onClick = onClick).graphicsLayer { alpha = a }
}

/** Older name for [pressDim]; the scale argument is ignored (no scaling). */
@Composable
fun Modifier.tilePress(@Suppress("UNUSED_PARAMETER") scaleTo: Float = 1f, enabled: Boolean = true, onClick: () -> Unit): Modifier =
    pressDim(enabled, onClick = onClick)

// Text

@Composable
fun CapsLabel(text: String, modifier: Modifier = Modifier, color: Color = Mono.c.dim, size: TextUnit = 13.sp) {
    Text(text, modifier, style = Mono.caps(size), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { 1f.toDp() }
    Box(modifier.fillMaxWidth().height(px).background(Mono.c.line))
}

// Sections and rows

/** A titled group of rows on one flat panel, separated by inset hairlines. */
@Composable
fun MonoSection(title: String? = null, trailing: String? = null, footer: String? = null, rows: List<@Composable () -> Unit>) {
    Column(Modifier.padding(horizontal = Mono.gutter)) {
        if (title != null) {
            Row(Modifier.padding(horizontal = Mono.gutter).padding(bottom = 8.dp)) {
                CapsLabel(title, Modifier.weight(1f))
                trailing?.let { CapsLabel(it) }
            }
        }
        Column(Modifier.fillMaxWidth().card()) {
            rows.forEachIndexed { i, row ->
                row()
                if (i != rows.lastIndex) Hairline(Modifier.padding(start = Mono.gutter))
            }
        }
        footer?.let { Text(it, Modifier.padding(horizontal = Mono.gutter).padding(top = 8.dp), style = Mono.footnote, color = Mono.c.dim) }
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
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.pressDim(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp).padding(horizontal = Mono.gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = Mono.body, color = Mono.c.ink)
            detail?.let { Text(it, style = Mono.footnote, color = Mono.c.dim, maxLines = 3) }
        }
        value?.let { Text(it, style = Mono.data(15.sp), color = Mono.c.dim, textAlign = TextAlign.End) }
        trailing()
        if (chevron) PhIcon(Ph.chevron, size = 14.dp, tint = Mono.c.faint)
    }
}

/** Key / value line for EXIF and recipe data. */
@Composable
fun DataRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = Mono.footnote, color = Mono.c.dim)
        Spacer(Modifier.width(12.dp).weight(1f))
        Text(value, style = Mono.data(13.sp, FontWeight.Medium), color = Mono.c.ink, textAlign = TextAlign.End)
    }
}

// Buttons

/** Solid white button. Press = quick dim, no bounce. */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.pressDim(enabled && !busy, onClick = onClick)
            .heightIn(min = 50.dp)
            .clip(Mono.shape)
            .background(if (enabled) Mono.c.ink else Mono.c.ink.copy(alpha = 0.1f), Mono.shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Mono.c.bg, strokeWidth = 2.dp)
        else Text(
            text, style = Mono.body.copy(fontWeight = FontWeight.SemiBold), textAlign = TextAlign.Center,
            color = if (enabled) Mono.c.bg else Mono.c.ink.copy(alpha = 0.4f), maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Grey filled button, same footprint as primary. */
@Composable
fun SecondaryButton(
    text: String, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false,
    @DrawableRes icon: Int? = null, onClick: () -> Unit,
) {
    Row(
        modifier.pressDim(enabled && !busy, onClick = onClick)
            .heightIn(min = 50.dp)
            .clip(Mono.shape)
            .background(Mono.c.tile, Mono.shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        val fg = Mono.c.ink.copy(alpha = if (enabled) 1f else 0.3f)
        if (busy) { CircularProgressIndicator(Modifier.size(18.dp), color = Mono.c.ink, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        else if (icon != null) { PhIcon(icon, null, size = 18.dp, tint = fg); Spacer(Modifier.width(8.dp)) }
        Text(text, style = Mono.body.copy(fontWeight = FontWeight.Medium), color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Icon button with a 48 dp touch target (back, share, more…). */
@Composable
fun IconButton(@DrawableRes icon: Int, contentDescription: String?, modifier: Modifier = Modifier, filled: Boolean = false, tint: Color = Mono.c.ink, size: Dp = 22.dp, onClick: () -> Unit) {
    Box(
        modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(CircleShape).pressDim(onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(40.dp).background(if (filled) Mono.c.tile else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
            PhIcon(icon, contentDescription, size = size, tint = tint)
        }
    }
}

/** Small inline button (Start / Stop, Cancel, Open Photos…). */
@Composable
fun OutlineChip(text: String, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).pressDim(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, Modifier.heightIn(min = 32.dp).background(if (filled) Mono.c.ink else Mono.c.tile, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            style = Mono.footnote.copy(fontWeight = FontWeight.SemiBold), color = if (filled) Mono.c.bg else Mono.c.ink, maxLines = 1,
        )
    }
}

/** On/off chip for option toggles. */
@Composable
fun ChipToggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    Box(
        Modifier.heightIn(min = 48.dp).semantics { selected = on }.pressDim(role = Role.Checkbox) { Haptics.tick(view); onChange(!on) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, Modifier.heightIn(min = 34.dp).background(if (on) Mono.c.ink else Mono.c.tile, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            style = Mono.footnote.copy(fontWeight = FontWeight.Medium), color = if (on) Mono.c.bg else Mono.c.ink, maxLines = 1,
        )
    }
}

/** Segmented text tabs; the selected one sits on a grey pill. Scrolls horizontally when it doesn't fit. */
@Composable
fun <T> TabStrip(items: List<T>, selection: T, label: (T) -> String, count: ((T) -> Int?)? = null, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    Row(modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            val on = item == selection
            val bg by animateFloatAsState(if (on) 1f else 0f, tween(220), label = "tab")
            Row(
                Modifier.heightIn(min = 48.dp).semantics { selected = on }
                    .pressDim(role = Role.Tab) { if (!on) Haptics.tick(view); onSelect(item) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.heightIn(min = 34.dp).background(Mono.c.tile.copy(alpha = bg), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(label(item), style = Mono.subhead.copy(fontWeight = FontWeight.SemiBold), color = if (on) Mono.c.ink else Mono.c.dim, maxLines = 1)
                    count?.invoke(item)?.let { Text("$it", style = Mono.data(12.sp), color = if (on) Mono.c.dim else Mono.c.faint, maxLines = 1) }
                }
            }
        }
    }
}

/** Five stars drawn as plain glyphs. Filled = ink, empty = faint. */
@Composable
fun StarRow(rating: Int, size: Dp = 12.dp, onTap: ((Int) -> Unit)? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (n in 1..5) {
            val on = n <= rating
            PhIcon(
                if (on) Ph.star else Ph.starOutline, null, size = size, tint = if (on) Mono.c.ink else Mono.c.faint,
                modifier = if (onTap != null) Modifier.clip(CircleShape).clickable { onTap(n) }.padding(6.dp) else Modifier,
            )
        }
    }
}

/** Empty / status message: plain type, no illustration. */
@Composable
fun QuietMessage(title: String, message: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Mono.gutter, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = Mono.headline, color = Mono.c.ink)
        message?.let { Text(it, style = Mono.subhead, color = Mono.c.dim) }
    }
}

/** Top bar: back button, title (with optional subtitle), actions. Grows with the text, never clips it. */
@Composable
fun TopBar(
    title: String, onBack: (() -> Unit)?, @DrawableRes backIcon: Int = Ph.back, subtitle: String? = null,
    background: Color = Mono.c.bg, actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.background(background).statusBarsPadding().fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconButton(backIcon, if (backIcon == Ph.close) "Close" else "Back", onClick = onBack) else Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(title, style = Mono.headline, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Mono.c.ink)
            subtitle?.let { Text(it, style = Mono.data(12.sp), color = Mono.c.dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        actions()
    }
}

@Composable
fun TextAction(text: String, bold: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).pressDim(enabled, onClick = onClick).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(
            text, style = Mono.body.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal),
            color = if (enabled) Mono.c.ink else Mono.c.faint, maxLines = 1,
        )
    }
}

@Composable
fun Spinner(color: Color = Mono.c.dim, size: Dp = 22.dp) = CircularProgressIndicator(Modifier.size(size), color = color, strokeWidth = 2.dp)

/** A chevron that turns when [open] changes. */
@Composable
fun Disclosure(open: Boolean, tint: Color = Mono.c.dim) {
    val r by animateFloatAsState(if (open) 180f else 0f, tween(220), label = "disclosure")
    PhIcon(Ph.caretDown, null, Modifier.rotate(r), size = 13.dp, tint = tint)
}

/** Thin amber progress bar on a grey track (import progress). */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, height: Dp = 4.dp, color: Color = Mono.c.accent, track: Color = Mono.c.tile) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(250), label = "progress")
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxWidth().height(height).graphicsLayer {
            val k = f.coerceAtLeast(0.02f)
            scaleX = k; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
        }.background(color, CircleShape))
    }
}

/** A small panel border for overlays (toast, HUD). */
@Composable
fun Modifier.overlayPanel(shape: Shape = RoundedCornerShape(14.dp)): Modifier =
    this.clip(shape).background(Mono.c.surface, shape).border(0.5.dp, Mono.c.line, shape)
