@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.unroll.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.unroll.AppState
import app.unroll.Screen
import kotlinx.coroutines.delay
import java.util.Locale

/** What is on screen: the base (home / a session's library) plus the pushed screens over it. */
private data class Route(val depth: Int, val key: String, val screen: Screen?, val sessionId: String?)

@Composable
fun Root(app: AppState) {
    val session = app.session
    val top = app.stack.lastOrNull()
    BackHandler(enabled = top != null || session != null || app.showPaywall) {
        when {
            app.showPaywall -> app.showPaywall = false
            app.pop() -> Unit
            else -> app.disconnect()
        }
    }
    val route = Route(
        depth = app.stack.size + if (session != null) 1 else 0,
        key = top?.let { "${app.stack.size}:${it::class.simpleName}" } ?: (session?.id?.let { "lib:$it" } ?: "home"),
        screen = top, sessionId = session?.id,
    )
    val saveable = rememberSaveableStateHolder()
    val context = LocalContext.current
    val reduce = remember { systemReducesMotion(context) }

    CompositionLocalProvider(LocalReduceMotion provides reduce) {
    Box(Modifier.fillMaxSize().background(Mono.c.bg)) {
        SharedTransitionLayout {
            AnimatedContent(
                route, contentKey = { it.key }, label = "nav",
                transitionSpec = {
                    val forward = targetState.depth >= initialState.depth
                    val spec = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
                    if (forward) {
                        (slideInHorizontally(spec) { it / 3 } + fadeIn(tween(220)) + scaleIn(initialScale = 0.98f))
                            .togetherWith(slideOutHorizontally(spec) { -it / 8 } + fadeOut(tween(180)))
                    } else {
                        (slideInHorizontally(spec) { -it / 8 } + fadeIn(tween(220)))
                            .togetherWith(slideOutHorizontally(spec) { it / 3 } + fadeOut(tween(180)) + scaleOut(targetScale = 0.98f))
                    }
                },
            ) { r ->
                CompositionLocalProvider(LocalShared provides this@SharedTransitionLayout, LocalNavScope provides this) {
                    saveable.SaveableStateProvider(r.key) {
                        val s = app.session
                        when (val sc = r.screen) {
                            is Screen.Detail -> ShotPagerScreen(app, sc.shots, sc.startId)
                            is Screen.Frame -> FrameStudioScreen(app, sc.image, sc.meta, sc.location)
                            is Screen.Recap -> RecapScreen(app, sc.shots)
                            Screen.Recipes -> RecipesScreen(app)
                            is Screen.RecipeDetail -> RecipeDetailScreen(app, sc.id)
                            Screen.ClockSync -> ClockSyncScreen(app)
                            Screen.Settings -> SettingsScreen(app)
                            Screen.Walks -> WalksScreen(app)
                            null -> if (s != null && r.sessionId == s.id) LibraryScreen(app, s) else HomeScreen(app)
                        }
                    }
                }
            }
        }

        // Paywall: a sheet that springs up over a dimmed screen.
        val scrim by animateFloatAsState(if (app.showPaywall) 0.45f else 0f, tween(250), label = "scrim")
        if (scrim > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim))
                .clickable(remember { MutableInteractionSource() }, null) { app.showPaywall = false })
        }
        AnimatedVisibility(
            app.showPaywall,
            enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)) { it },
            exit = slideOutVertically(tween(220)) { it },
        ) {
            Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 24.dp).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))) {
                PaywallSheet(app) { app.showPaywall = false }
            }
        }

        // The paywall covers the HUD; the toast stays on top of everything.
        if (!app.showPaywall) Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 92.dp)) { ImportHUD(app) }
        Box(Modifier.align(Alignment.TopCenter).statusBarsPadding()) { Toast(app) }
    }
    }
}

/** Import progress card above the action bar; "N saved" for a few seconds when a run ends. */
@Composable
private fun ImportHUD(app: AppState) {
    val imp = app.importer
    val context = LocalContext.current
    var showDone by remember { mutableStateOf(false) }
    val running = imp.isRunning
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(running) {
        if (running) Haptics.press(view)
        if (!running && imp.lastFinishedRun != null) { Haptics.confirm(view); showDone = true; delay(5000); showDone = false }
    }
    val progress by animateFloatAsState(imp.progress.toFloat().coerceIn(0f, 1f), Mono.settle(), label = "hud")
    AnimatedVisibility(
        running || showDone,
        enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().card(RoundedCornerShape(22.dp), 18.dp)) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(running, Modifier.weight(1f), label = "hudText") { r ->
                    if (r) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Importing ", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Mono.c.ink)
                                AnimatedCount(minOf(imp.completedInRun + imp.failedInRun + 1, imp.totalInRun), Mono.data(15.sp, FontWeight.ExtraBold), Mono.c.ink)
                                Text(" of ${imp.totalInRun}", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Mono.c.ink)
                            }
                            Text(imp.throughputMBps?.let { String.format(Locale.US, "%.0f MB/s", it) } ?: (imp.current?.primary?.name ?: ""), style = Mono.data(12.sp), color = Mono.c.dim)
                        }
                    } else {
                        imp.lastFinishedRun?.let { run ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PhIcon(Ph.checkCircle, null, size = 26.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("${run.count} saved to your library", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Mono.c.ink)
                                    Text(String.format(Locale.US, "%.1f s", run.seconds) + (if (run.failed > 0) "  ·  ${run.failed} failed" else "") +
                                        if (run.geotagged > 0) "  ·  ${run.geotagged} with location" else "  ·  originals untouched",
                                        style = Mono.data(12.sp), color = Mono.c.dim)
                                }
                            }
                        }
                    }
                }
                if (running) OutlineChip("Cancel") { imp.cancelAll() }
                else OutlineChip("Open", filled = true) {
                    runCatching {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            if (running) {
                Box(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(Mono.c.raised)) {
                    Box(Modifier.fillMaxWidth(progress).height(4.dp).background(Mono.c.ink, CircleShape))
                }
            }
        }
    }
}

@Composable
private fun Toast(app: AppState) {
    val text = app.toast ?: app.importer.lastError
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(text) {
        if (text != null) { shown = text; delay(3000); app.toast = null; app.importer.lastError = null }
    }
    AnimatedVisibility(
        text != null,
        enter = slideInVertically(spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium)) { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Text(
            shown ?: "", Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp).card(RoundedCornerShape(18.dp), 16.dp)
                .background(Mono.c.ink).padding(horizontal = 18.dp, vertical = 12.dp),
            fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Mono.c.bg,
        )
    }
}
