package app.unroll.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.unroll.R
import kotlin.math.roundToInt

/**
 * Unroll's visual language: black, white and warm greys. Depth comes from layered surfaces and
 * soft shadows, not colour; motion is physical (springs), never decorative. Type is Nunito
 * throughout — data uses its tabular figures so numbers line up like a camera's top-plate display.
 */
@Immutable
data class MonoPalette(
    val bg: Color,
    /** Cards and sheets sit on this, one step above [bg]. */
    val surface: Color,
    val raised: Color,
    val ink: Color,
    val dim: Color,
    val faint: Color,
    val line: Color,
    val tile: Color,
    /** Near-black feature surface, same in both modes. */
    val hero: Color,
    val shadow: Color,
    val dark: Boolean,
)

private val Light = MonoPalette(
    bg = Color(0xFFF4F4F2), surface = Color.White, raised = Color(0xFFEBEBE8), ink = Color(0xFF0E0E0F),
    dim = Color(0xFF6C6C70), faint = Color(0xFFB5B5B8), line = Color(0xFFE3E3E0), tile = Color.White,
    hero = Color(0xFF111112), shadow = Color(0x40000000), dark = false,
)
private val Dark = MonoPalette(
    bg = Color(0xFF0A0A0B), surface = Color(0xFF151517), raised = Color(0xFF212124), ink = Color(0xFFF4F4F5),
    dim = Color(0xFF9A9AA0), faint = Color(0xFF55555B), line = Color(0xFF26262A), tile = Color(0xFF161618),
    hero = Color(0xFF1B1B1E), shadow = Color(0x99000000), dark = true,
)

val LocalMono = staticCompositionLocalOf { Light }

object Mono {
    val c: MonoPalette @Composable get() = LocalMono.current
    val gutter = 20.dp
    val radius = 16.dp
    val cardRadius = 24.dp

    val sans = FontFamily(
        Font(R.font.nunito_regular, FontWeight.Normal),
        Font(R.font.nunito_medium, FontWeight.Medium),
        Font(R.font.nunito_semibold, FontWeight.SemiBold),
        Font(R.font.nunito_bold, FontWeight.Bold),
        Font(R.font.nunito_extrabold, FontWeight.ExtraBold),
    )

    /** Small uppercase label for section headers and data captions. */
    fun caps(size: TextUnit = 11.sp) = TextStyle(fontFamily = sans, fontWeight = FontWeight.ExtraBold, fontSize = size, letterSpacing = 1.1.sp)

    /** Numbers and EXIF: Nunito with tabular figures so columns don't jitter while counting. */
    fun data(size: TextUnit = 15.sp, weight: FontWeight = FontWeight.SemiBold) =
        TextStyle(fontFamily = sans, fontWeight = weight, fontSize = size, fontFeatureSettings = "tnum")

    /** One physical feel for every touch: quick, slightly springy, never bouncy. */
    fun <T> press() = spring<T>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)
    fun <T> settle() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
}

/** Phosphor icons (MIT), shipped as vector drawables. */
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
}

@Composable
fun ph(@DrawableRes res: Int): Painter = painterResource(res)

@Composable
fun PhIcon(@DrawableRes res: Int, contentDescription: String? = null, modifier: Modifier = Modifier, size: Dp = 22.dp, tint: Color = Mono.c.ink) =
    Icon(painterResource(res), contentDescription, modifier.size(size), tint = tint)

@Composable
fun UnrollTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) Dark else Light
    // Material pieces (text fields, switches, menus, progress) resolve to the same greys.
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.ink, onPrimary = p.bg, secondary = p.dim, onSecondary = p.bg, tertiary = p.dim,
        background = p.bg, onBackground = p.ink, surface = p.surface, onSurface = p.ink, surfaceVariant = p.raised,
        onSurfaceVariant = p.dim, outline = p.faint, outlineVariant = p.line, primaryContainer = p.raised,
        onPrimaryContainer = p.ink, secondaryContainer = p.raised, onSecondaryContainer = p.ink,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surface, surfaceContainerLow = p.bg,
        surfaceContainerHighest = p.raised, surfaceContainerLowest = p.bg, error = p.ink, onError = p.bg,
    )
    val b = Typography()
    fun TextStyle.n() = copy(fontFamily = Mono.sans)
    val type = Typography(
        b.displayLarge.n(), b.displayMedium.n(), b.displaySmall.n(), b.headlineLarge.n(), b.headlineMedium.n(),
        b.headlineSmall.n(), b.titleLarge.n(), b.titleMedium.n(), b.titleSmall.n(), b.bodyLarge.n(), b.bodyMedium.n(),
        b.bodySmall.n(), b.labelLarge.n(), b.labelMedium.n(), b.labelSmall.n(),
    )
    CompositionLocalProvider(LocalMono provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}

/** Forces the dark palette for a subtree (shot pager, frame studio). */
@Composable
fun DarkSurface(content: @Composable () -> Unit) = UnrollTheme(dark = true, content = content)

// Surfaces

/** A card one step above the background: white on a soft, wide shadow in light mode; a lifted grey in dark. */
@Composable
fun Modifier.card(shape: Shape = RoundedCornerShape(Mono.cardRadius), elevation: Dp = 10.dp): Modifier {
    val c = Mono.c
    return this
        .shadow(if (c.dark) 0.dp else elevation, shape, clip = false, ambientColor = c.shadow, spotColor = c.shadow)
        .background(c.surface, shape)
        .then(if (c.dark) Modifier.border(1.dp, c.line, shape) else Modifier)
        .clip(shape)
}

/** Spring "press" feedback: a slight sink, no ripple, no colour. */
@Composable
fun Modifier.tilePress(scaleTo: Float = 0.96f, enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val p by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (p) scaleTo else 1f, Mono.press(), label = "press")
    return this.graphicsLayer { scaleX = s; scaleY = s }.clickable(src, null, enabled = enabled, onClick = onClick)
}

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

/** A titled group of rows on one card, separated by inset hairlines. */
@Composable
fun MonoSection(title: String? = null, trailing: String? = null, rows: List<@Composable () -> Unit>) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        if (title != null) {
            Row(Modifier.padding(horizontal = 6.dp).padding(bottom = 10.dp)) {
                CapsLabel(title)
                Spacer(Modifier.weight(1f))
                trailing?.let { CapsLabel(it) }
            }
        }
        Column(Modifier.fillMaxWidth().card(elevation = 6.dp)) {
            rows.forEachIndexed { i, row ->
                row()
                if (i != rows.lastIndex) Hairline(Modifier.padding(start = Mono.gutter))
            }
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
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Mono.c.ink)
            detail?.let { Text(it, fontSize = 13.sp, color = Mono.c.dim, maxLines = 3) }
        }
        value?.let { Text(it, style = Mono.data(14.sp), color = Mono.c.dim) }
        trailing()
        if (chevron) PhIcon(Ph.chevron, size = 14.dp, tint = Mono.c.faint)
    }
}

/** Key / value line for EXIF and recipe data. */
@Composable
fun DataRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Mono.c.dim)
        Spacer(Modifier.width(12.dp).weight(1f))
        Text(value, style = Mono.data(14.sp, FontWeight.Bold), color = Mono.c.ink, textAlign = TextAlign.End)
    }
}

// Buttons

/** Solid ink button (black on light, white on dark). */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    val bg by animateColorAsState(if (enabled) Mono.c.ink else Mono.c.ink.copy(alpha = 0.1f), label = "bg")
    Box(
        modifier.defaultMinSize(minHeight = 54.dp)
            .tilePress(0.97f, enabled && !busy, onClick)
            .shadow(if (enabled && !Mono.c.dark) 8.dp else 0.dp, RoundedCornerShape(Mono.radius), ambientColor = Mono.c.shadow, spotColor = Mono.c.shadow)
            .background(bg, RoundedCornerShape(Mono.radius))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Mono.c.bg, strokeWidth = 2.dp)
        else Text(text, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = if (enabled) Mono.c.bg else Mono.c.ink.copy(alpha = 0.4f), maxLines = 1)
    }
}

/** Quiet button on a raised surface, same footprint as primary. */
@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.defaultMinSize(minHeight = 54.dp)
            .tilePress(0.97f, enabled && !busy, onClick)
            .background(Mono.c.raised, RoundedCornerShape(Mono.radius))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Mono.c.ink, strokeWidth = 2.dp)
        else Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Mono.c.ink.copy(alpha = if (enabled) 1f else 0.3f), maxLines = 1)
    }
}

/** Round icon button (back, share, more…). */
@Composable
fun IconButton(@DrawableRes icon: Int, contentDescription: String?, modifier: Modifier = Modifier, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.size(42.dp).tilePress(0.9f, onClick = onClick)
            .background(if (filled) Mono.c.raised else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) { PhIcon(icon, contentDescription, size = 21.dp) }
}

/** Small capsule button used inline (Cancel, Open Photos…). */
@Composable
fun OutlineChip(text: String, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 34.dp).tilePress(0.94f, onClick = onClick)
            .background(if (filled) Mono.c.ink else Mono.c.raised, CircleShape).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = if (filled) Mono.c.bg else Mono.c.ink) }
}

/** On/off chip for option toggles; fills with ink when on. */
@Composable
fun ChipToggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    val bg by animateColorAsState(if (on) Mono.c.ink else Mono.c.raised, Mono.settle(), label = "chip")
    val fg by animateColorAsState(if (on) Mono.c.bg else Mono.c.dim, Mono.settle(), label = "chipText")
    Box(
        Modifier.heightIn(min = 36.dp).tilePress(0.94f) { Haptics.tick(view); onChange(!on) }.background(bg, CircleShape).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1) }
}

/**
 * Segmented filter strip: an ink pill slides (with a spring) to the selected item and the text
 * inverts under it. Scrolls horizontally when it doesn't fit.
 */
@Composable
fun <T> TabStrip(items: List<T>, selection: T, label: (T) -> String, count: ((T) -> Int?)? = null, onSelect: (T) -> Unit) {
    val density = LocalDensity.current
    val view = androidx.compose.ui.platform.LocalView.current
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val idx = items.indexOf(selection).coerceAtLeast(0)
    val target = bounds[idx]
    val x by animateFloatAsState(target?.first ?: 0f, Mono.press(), label = "pillX")
    val w by animateFloatAsState(target?.second ?: 0f, Mono.press(), label = "pillW")
    Box(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (target != null) {
            Box(
                Modifier.offset { IntOffset(x.roundToInt(), 0) }.width(with(density) { w.toDp() }).height(38.dp)
                    .background(Mono.c.ink, CircleShape),
            )
        }
        Row {
            items.forEachIndexed { i, item ->
                val on = i == idx
                val fg by animateColorAsState(if (on) Mono.c.bg else Mono.c.dim, Mono.settle(), label = "tab")
                Row(
                    Modifier.height(38.dp)
                        .onGloballyPositioned { c ->
                            val x0 = c.parentLayoutCoordinates?.localPositionOf(c, Offset.Zero)?.x ?: 0f
                            bounds[i] = x0 to c.size.width.toFloat()
                        }
                        .clip(CircleShape).clickable { if (!on) Haptics.tick(view); onSelect(item) }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(label(item), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = fg)
                    count?.invoke(item)?.let { Text("$it", style = Mono.data(12.sp, FontWeight.Bold), color = fg.copy(alpha = 0.7f)) }
                }
            }
        }
    }
}

/** Five stars. Filled = ink, empty = faint; a change pops with a spring. */
@Composable
fun StarRow(rating: Int, size: Dp = 12.dp, onTap: ((Int) -> Unit)? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(if (onTap != null) 2.dp else 2.dp)) {
        for (n in 1..5) {
            val on = n <= rating
            val s by animateFloatAsState(if (on) 1f else 0.82f, Mono.press(), label = "star")
            PhIcon(
                if (on) Ph.star else Ph.starOutline, null, size = size, tint = if (on) Mono.c.ink else Mono.c.faint,
                modifier = (if (onTap != null) Modifier.clip(CircleShape).clickable { onTap(n) }.padding(6.dp) else Modifier)
                    .graphicsLayer { scaleX = s; scaleY = s },
            )
        }
    }
}

/** Empty / status message: plain type, no illustration. */
@Composable
fun QuietMessage(title: String, message: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Mono.gutter + 6.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Mono.c.ink)
        message?.let { Text(it, fontSize = 15.sp, color = Mono.c.dim, lineHeight = 21.sp) }
    }
}

/** Top bar: round back button, title, actions. */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, @DrawableRes backIcon: Int = Ph.back, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.background(Mono.c.bg).statusBarsPadding().fillMaxWidth().height(58.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconButton(backIcon, "Back", filled = true, onClick = onBack) else Spacer(Modifier.width(12.dp))
        Text(
            title, Modifier.weight(1f).padding(horizontal = 12.dp), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = Mono.c.ink,
        )
        actions()
    }
}

@Composable
fun TextAction(text: String, bold: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text, Modifier.clip(CircleShape).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        fontSize = 16.sp, fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.Bold,
        color = if (enabled) Mono.c.ink else Mono.c.faint,
    )
}

// Icons by role (kept as an enum so screens read naturally).

enum class GlyphKind(@DrawableRes val res: Int) {
    USB(R.drawable.ph_usb), CARD(R.drawable.ph_sim_card), WIFI(R.drawable.ph_wifi_high), RECIPES(R.drawable.ph_aperture),
    CLOCK(R.drawable.ph_timer), FRAME(R.drawable.ph_frame_corners), MAP(R.drawable.ph_path), GEAR(R.drawable.ph_gear_six),
    VIDEO(R.drawable.ph_video_camera)
}

@Composable
fun Glyph(kind: GlyphKind, modifier: Modifier = Modifier, color: Color = Mono.c.ink, size: Dp = 24.dp) =
    PhIcon(kind.res, null, modifier, size, color)

/** Tile: icon top-left, count or tag top-right, title and caption at the bottom. Lifts on a soft shadow. */
@Composable
fun Tile(glyph: GlyphKind, title: String, caption: String? = null, count: Int = 0, tag: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.tilePress(onClick = onClick).heightIn(min = 124.dp).card().padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Glyph(glyph, size = 26.dp)
            Spacer(Modifier.weight(1f))
            if (tag != null) {
                Text(tag.uppercase(), style = Mono.caps(9.sp), color = Mono.c.ink,
                    modifier = Modifier.background(Mono.c.raised, CircleShape).padding(horizontal = 8.dp, vertical = 4.dp))
            } else if (count > 0) {
                AnimatedCount(count, Mono.data(14.sp, FontWeight.Bold), Mono.c.dim)
            }
        }
        Column(Modifier.padding(top = 20.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Mono.c.ink)
            caption?.let { Text(it, fontSize = 13.sp, color = Mono.c.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp)) }
        }
    }
}

@Composable
fun Spinner(color: Color = Mono.c.dim, size: Dp = 22.dp) = CircularProgressIndicator(Modifier.size(size), color = color, strokeWidth = 2.dp)

/** A chevron that rotates with a spring when [open] changes. */
@Composable
fun Disclosure(open: Boolean, tint: Color = Mono.c.dim) {
    val r by animateFloatAsState(if (open) 180f else 0f, Mono.press(), label = "disclosure")
    PhIcon(Ph.caretDown, null, Modifier.rotate(r), size = 13.dp, tint = tint)
}
