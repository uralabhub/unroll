package app.unroll.ui

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.unroll.AppState
import app.unroll.Screen
import app.unroll.core.HeaderParser
import app.unroll.data.Quota
import app.unroll.media.ImageDecode
import app.unroll.source.UsbCameraService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The screen when nothing is connected: what to do (plug in), the other ways in, and the tools.
 * Blocks rise in on arrival; only the "waiting for a camera" dot keeps pulsing while it listens.
 */
@Composable
fun HomeScreen(app: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        app.openFolder(uri)
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val data = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
                ?: return@launch
            val img = withContext(Dispatchers.Default) { ImageDecode.decode(data, 4096) } ?: return@launch
            val meta = withContext(Dispatchers.Default) { HeaderParser.parse(data.copyOf(minOf(data.size, HeaderParser.HEADER_LENGTH))).meta }
            app.push(Screen.Frame(img, meta, null))
        }
    }
    val nav = WindowInsets.navigationBars.asPaddingValues()

    Column(Modifier.fillMaxSize().background(Mono.c.bg)) {
        // Title bar: status-bar inset outside the scroll, so content never slides under the clock.
        Row(
            Modifier.statusBarsPadding().fillMaxWidth().heightIn(min = 64.dp).padding(start = Mono.gutter + 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Unroll", Modifier.weight(1f).riseIn(0, "home-title").semantics { heading() }, style = Mono.largeTitle, color = Mono.c.ink)
            Box(Modifier.riseIn(1, "home-gear")) { IconButton(Ph.gear, "Settings") { app.push(Screen.Settings) } }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = nav.calculateBottomPadding() + 112.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "connect") {
                Box(Modifier.widthIn(max = 640.dp).padding(horizontal = Mono.gutter).fillMaxWidth().riseIn(1, "connect").card().animateContentSize(Motion.float())) {
                    ConnectPanel(app, pickFolder = { pickFolder.launch(null) })
                }
            }
            item(key = "tools-title") { Box(Modifier.riseIn(3, "tools-title")) { GroupTitle("Tools") } }
            item(key = "tools") {
                Column(Modifier.widthIn(max = 640.dp).padding(horizontal = Mono.gutter).fillMaxWidth().riseIn(4, "tools").card()) {
                    Box(Modifier.riseIn(5, "row-walk")) { PhotoWalkRow(app) }
                    Hairline(Modifier.padding(start = 56.dp))
                    ToolRow(Ph.path, "Walk history", app.walks.walks.size, 6) { app.push(Screen.Walks) }
                    Hairline(Modifier.padding(start = 56.dp))
                    ToolRow(Ph.film, "Recipes", app.recipes.items.size, 7) { app.push(Screen.Recipes) }
                    Hairline(Modifier.padding(start = 56.dp))
                    ToolRow(Ph.timer, "Clock sync", app.settings.clockOffsets.size, 8) { app.push(Screen.ClockSync) }
                    Hairline(Modifier.padding(start = 56.dp))
                    ToolRow(Ph.frame, "Frame a photo", 0, 8) {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
            }
            if (!app.pro.isPro) {
                item(key = "quota") {
                    Column(Modifier.widthIn(max = 640.dp).padding(top = 28.dp).riseIn(8, "quota")) {
                        Row(
                            Modifier.padding(horizontal = Mono.gutter).fillMaxWidth().pressDim(scale = 0.98f) { app.showPaywall = true }.card()
                                .heightIn(min = 56.dp).padding(horizontal = Mono.gutter, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                AnimatedCount(app.quota.remaining, Mono.data(15.sp), Mono.c.ink, suffix = " of ${Quota.FREE_PER_DAY} free imports left today")
                                Text("Pro: unlimited imports, auto-import picks, no watermark", style = Mono.footnote, color = Mono.c.dim)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text("Get Pro", style = Mono.subhead.copy(fontWeight = FontWeight.SemiBold), color = Mono.c.ink)
                        }
                        Footer("No account. No cloud. Your originals are never modified.")
                    }
                }
            } else {
                item(key = "footer") { Box(Modifier.widthIn(max = 640.dp).padding(top = 20.dp).riseIn(8, "footer")) { Footer("No account. No cloud. Your originals are never modified.") } }
            }
        }
    }
}


@Composable
private fun GroupTitle(text: String) {
    Box(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(start = Mono.gutter * 2, end = Mono.gutter, top = 28.dp, bottom = 8.dp)) {
        Text(text, Modifier.semantics { heading() }, style = Mono.footnote.copy(fontWeight = FontWeight.SemiBold), color = Mono.c.dim)
    }
}

@Composable
private fun Footer(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = Mono.gutter * 2, vertical = 8.dp), style = Mono.footnote, color = Mono.c.dim)
}

/** Icon + title + optional value, in a list row. */
@Composable
private fun ToolRow(icon: Int, title: String, value: Int, index: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().riseIn(index, "tool-$title").pressDim(scale = 0.98f, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = Mono.gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhIcon(icon, null, size = 20.dp, tint = Mono.c.dim)
        Spacer(Modifier.width(20.dp))
        Text(title, Modifier.weight(1f), style = Mono.body, color = Mono.c.ink)
        if (value > 0) AnimatedCount(value, Mono.data(16.sp), Mono.c.dim)
        Spacer(Modifier.width(10.dp))
        PhIcon(Ph.chevron, null, size = 13.dp, tint = Mono.c.faint)
    }
}

/** "Connect your camera": the one thing to do, then the other ways in. */
@Composable
private fun ConnectPanel(app: AppState, pickFolder: () -> Unit) {
    val usb = app.usb
    val denied = usb.authorization == UsbCameraService.Authorization.DENIED
    val attached = usb.attached
    var opening by remember { mutableStateOf<String?>(null) }
    // The library replaces Home once the session exists; the spinner covers the gap until then.
    LaunchedEffect(opening) { if (opening != null) { delay(8000); opening = null } }

    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Pulses while listening for a camera; steady once one is found or access is off.
            val dot by animateColorAsState(if (denied) Mono.c.faint else Mono.c.accent, Motion.snap(), label = "dot")
            Box(Modifier.popOn(attached != null, 1.8f)) { PulseDot(dot, live = !denied && attached == null) }
            Spacer(Modifier.width(8.dp))
            AnimatedContent(
                when {
                    denied -> "USB access is off"
                    attached != null -> "Camera found"
                    else -> "Ready. Waiting for a camera"
                },
                label = "connectStatus",
                transitionSpec = { (fadeIn(tween(220)) + slideInVertically(Motion.bouncy()) { it / 2 }).togetherWith(fadeOut(tween(120)) + slideOutVertically(tween(150)) { -it / 2 }) },
            ) { t -> Text(t, style = Mono.footnote.copy(fontWeight = FontWeight.Medium), color = Mono.c.dim) }
        }
        Text("Connect your camera", Modifier.padding(top = 14.dp).semantics { heading() }, style = Mono.title, color = Mono.c.ink)
        Text(
            "Plug it in with a USB‑C cable. Newest photos show up first; RAW, star ratings and protected shots come across as they are. Nothing to pair.",
            Modifier.padding(top = 6.dp), style = Mono.subhead, color = Mono.c.dim,
        )
        AnimatedVisibility(usb.lastError != null, enter = fadeIn(tween(200)) + expandVertically(Motion.float()), exit = fadeOut(tween(150)) + shrinkVertically(tween(200))) {
            Text(usb.lastError ?: "", Modifier.padding(top = 10.dp), style = Mono.footnote, color = Mono.c.ink)
        }

        AnimatedVisibility(
            attached != null || denied,
            enter = fadeIn(tween(220)) + expandVertically(Motion.float()) + scaleIn(Motion.bouncy(), initialScale = 0.9f),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(220)),
        ) {
            PrimaryButton(
                if (denied) "Allow USB access" else "Open ${attached?.productName ?: "camera"}",
                Modifier.fillMaxWidth().padding(top = 18.dp),
            ) {
                usb.lastError = null
                attached?.let(usb::connect) ?: usb.scanAttached(any = true)
            }
        }

        // Brand-specific ways in (Ricoh GR over Wi‑Fi) live in Settings › Connections, not here.
        SecondaryButton("Open an SD card", Modifier.fillMaxWidth().padding(top = 20.dp), icon = Ph.sd, enabled = opening == null, onClick = pickFolder)
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp).pressDim(enabled = opening == null) { opening = "demo"; app.openDemo() },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            if (opening == "demo") { Spinner(Mono.c.dim, 14.dp); Spacer(Modifier.width(8.dp)) }
            Text("No camera handy? Try the demo roll", style = Mono.footnote.copy(fontWeight = FontWeight.Medium), color = Mono.c.dim)
        }
    }
}

/** Photo walk on/off in a list row; the timer only ticks while recording. */
@Composable
private fun PhotoWalkRow(app: AppState) {
    val rec = app.walks
    val view = androidx.compose.ui.platform.LocalView.current
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true) rec.startWalk() else app.toast = "Allow location to log a walk."
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = Mono.gutter, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint by animateColorAsState(if (rec.isRecording) Mono.c.accent else Mono.c.dim, Motion.snap(), label = "walkTint")
        PhIcon(Ph.walk, null, Modifier.breathe(rec.isRecording).popOn(rec.isRecording, 1.3f), size = 20.dp, tint = tint)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Photo walk", style = Mono.body, color = Mono.c.ink)
            AnimatedContent(rec.isRecording, label = "walkDetail", transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically(Motion.bouncy()) { it / 2 }).togetherWith(fadeOut(tween(120)))
                    .using(SizeTransform(clip = false))
            }) { recording ->
                if (recording) WalkTimer(app)
                else Text("Log your route; imports get locations", style = Mono.footnote, color = Mono.c.dim)
            }
        }
        Spacer(Modifier.width(8.dp))
        OutlineChip(if (rec.isRecording) "Stop" else "Start", filled = rec.isRecording) {
            Haptics.confirm(view)
            when {
                rec.isRecording -> rec.stopWalk()
                rec.hasPermission -> rec.startWalk()
                else -> perm.launch(
                    listOfNotNull(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.POST_NOTIFICATIONS.takeIf { android.os.Build.VERSION.SDK_INT >= 33 }).toTypedArray(),
                )
            }
        }
    }
}

/** Ticks once a second, and only exists while a walk is recording. */
@Composable
private fun WalkTimer(app: AppState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val w = app.walks.current ?: return
    val secs = w.durationMillis(now) / 1000
    Text(
        String.format(Locale.US, "Recording · %d:%02d:%02d · %.2f km", secs / 3600, secs % 3600 / 60, secs % 60, w.distanceMeters / 1000),
        style = Mono.data(13.sp), color = Mono.c.accent,
    )
}
