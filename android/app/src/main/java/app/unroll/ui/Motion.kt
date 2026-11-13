@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.unroll.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** The shared-transition scope around the whole navigation, and the scope of the screen being shown. */
val LocalShared = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Marks a view as the same photo on two screens (grid cell ↔ pager page), so navigating morphs
 * one into the other instead of cutting.
 */
@Composable
fun Modifier.sharedPhoto(id: String): Modifier {
    val shared = LocalShared.current ?: return this
    val scope = LocalNavScope.current ?: return this
    return with(shared) {
        this@sharedPhoto.sharedElement(
            rememberSharedContentState("photo-$id"), scope,
            boundsTransform = { _, _ -> spring(dampingRatio = 0.86f, stiffness = 260f) },
        )
    }
}

/**
 * Rise-in on first composition: fades up from 16dp, delayed by [index] × 45 ms so a column of
 * blocks arrives in order. Runs once per [key].
 */
@Composable
fun Modifier.riseIn(index: Int, key: Any = Unit): Modifier {
    if (LocalReduceMotion.current) return this
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        delay(index * 45L)
        a.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow))
    }
    return this.graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 16.dp.toPx()
    }
}

/** Grid cells: scale up from 0.92 and fade, staggered a little by position, only the first time. */
@Composable
fun Modifier.popIn(index: Int, key: Any): Modifier {
    if (LocalReduceMotion.current) return this
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        delay((index % 12) * 22L)
        a.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow))
    }
    return this.graphicsLayer {
        alpha = a.value.coerceIn(0f, 1f)
        val s = 0.92f + 0.08f * a.value
        scaleX = s; scaleY = s
    }
}

/** Number that rolls digit by digit when it changes (up rolls up, down rolls down). */
@Composable
fun AnimatedCount(value: Int, style: TextStyle, color: Color, modifier: Modifier = Modifier, suffix: String = "") {
    AnimatedContent(
        value, modifier, label = "count",
        transitionSpec = {
            val up = targetState > initialState
            (slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)) { if (up) it else -it } + fadeIn(tween(160)))
                .togetherWith(slideOutVertically(tween(160)) { if (up) -it / 2 else it / 2 } + fadeOut(tween(120)))
        },
    ) { v -> Row { Text("$v$suffix", style = style, color = color) } }
}

// Accessibility: Settings → "Remove animations" sets the animator duration scale to 0.

/** True when the user turned animations off system-wide; loops, tilt and staggered entrances then stay still. */
val LocalReduceMotion = androidx.compose.runtime.staticCompositionLocalOf { false }

fun systemReducesMotion(context: android.content.Context): Boolean =
    android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

// Haptics on key moments (filter change, selection, import start / finish, walk start / stop).

object Haptics {
    fun tick(view: android.view.View) { view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
    fun confirm(view: android.view.View) { view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM) }
    fun press(view: android.view.View) { view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY) }
}

/**
 * Device tilt from the gravity sensor, as (rotationX, rotationY) in degrees, clamped to ±[maxDeg]
 * and relative to how the phone was held when the screen appeared. Springs toward the target so
 * the card drifts rather than jitters. Zero when motion is reduced or there is no sensor.
 */
@Composable
fun rememberTilt(maxDeg: Float = 4f): Pair<Float, Float> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val reduce = LocalReduceMotion.current
    val target = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0f to 0f) }
    androidx.compose.runtime.DisposableEffect(reduce) {
        if (reduce) return@DisposableEffect onDispose { }
        val sm = context.getSystemService(android.hardware.SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY)
        var base: FloatArray? = null
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val g = e.values
                val b = base ?: g.copyOf().also { base = it }
                val ry = ((b[0] - g[0]) / 9.81f * 40f).coerceIn(-maxDeg, maxDeg)
                val rx = ((g[1] - b[1]) / 9.81f * 40f).coerceIn(-maxDeg, maxDeg)
                target.value = rx to ry
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) = Unit
        }
        if (sensor != null) sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm?.unregisterListener(listener) }
    }
    val spec = spring<Float>(dampingRatio = 0.9f, stiffness = Spring.StiffnessVeryLow)
    val x by androidx.compose.animation.core.animateFloatAsState(target.value.first, spec, label = "tiltX")
    val y by androidx.compose.animation.core.animateFloatAsState(target.value.second, spec, label = "tiltY")
    return x to y
}
