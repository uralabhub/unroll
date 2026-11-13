package app.unroll.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.unroll.AppState
import app.unroll.Screen
import kotlinx.coroutines.delay
import java.util.Locale

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
    Box(Modifier.fillMaxSize().background(Mono.c.bg)) {
        if (session != null) LibraryScreen(app, session) else HomeScreen(app)
        // Pushed screens cover the base; each is a plain full-screen page.
        when (top) {
            is Screen.Detail -> ShotPagerScreen(app, top.shots, top.startId)
            is Screen.Frame -> FrameStudioScreen(app, top.image, top.meta, top.location)
            is Screen.Recap -> RecapScreen(app, top.shots)
            Screen.Recipes -> RecipesScreen(app)
            is Screen.RecipeDetail -> RecipeDetailScreen(app, top.id)
            Screen.ClockSync -> ClockSyncScreen(app)
            Screen.Settings -> SettingsScreen(app)
            Screen.Walks -> WalksScreen(app)
            null -> Unit
        }
        AnimatedVisibility(app.showPaywall, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            PaywallSheet(app) { app.showPaywall = false }
        }
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 78.dp)) { ImportHUD(app) }
        Box(Modifier.align(Alignment.TopCenter).statusBarsPadding()) { Toast(app) }
    }
}

/** Import progress card above the action bar; "N saved" for a few seconds when a run ends. */
@Composable
private fun ImportHUD(app: AppState) {
    val imp = app.importer
    val context = LocalContext.current
    var showDone by remember { mutableStateOf(false) }
    val running = imp.isRunning
    LaunchedEffect(running) {
        if (!running && imp.lastFinishedRun != null) { showDone = true; delay(5000); showDone = false }
    }
    AnimatedVisibility(running || showDone, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        Column(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(Mono.radius)).background(Mono.c.bg)
                .border(1.dp, Mono.c.line, RoundedCornerShape(Mono.radius)),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Importing ${minOf(imp.completedInRun + 1, imp.totalInRun)} of ${imp.totalInRun}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Mono.c.ink)
                        Text(imp.throughputMBps?.let { String.format(Locale.US, "%.0f MB/s", it) } ?: (imp.current?.primary?.name ?: ""), style = Mono.data(11.sp), color = Mono.c.dim)
                    }
                    OutlineChip("Cancel") { imp.cancelAll() }
                } else imp.lastFinishedRun?.let { run ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${run.count} saved to your library", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Mono.c.ink)
                        Text(String.format(Locale.US, "%.1f s", run.seconds) + if (run.geotagged > 0) "  ·  ${run.geotagged} with location" else "  ·  originals untouched",
                            style = Mono.data(11.sp), color = Mono.c.dim)
                    }
                    OutlineChip("Open Photos", filled = true) {
                        runCatching {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }
            }
            if (running) {
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    Box(Modifier.fillMaxWidth(imp.progress.toFloat().coerceIn(0f, 1f)).height(2.dp).background(Mono.c.ink))
                }
            }
        }
    }
}

@Composable
private fun Toast(app: AppState) {
    val text = app.toast ?: app.importer.lastError
    LaunchedEffect(text) {
        if (text != null) { delay(3000); app.toast = null; app.importer.lastError = null }
    }
    AnimatedVisibility(text != null, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
        Text(
            text ?: "", Modifier.padding(top = 6.dp, start = 16.dp, end = 16.dp).background(Mono.c.ink, RoundedCornerShape(Mono.radius)).padding(horizontal = 16.dp, vertical = 10.dp),
            fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Mono.c.bg,
        )
    }
    Spacer(Modifier.height(0.dp))
}
