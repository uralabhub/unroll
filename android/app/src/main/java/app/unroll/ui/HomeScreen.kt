package app.unroll.ui

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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
            val meta = HeaderParser.parse(data.copyOf(minOf(data.size, HeaderParser.HEADER_LENGTH))).meta
            app.push(Screen.Frame(img, meta, null))
        }
    }

    Column(
        // Status-bar inset outside the scroll, so content never slides under the clock.
        Modifier.fillMaxSize().background(Mono.c.bg).statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 120.dp),
    ) {
        Row(Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp, bottom = 20.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                CapsLabel(LocalDate.now().format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())))
                Text("Unroll", fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.2).sp, color = Mono.c.ink)
            }
            Box(
                Modifier.padding(bottom = 4.dp).size(40.dp).background(Mono.c.tile, CircleShape).tilePress { app.push(Screen.Settings) },
                contentAlignment = Alignment.Center,
            ) { Glyph(GlyphKind.GEAR, Modifier.size(19.dp)) }
        }

        ConnectHero(app)

        BlockTitle("Or import from")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(GlyphKind.CARD, "SD card", "Reader or folder", modifier = Modifier.weight(1f)) { pickFolder.launch(null) }
            Tile(GlyphKind.WIFI, "Ricoh GR", "Camera Wi-Fi", tag = "Beta", modifier = Modifier.weight(1f)) { app.connectRicoh() }
        }

        BlockTitle("Photo walk")
        PhotoWalkCard(app)

        BlockTitle("Tools")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile(GlyphKind.RECIPES, "Recipes", "From your shots", count = app.recipes.items.size, modifier = Modifier.weight(1f)) { app.push(Screen.Recipes) }
                Tile(GlyphKind.CLOCK, "Clock sync", "Fix time drift", count = app.settings.clockOffsets.size, modifier = Modifier.weight(1f)) { app.push(Screen.ClockSync) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile(GlyphKind.FRAME, "Frame", "EXIF border", modifier = Modifier.weight(1f)) {
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                Tile(GlyphKind.MAP, "Walks", "Routes, recaps", count = app.walks.walks.size, modifier = Modifier.weight(1f)) { app.push(Screen.Walks) }
            }
        }

        if (!app.pro.isPro) {
            Spacer(Modifier.height(28.dp))
            QuotaCard(app)
        }

        Text(
            "NO ACCOUNT · NO CLOUD · ORIGINALS UNTOUCHED", style = Mono.caps(10.sp), color = Mono.c.faint,
            modifier = Modifier.fillMaxWidth().padding(top = 32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun BlockTitle(text: String) = CapsLabel(text, Modifier.padding(start = 4.dp, top = 28.dp, bottom = 10.dp))

/** Top of Home: a dark strip of film that says what to do. */
@Composable
private fun ConnectHero(app: AppState) {
    val usb = app.usb
    val denied = usb.authorization == UsbCameraService.Authorization.DENIED
    val attached = usb.attached
    val blink by rememberInfiniteTransition(label = "blink").animateFloat(1f, 0.15f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot")
    val white = Color.White
    Column(Modifier.fillMaxWidth().background(Mono.c.hero, RoundedCornerShape(24.dp))) {
        Sprockets()
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).alpha(if (denied) 0.4f else blink).background(white, CircleShape))
                Spacer(Modifier.width(8.dp))
                CapsLabel(
                    when {
                        denied -> "USB access off"
                        attached != null -> "Camera found"
                        else -> "Waiting for a camera"
                    },
                    color = white.copy(alpha = 0.7f), size = 10.sp,
                )
                Spacer(Modifier.weight(1f))
                CapsLabel("USB-C", color = white.copy(alpha = 0.4f), size = 10.sp)
            }
            Text(
                "Plug in\nyour camera.", Modifier.padding(top = 22.dp), fontSize = 36.sp, lineHeight = 38.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp, color = white,
            )
            Text(
                "Newest shots load first. RAW, star ratings and locked frames come across as they are.",
                Modifier.padding(top = 10.dp), fontSize = 15.sp, color = white.copy(alpha = 0.55f),
            )
            Text(
                "FUJIFILM  SONY  CANON  NIKON  LEICA  RICOH  OM  LUMIX", Modifier.padding(top = 18.dp),
                style = Mono.caps(9.sp).copy(letterSpacing = 0.6.sp, lineHeight = 15.sp), color = white.copy(alpha = 0.35f),
            )
            usb.lastError?.let { Text(it, Modifier.padding(top = 14.dp), fontSize = 13.sp, color = white.copy(alpha = 0.7f)) }
            Spacer(Modifier.height(22.dp))
            if (attached != null || denied) {
                HeroButton(if (denied) "Allow USB access" else "Open ${attached?.productName ?: "camera"}") {
                    usb.lastError = null
                    attached?.let(usb::connect) ?: usb.scanAttached(any = true)
                }
                Spacer(Modifier.height(10.dp))
            }
            HeroButton("No camera? Open the demo roll", arrow = true) { app.openDemo() }
        }
        Sprockets()
    }
}

@Composable
private fun HeroButton(text: String, arrow: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).background(Color.White, RoundedCornerShape(14.dp)).tilePress(onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (arrow) Arrangement.SpaceBetween else Arrangement.Center,
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.Black)
        if (arrow) Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.Black, modifier = Modifier.size(18.dp))
    }
}

/** Row of film perforations along the hero's edge. */
@Composable
private fun Sprockets() {
    Row(Modifier.fillMaxWidth().padding(10.dp)) {
        repeat(16) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(Modifier.size(10.dp, 6.dp).background(Color.White.copy(alpha = 0.13f), RoundedCornerShape(1.5.dp)))
            }
        }
    }
}

/** GPS logger: a big running clock and a single round start / stop control. */
@Composable
private fun PhotoWalkCard(app: AppState) {
    val rec = app.walks
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(rec.isRecording) { while (rec.isRecording) { now = System.currentTimeMillis(); delay(1000) } }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true) rec.startWalk() else app.toast = "Allow location to log a walk."
    }
    val w = rec.current
    Row(
        Modifier.fillMaxWidth().background(Mono.c.tile, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rec.isRecording) { Box(Modifier.size(6.dp).background(Mono.c.ink, CircleShape)); Spacer(Modifier.width(6.dp)) }
                CapsLabel(if (rec.isRecording) "Recording" else "GPS log", color = if (rec.isRecording) Mono.c.ink else Mono.c.dim, size = 10.sp)
            }
            val secs = (w?.durationMillis(now) ?: 0) / 1000
            Text(
                String.format(Locale.US, "%02d:%02d:%02d", secs / 3600, secs % 3600 / 60, secs % 60),
                style = Mono.data(30.sp), color = if (w == null) Mono.c.faint else Mono.c.ink,
            )
            if (w != null) {
                Text(String.format(Locale.US, "%.2f km  ·  %d fixes", w.distanceMeters / 1000, w.points.size), style = Mono.data(12.sp), color = Mono.c.dim)
            } else {
                Text("Log your route while you shoot. Imports get locations on their own.", fontSize = 13.sp, color = Mono.c.dim)
            }
        }
        Spacer(Modifier.width(16.dp))
        Box(
            Modifier.size(56.dp).background(Mono.c.ink, CircleShape).tilePress {
                when {
                    rec.isRecording -> rec.stopWalk()
                    rec.hasPermission -> rec.startWalk()
                    else -> perm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            if (rec.isRecording) Box(Modifier.size(16.dp).background(Mono.c.bg, RoundedCornerShape(2.dp)))
            else Icon(Icons.Filled.PlayArrow, "Start walk", tint = Mono.c.bg)
        }
    }
}

/** Free tier meter: ten segments, one per import left today. */
@Composable
private fun QuotaCard(app: AppState) {
    val left = app.quota.remaining
    val total = Quota.FREE_PER_DAY
    Column(
        Modifier.fillMaxWidth().border(1.dp, Mono.c.line, RoundedCornerShape(18.dp)).clickable { app.showPaywall = true }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            CapsLabel("Free today", size = 10.sp)
            Spacer(Modifier.weight(1f))
            Text("$left of $total left", style = Mono.data(12.sp, FontWeight.Medium), color = Mono.c.ink)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(total) { i ->
                Box(Modifier.weight(1f).height(4.dp).background(if (i < left) Mono.c.ink else Mono.c.ink.copy(alpha = 0.12f), CircleShape))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Unlimited imports, auto-import picks, every frame, no watermark.", Modifier.weight(1f), fontSize = 13.sp, color = Mono.c.dim)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.height(32.dp).background(Mono.c.ink, CircleShape).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text("Get Pro", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Mono.c.bg)
            }
        }
    }
}
