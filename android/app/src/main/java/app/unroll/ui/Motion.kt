@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.unroll.ui

import androidx.compose.animation.AnimatedVisibilityScope
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
 * Unroll's motion language: quick, critically damped, purposeful.
 * - Nothing animates while the screen is idle.
 * - Nothing overshoots (no bounce): springs are fully damped, otherwise 200–350 ms tweens.
 * - Motion explains a change (where a photo went, what is importing); it never decorates.
 */
object Motion {
    /** Press feedback: a fast dim, no scale. */
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

/** Flat design: kept as a no-op so older call sites compile. Nothing rises in. */
@Suppress("UNUSED_PARAMETER", "UnusedReceiverParameter")
fun Modifier.riseIn(index: Int, key: Any = Unit): Modifier = this

/** Counters are plain text with tabular digits; they don't roll. */
@Composable
fun AnimatedCount(value: Int, style: TextStyle, color: Color, modifier: Modifier = Modifier, suffix: String = "") =
    Text("$value$suffix", modifier, style = style, color = color)

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
