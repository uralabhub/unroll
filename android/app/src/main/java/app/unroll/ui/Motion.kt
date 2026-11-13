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
