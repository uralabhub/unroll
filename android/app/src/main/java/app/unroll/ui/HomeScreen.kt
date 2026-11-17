package app.unroll.ui

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
 * One calm list; nothing animates while it sits there.
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
            Text("Unroll", Modifier.weight(1f).semantics { heading() }, style = Mono.largeTitle, color = Mono.c.ink)
            IconButton(Ph.gear, "Settings") { app.push(Screen.Settings) }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = nav.calculateBottomPadding() + 112.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "connect") {
                Box(Modifier.widthIn(max = 640.dp).padding(horizontal = Mono.gutter).fillMaxWidth().card()) {
                    ConnectPanel(app, pickFolder = { pickFolder.launch(null) })
                }
            }
            item(key = "tools-title") { GroupTitle("Tools") }
            item(key = "tools") {
                Column(Modifier.widthIn(max = 640.dp).padding(horizontal = Mono.gutter).fillMaxWidth().card()) {
                    PhotoWalkRow(app)
                    Hairline(Modifier.padding(start = 56.dp))
                    ToolRow(Ph.path, "Walk history", count(app.walks.walks.size)) { app.push(Screen.Walks) }
                    Hairline(Modifier.padding(start = 56.dp))
                    ToolRow(Ph.film, "Recipes", count(app.recipes.items.size)) { app.push(Screen.Recipes) }
                    Hairline(Modifier.padding(start = 56.dp))
                    ToolRow(Ph.timer, "Clock sync", count(app.settings.clockOffsets.size)) { app.push(Screen.ClockSync) }
                    Hairline(Modifier.padding(start = 56.dp))
                    ToolRow(Ph.frame, "Frame a photo", null) {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
            }
            if (!app.pro.isPro) {
                item(key = "quota") {
                    Column(Modifier.widthIn(max = 640.dp).padding(top = 28.dp)) {
                        Row(
                            Modifier.padding(horizontal = Mono.gutter).fillMaxWidth().card().pressDim { app.showPaywall = true }
                                .heightIn(min = 56.dp).padding(horizontal = Mono.gutter, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${app.quota.remaining} of ${Quota.FREE_PER_DAY} free imports left today", style = Mono.data(15.sp), color = Mono.c.ink)
                                Text("Pro: unlimited imports, auto-import picks, no watermark", style = Mono.footnote, color = Mono.c.dim)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text("Get Pro", style = Mono.subhead.copy(fontWeight = FontWeight.SemiBold), color = Mono.c.ink)
                        }
                        Footer("No account. No cloud. Your originals are never modified.")
                    }
                }
            } else {
                item(key = "footer") { Box(Modifier.widthIn(max = 640.dp).padding(top = 20.dp)) { Footer("No account. No cloud. Your originals are never modified.") } }
            }
        }
    }
}

private fun count(n: Int): String? = if (n > 0) "$n" else null

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
private fun ToolRow(icon: Int, title: String, value: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressDim(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = Mono.gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhIcon(icon, null, size = 20.dp, tint = Mono.c.dim)
        Spacer(Modifier.width(20.dp))
        Text(title, Modifier.weight(1f), style = Mono.body, color = Mono.c.ink)
        value?.let { Text(it, style = Mono.data(16.sp), color = Mono.c.dim) }
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
            Box(Modifier.size(7.dp).background(if (denied) Mono.c.faint else Mono.c.accent, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    denied -> "USB access is off"
                    attached != null -> "Camera found"
                    else -> "Ready. Waiting for a camera"
                },
                style = Mono.footnote.copy(fontWeight = FontWeight.Medium), color = Mono.c.dim,
            )
        }
        Text("Connect your camera", Modifier.padding(top = 14.dp).semantics { heading() }, style = Mono.title, color = Mono.c.ink)
        Text(
            "Plug it in with a USB‑C cable. Newest photos show up first; RAW, star ratings and protected shots come across as they are. Nothing to pair.",
            Modifier.padding(top = 6.dp), style = Mono.subhead, color = Mono.c.dim,
        )
        usb.lastError?.let { Text(it, Modifier.padding(top = 10.dp), style = Mono.footnote, color = Mono.c.ink) }

        if (attached != null || denied) {
            PrimaryButton(
                if (denied) "Allow USB access" else "Open ${attached?.productName ?: "camera"}",
                Modifier.fillMaxWidth().padding(top = 18.dp),
            ) {
                usb.lastError = null
                attached?.let(usb::connect) ?: usb.scanAttached(any = true)
            }
        }

        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("SD card", Modifier.weight(1f), icon = Ph.sd, enabled = opening == null, onClick = pickFolder)
            SecondaryButton("Ricoh Wi‑Fi", Modifier.weight(1f), icon = Ph.wifi, enabled = opening == null, busy = opening == "ricoh") {
                opening = "ricoh"; app.connectRicoh()
            }
        }
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
        PhIcon(Ph.walk, null, size = 20.dp, tint = if (rec.isRecording) Mono.c.accent else Mono.c.dim)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Photo walk", style = Mono.body, color = Mono.c.ink)
            if (rec.isRecording) WalkTimer(app)
            else Text("Log your route; imports get locations", style = Mono.footnote, color = Mono.c.dim)
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
