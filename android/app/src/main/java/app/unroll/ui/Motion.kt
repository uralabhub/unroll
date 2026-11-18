@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.unroll.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * Unroll's motion language: lively but quick, and never in the way.
 * - Every touch answers: presses scale down and spring back; selections and checks pop.
 * - Content arrives: blocks fade and rise into place, staggered, once per screen visit.
 * - Live states breathe (waiting for a camera, recording, importing) only while they are live.
 * - Photo grid cells stay cheap: a thumbnail fade, never per-cell entrances or loops.
 * - Settings → "Remove animations" ([LocalReduceMotion]) turns all of it into plain fades.
 */
object Motion {
    /** Press feedback: fast in. */
    fun <T> press(): FiniteAnimationSpec<T> = tween(120)
    /** State changes inside a control (tabs, toggles, selection). */
    fun <T> snap(): FiniteAnimationSpec<T> = tween(220, easing = FastOutSlowInEasing)
    /** Content arriving (lists, cards). */
    fun <T> arrive(): FiniteAnimationSpec<T> = tween(300, easing = FastOutSlowInEasing)
    /** Larger layout moves (screens, bars, panels). */
    fun <T> glide(): FiniteAnimationSpec<T> = tween(350, easing = FastOutSlowInEasing)
    /** Progress fills: steady, not a spring. */
    fun <T> progress(): FiniteAnimationSpec<T> = tween(250)
    /** Physical moves (shared photo): fully damped, ~0.35 s. */
    fun <T> settle(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 380f)
    /** Pops (checks, stars, chips, press release): a small, quick overshoot. */
    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.7f, stiffness = 500f)
    /** Bars, sheets and toasts sliding in: a soft overshoot. */
    fun <T> float(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.82f, stiffness = 420f)
    /** How far a pressed button shrinks. */
    const val pressScale = 0.96f
    /** Stagger between arriving blocks; capped so long lists never wait. */
    fun staggerMs(index: Int): Long = 35L * index.coerceIn(0, 8)
}

/** The shared-transition scope around the whole navigation, and the scope of the screen being shown. */
val LocalShared = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Marks a view as the same photo on two screens (grid cell ↔ pager page), so navigating zooms
 * one into the other instead of cutting. Fully damped: no overshoot.
 */
@Composable
fun Modifier.sharedPhoto(id: String): Modifier {
    val shared = LocalShared.current ?: return this
    val scope = LocalNavScope.current ?: return this
    return with(shared) {
        this@sharedPhoto.sharedElement(
            rememberSharedContentState("photo-$id"), scope,
            boundsTransform = { _, _ -> spring(dampingRatio = 1f, stiffness = 380f) },
        )
    }
}

/**
 * Fades the block in while it rises 16 dp and grows from 98 %, [index] × 35 ms after it first
 * appears. Runs once per [key] (saved, so scrolling back or returning to the screen doesn't replay it).
 * Only a graphics layer changes: no relayout.
 */
@Composable
fun Modifier.riseIn(index: Int = 0, key: Any = Unit): Modifier {
    val reduce = LocalReduceMotion.current
    var done by rememberSaveable(key) { mutableStateOf(false) }
    val p = remember(key) { Animatable(if (done) 1f else 0f) }
    LaunchedEffect(key) {
        if (done) return@LaunchedEffect
        delay(Motion.staggerMs(index))
        p.animateTo(1f, if (reduce) tween(200) else spring(dampingRatio = 0.8f, stiffness = 260f))
        done = true
    }
    return this.graphicsLayer {
        val v = p.value
        alpha = v.coerceIn(0f, 1f)
        if (!reduce) {
            translationY = (1f - v) * 16.dp.toPx()
            val k = 0.98f + 0.02f * v
            scaleX = k; scaleY = k
        }
    }
}

/** Plays a one-off pop (1 → [peak] → 1) whenever [trigger] changes after the first composition. */
@Composable
fun Modifier.popOn(trigger: Any?, peak: Float = 1.25f): Modifier {
    val reduce = LocalReduceMotion.current
    val k = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(trigger) {
        if (first) { first = false; return@LaunchedEffect }
        if (reduce) return@LaunchedEffect
        k.animateTo(peak, tween(90))
        k.animateTo(1f, Motion.bouncy())
    }
    return this.graphicsLayer { scaleX = k.value; scaleY = k.value }
}

/**
 * Counter with rolling digits: each changed digit slides up (count grows) or down (count shrinks).
 * Digits are keyed from the right so units stay put when the number gains a digit.
 */
@Composable
fun AnimatedCount(value: Int, style: TextStyle, color: Color, modifier: Modifier = Modifier, suffix: String = "", prefix: String = "") {
    val reduce = LocalReduceMotion.current
    var last by remember { mutableIntStateOf(value) }
    val up = value >= last
    SideEffect { last = value }
    val tnum = style.copy(fontFeatureSettings = "tnum")
    val digits = value.toString()
    Row(modifier.clearAndSetSemantics { contentDescription = "$prefix$value$suffix" }) {
        if (prefix.isNotEmpty()) Text(prefix, style = tnum, color = color, maxLines = 1)
        for (i in digits.indices) {
            key(digits.length - i) {
                AnimatedContent(
                    digits[i], label = "digit",
                    transitionSpec = {
                        if (reduce) fadeIn(tween(150)).togetherWith(fadeOut(tween(100)))
                        else {
                            val dir = if (up) 1 else -1
                            (slideInVertically(Motion.bouncy()) { h -> dir * h } + fadeIn(tween(160)))
                                .togetherWith(slideOutVertically(tween(180)) { h -> -dir * h } + fadeOut(tween(120)))
                                .using(SizeTransform(clip = true))
                        }
                    },
                ) { c -> Text(c.toString(), style = tnum, color = color, maxLines = 1) }
            }
        }
        if (suffix.isNotEmpty()) Text(suffix, style = tnum, color = color, maxLines = 1)
    }
}

/** Text whose changed characters roll up (clocks, timers). Same-length strings keep each glyph in place. */
@Composable
fun RollingText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val tnum = style.copy(fontFeatureSettings = "tnum")
    Row(modifier.clearAndSetSemantics { contentDescription = text }) {
        for (i in text.indices) {
            key(text.length - i) {
                AnimatedContent(
                    text[i], label = "char",
                    transitionSpec = {
                        if (reduce) fadeIn(tween(150)).togetherWith(fadeOut(tween(100)))
                        else (slideInVertically(Motion.bouncy()) { h -> h } + fadeIn(tween(160)))
                            .togetherWith(slideOutVertically(tween(180)) { h -> -h } + fadeOut(tween(120)))
                            .using(SizeTransform(clip = true))
                    },
                ) { c -> Text(c.toString(), style = tnum, color = color, maxLines = 1) }
            }
        }
    }
}

/** Repeating sweep 0 → 1 for shimmers and sheens. Only call it while the effect is visible. */
@Composable
fun rememberSweep(periodMs: Int = 1300): State<Float> {
    val t = rememberInfiniteTransition(label = "sweep")
    return t.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
}

/** A soft highlight that sweeps across this element's bounds while [active] (progress fills). Put it after any clip/scale. */
@Composable
fun Modifier.sheen(active: Boolean, strength: Float = 0.35f, periodMs: Int = 1300): Modifier {
    if (!active || LocalReduceMotion.current) return this
    val sweep by rememberSweep(periodMs)
    return this.drawWithContent {
        drawContent()
        val band = maxOf(size.width * 0.35f, 60.dp.toPx())
        val x = -band + (size.width + 2 * band) * sweep
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = strength), Color.Transparent), startX = x - band / 2, endX = x + band / 2),
        )
    }
}

/**
 * An expanding, fading ring behind a status dot: "listening". One ring, then a rest with no
 * frames drawn, so an idle Home screen doesn't keep the GPU busy at the display refresh rate.
 */
@Composable
fun PulseDot(color: Color, size: Dp = 7.dp, live: Boolean = true) {
    val reduce = LocalReduceMotion.current
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (live && !reduce) {
            val p = remember { Animatable(1f) }
            LaunchedEffect(Unit) {
                while (true) {
                    p.snapTo(0f)
                    p.animateTo(1f, tween(1400, easing = LinearOutSlowInEasing))
                    kotlinx.coroutines.delay(2200)
                }
            }
            Box(Modifier.size(size).graphicsLayer {
                val k = 1f + 2.4f * p.value
                scaleX = k; scaleY = k; alpha = 0.55f * (1f - p.value)
            }.background(color, CircleShape))
        }
        Box(Modifier.size(size).background(color, CircleShape))
    }
}

/** Alpha dips to 0.4 and back, then rests (no frames) for a moment: live indicators (recording). */
@Composable
fun Modifier.breathe(active: Boolean): Modifier {
    if (!active || LocalReduceMotion.current) return this
    val a = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            a.animateTo(0.4f, tween(900, easing = FastOutSlowInEasing))
            a.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
            kotlinx.coroutines.delay(1400)
        }
    }
    return this.graphicsLayer { alpha = a.value }
}

// Accessibility: Settings → "Remove animations" sets the animator duration scale to 0.

/** True when the user turned animations off system-wide. */
val LocalReduceMotion = androidx.compose.runtime.staticCompositionLocalOf { false }

fun systemReducesMotion(context: android.content.Context): Boolean =
    android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

// Haptics on key moments (filter change, selection, import start / finish, walk start / stop).

object Haptics {
    fun tick(view: android.view.View) { view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
    fun confirm(view: android.view.View) { view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM) }
    fun press(view: android.view.View) { view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY) }
}
