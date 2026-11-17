@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.unroll.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.unroll.AppState
import app.unroll.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** What is on screen: the base (home / a session's library) plus the pushed screens over it. */
private data class Route(val depth: Int, val key: String, val screen: Screen?, val sessionId: String?)

@Composable
fun Root(app: AppState) {
    val session = app.session
    val top = app.stack.lastOrNull()
    val scope = rememberCoroutineScope()

    // Back pops pushed screens (with the predictive-back preview). It never disconnects: from the
    // library or Home, back leaves the app like any root screen. Disconnect lives in the menus.
    var backProgress by remember { mutableFloatStateOf(0f) }
    var backKey by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = app.showPaywall) { app.showPaywall = false }
    PredictiveBackHandler(enabled = top != null && !app.showPaywall) { events ->
        val key = "${app.stack.size}:${app.stack.lastOrNull()?.let { it::class.simpleName }}"
        try {
            backKey = key
            events.collect { backProgress = it.progress }
            app.pop()
            // Let the outgoing screen leave from where the gesture left it, then reset.
            scope.launch { delay(380); if (backKey == key) { backKey = null; backProgress = 0f } }
        } catch (e: CancellationException) {
            backKey = null; backProgress = 0f
            throw e
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

    LaunchedEffect(app.showPaywall) {
        if (!app.showPaywall) app.paywallNote?.let { app.toast = it; app.paywallNote = null }
    }

    // Import summary stays up for a few seconds after a run ends.
    val view = androidx.compose.ui.platform.LocalView.current
    val finished = app.importer.lastFinishedRun
    LaunchedEffect(finished) {
        // Measured from when the run ended, so recreating the activity (rotation, font size)
        // doesn't replay an old summary or its haptic.
        val left = finished?.let { 5000 - (System.currentTimeMillis() - it.endedAt) } ?: 0
        if (finished != null && left > 0) {
            if (left > 4500) Haptics.confirm(view)
            app.showingSummary = true; delay(left); app.showingSummary = false
        } else app.showingSummary = false
    }

    CompositionLocalProvider(LocalReduceMotion provides reduce) {
    Box(Modifier.fillMaxSize().background(Mono.c.bg)) {
        SharedTransitionLayout {
            AnimatedContent(
                route, contentKey = { it.key }, label = "nav",
                transitionSpec = {
                    val forward = targetState.depth >= initialState.depth
                    val photo = targetState.screen is Screen.Detail || initialState.screen is Screen.Detail
                    val base = targetState.screen == null && initialState.screen == null
                    when {
                        // Home ↔ library, and grid ↔ photo (the photo itself zooms): a plain cross-fade.
                        base || photo -> fadeIn(tween(300)).togetherWith(fadeOut(tween(220)))
                        forward -> (slideInHorizontally(tween(320)) { it / 10 } + fadeIn(tween(300)))
                            .togetherWith(fadeOut(tween(200)))
                        else -> fadeIn(tween(300))
                            .togetherWith(slideOutHorizontally(tween(320)) { it / 10 } + fadeOut(tween(220)))
                    }
                },
            ) { r ->
                val dragged = r.key == backKey
                CompositionLocalProvider(LocalShared provides this@SharedTransitionLayout, LocalNavScope provides this) {
                    // Photos go edge to edge; every other screen keeps clear of cutouts and side navigation bars.
                    val sides = if (r.screen is Screen.Detail) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        if (dragged) {
                            val p = backProgress
                            val k = 1f - 0.08f * p
                            scaleX = k; scaleY = k
                            translationX = p * 24.dp.toPx()
                            clip = true
                            shape = RoundedCornerShape(28.dp * p)
                        }
                    }.background(Mono.c.bg).then(sides)) {
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
        }

        // Paywall: a sheet over a dimmed screen. No bounce.
        val scrim by animateFloatAsState(if (app.showPaywall) 0.5f else 0f, tween(250), label = "scrim")
        if (scrim > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim))
                .clickable(remember { MutableInteractionSource() }, null) { app.showPaywall = false })
        }
        AnimatedVisibility(
            app.showPaywall,
            enter = slideInVertically(tween(320)) { it },
            exit = slideOutVertically(tween(250)) { it },
        ) {
            Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 24.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))) {
                PaywallSheet(app) { app.showPaywall = false }
            }
        }

        // Floating import status only when no library is there to host it (a paused run outliving its session).
        if (!app.showPaywall) {
            Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp)) { ImportHUD(app) }
        }
        // Toast at the bottom, above the bar (never over the status bar).
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp)) { Toast(app) }
    }
    }
}

/** True while there's an import to show: running, paused waiting for the camera, or just finished. */
val AppState.importVisible: Boolean
    get() = importer.isRunning || importer.paused != null || showingSummary

/**
 * Progress of the running import, or the summary of the one that just finished.
 * Lives in the library's bottom bar (and floats on Home only if a paused run outlives the session).
 */
@Composable
fun ImportStatusBar(app: AppState, modifier: Modifier = Modifier) {
    val imp = app.importer
    val context = LocalContext.current
    val running = imp.isRunning
    val paused = !running && imp.paused != null
    val title = when {
        running -> "Importing ${minOf(imp.completedInRun + imp.failedInRun + 1, imp.totalInRun)} of ${imp.totalInRun}"
        paused -> "Paused · ${imp.completedInRun} of ${imp.completedInRun + imp.waitingCount}"
        else -> imp.lastFinishedRun?.let { "${it.count} saved to Photos" } ?: ""
    }
    val subtitle = when {
        paused -> "Camera disconnected. Plug it back in to continue."
        running -> imp.throughputMBps?.let { String.format(Locale.US, "%.0f MB/s", it) } ?: (imp.current?.primary?.name ?: "Starting…")
        else -> imp.lastFinishedRun?.let { run ->
            buildList {
                add(String.format(Locale.US, "%.1f s", run.seconds))
                if (run.failed > 0) add("${run.failed} failed")
                if (run.geotagged > 0) add("${run.geotagged} with location")
            }.joinToString(" · ")
        } ?: ""
    }
    Column(modifier.fillMaxWidth().heightIn(min = 50.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = Mono.data(15.sp, FontWeight.SemiBold), color = Mono.c.ink, maxLines = 2)
                Text(subtitle, style = Mono.data(12.sp), color = Mono.c.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            if (running || paused) OutlineChip("Cancel") { imp.cancelAll() }
            else OutlineChip("Open Photos", filled = true) {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
        if (running || paused) {
            ProgressLine(if (paused) 1f * imp.completedInRun / maxOf(1, imp.completedInRun + imp.waitingCount) else imp.progress.toFloat(),
                color = if (paused) Mono.c.dim else Mono.c.accent)
        }
    }
}

/** Floating import status, shown only when no library screen is there to host it. */
@Composable
private fun ImportHUD(app: AppState) {
    val show = app.session == null && app.importVisible
    AnimatedVisibility(
        show,
        enter = slideInVertically(tween(300)) { it / 2 } + fadeIn(tween(300)),
        exit = slideOutVertically(tween(250)) { it / 2 } + fadeOut(tween(200)),
    ) {
        Box(Modifier.padding(horizontal = 12.dp).widthIn(max = 560.dp).overlayPanel(RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
            ImportStatusBar(app)
        }
    }
}

@Composable
private fun Toast(app: AppState) {
    val text = app.toast ?: app.importer.lastError
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(text) {
        if (text != null) { shown = text; delay(3500); app.toast = null; app.importer.lastError = null }
    }
    AnimatedVisibility(
        text != null,
        enter = slideInVertically(tween(300)) { it / 2 } + fadeIn(tween(300)),
        exit = slideOutVertically(tween(250)) { it / 2 } + fadeOut(tween(200)),
    ) {
        Text(
            shown ?: "", Modifier.padding(horizontal = 20.dp).widthIn(max = 560.dp).overlayPanel().padding(horizontal = 16.dp, vertical = 11.dp),
            style = Mono.subhead.copy(fontWeight = FontWeight.Medium), color = Mono.c.ink, textAlign = TextAlign.Center,
        )
    }
}
