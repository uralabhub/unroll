package app.unroll.ui

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(app: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
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
        Modifier.fillMaxSize().background(Mono.c.bg).statusBarsPadding().verticalScroll(scroll)
            .padding(horizontal = 16.dp).padding(bottom = 120.dp),
    ) {
        Row(Modifier.riseIn(0).padding(top = 10.dp, start = 4.dp, end = 0.dp, bottom = 18.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                CapsLabel(greeting() + " · " + LocalDate.now().format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())))
                Text("Unroll", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, color = Mono.c.ink)
            }
            Box(Modifier.padding(bottom = 6.dp).size(44.dp).card(CircleShape, 6.dp).tilePress(0.9f) { app.push(Screen.Settings) }, contentAlignment = Alignment.Center) {
                PhIcon(Ph.gear, "Settings", size = 22.dp)
            }
        }

        ConnectHero(app, scroll, Modifier.riseIn(1))

        BlockTitle("Or import from", 2)
        Row(Modifier.riseIn(3), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(GlyphKind.CARD, "SD card", "Reader or folder", modifier = Modifier.weight(1f)) { pickFolder.launch(null) }
            Tile(GlyphKind.WIFI, "Ricoh GR", "Camera Wi-Fi", tag = "Beta", modifier = Modifier.weight(1f)) { app.connectRicoh() }
        }

        BlockTitle("Photo walk", 4)
        PhotoWalkCard(app, Modifier.riseIn(5))

        BlockTitle("Tools", 6)
        Column(Modifier.riseIn(7), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(GlyphKind.RECIPES, "Recipes", "From your shots", count = app.recipes.items.size, modifier = Modifier.weight(1f)) { app.push(Screen.Recipes) }
                Tile(GlyphKind.CLOCK, "Clock sync", "Fix time drift", count = app.settings.clockOffsets.size, modifier = Modifier.weight(1f)) { app.push(Screen.ClockSync) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(GlyphKind.FRAME, "Frame", "EXIF border", modifier = Modifier.weight(1f)) {
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                Tile(GlyphKind.MAP, "Walks", "Routes, recaps", count = app.walks.walks.size, modifier = Modifier.weight(1f)) { app.push(Screen.Walks) }
            }
        }

        if (!app.pro.isPro) {
            Spacer(Modifier.height(28.dp))
            QuotaCard(app, Modifier.riseIn(8))
        }

        Text(
            "No account · No cloud · Originals untouched", style = Mono.caps(10.sp), color = Mono.c.faint,
            modifier = Modifier.fillMaxWidth().padding(top = 32.dp).riseIn(9), textAlign = TextAlign.Center,
        )
        Spacer(Modifier.navigationBarsPadding())
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Morning"
    in 12..17 -> "Afternoon"
    else -> "Evening"
}

@Composable
private fun BlockTitle(text: String, index: Int) =
    CapsLabel(text, Modifier.padding(start = 6.dp, top = 30.dp, bottom = 12.dp).riseIn(index))

/**
 * Top of Home: a strip of film that says what to do. The perforations advance slowly, like a
 * film being wound on; the card drifts and fades as the page scrolls over it.
 */
@Composable
private fun ConnectHero(app: AppState, scroll: ScrollState, modifier: Modifier) {
    val usb = app.usb
    val denied = usb.authorization == UsbCameraService.Authorization.DENIED
    val attached = usb.attached
    val reduce = LocalReduceMotion.current
    val (tiltX, tiltY) = rememberTilt()
    val t = rememberInfiniteTransition(label = "hero")
    val blink by t.animateFloat(1f, 0.15f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot")
    val wind by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (attached != null) 900 else 5200, easing = LinearEasing)), label = "wind")
    val white = Color.White
    val phase = if (reduce) 0f else wind
    Column(
        modifier.fillMaxWidth()
            .graphicsLayer {
                val y = scroll.value.toFloat()
                translationY = y * 0.35f
                alpha = (1f - y / 900f).coerceIn(0f, 1f)
                val s = 1f - (y / 6000f).coerceIn(0f, 0.06f)
                scaleX = s; scaleY = s
                // Gyroscope parallax: the card leans up to 4° with the phone, like a print in the hand.
                rotationX = tiltX; rotationY = tiltY
                cameraDistance = 14f * density
            }
            // Layered shadow: a wide soft one far below, a tighter one close under the card.
            .shadow(28.dp, RoundedCornerShape(28.dp), clip = false, ambientColor = Mono.c.shadow, spotColor = Mono.c.shadow)
            .card(RoundedCornerShape(28.dp), 8.dp)
            .background(Mono.c.hero),
    ) {
        Sprockets(phase)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).graphicsLayer { alpha = if (denied) 0.4f else if (reduce) 1f else blink }.background(white, CircleShape))
                Spacer(Modifier.width(8.dp))
                Crossfade(
                    when {
                        denied -> "USB access off"
                        attached != null -> "Camera found"
                        else -> "Waiting for a camera"
                    }, label = "status",
                ) { CapsLabel(it, color = white.copy(alpha = 0.75f), size = 10.sp) }
                Spacer(Modifier.weight(1f))
                PhIcon(Ph.usb, "USB-C", size = 18.dp, tint = white.copy(alpha = 0.45f))
            }
            Text(
                "Plug in\nyour camera.", Modifier.padding(top = 22.dp), fontSize = 38.sp, lineHeight = 40.sp,
                fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, color = white,
            )
            Text(
                "Newest shots load first. RAW, star ratings and locked frames come across as they are.",
                Modifier.padding(top = 10.dp), fontSize = 15.sp, lineHeight = 21.sp, color = white.copy(alpha = 0.6f),
            )
            Text(
                "FUJIFILM  SONY  CANON  NIKON  LEICA  RICOH  OM  LUMIX", Modifier.padding(top = 16.dp),
                style = Mono.caps(9.sp).copy(letterSpacing = 0.8.sp, lineHeight = 15.sp), color = white.copy(alpha = 0.35f),
            )
            usb.lastError?.let { Text(it, Modifier.padding(top = 14.dp), fontSize = 13.sp, color = white.copy(alpha = 0.75f)) }
            Spacer(Modifier.height(22.dp))
            AnimatedVisibility(attached != null || denied, enter = expandVertically(Mono.press()) + fadeIn()) {
                Column {
                    HeroButton(if (denied) "Allow USB access" else "Open ${attached?.productName ?: "camera"}", Ph.plug) {
                        usb.lastError = null
                        attached?.let(usb::connect) ?: usb.scanAttached(any = true)
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
            HeroButton("No camera? Open the demo roll", Ph.forward) { app.openDemo() }
        }
        Sprockets(phase)
    }
}

@Composable
private fun HeroButton(text: String, icon: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).tilePress(0.97f, onClick = onClick).background(Color.White, RoundedCornerShape(16.dp)).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
        PhIcon(icon, null, size = 18.dp, tint = Color.Black)
    }
}

/** Film perforations; [phase] 0→1 slides them one hole to the left (seamless loop). */
@Composable
private fun Sprockets(phase: Float) {
    Canvas(Modifier.fillMaxWidth().height(26.dp)) {
        val pitch = size.width / 15f
        val w = 10.dp.toPx(); val h = 6.dp.toPx()
        val y = (size.height - h) / 2
        var x = -phase * pitch + (pitch - w) / 2
        while (x < size.width) {
            drawRoundRect(Color.White.copy(alpha = 0.14f), Offset(x, y), Size(w, h), CornerRadius(1.5.dp.toPx()))
            x += pitch
        }
    }
}

/** GPS logger: a big running clock and one control that morphs between start (circle) and stop (rounded square). */
@Composable
private fun PhotoWalkCard(app: AppState, modifier: Modifier) {
    val rec = app.walks
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(rec.isRecording) { while (rec.isRecording) { now = System.currentTimeMillis(); delay(1000) } }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true) rec.startWalk() else app.toast = "Allow location to log a walk."
    }
    val w = rec.current
    val view = androidx.compose.ui.platform.LocalView.current
    val corner by animateDpAsState(if (rec.isRecording) 18.dp else 30.dp, Mono.press(), label = "morph")
    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(1f, 1.18f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "pulse")
    Row(
        modifier.fillMaxWidth().card().padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rec.isRecording) {
                    Box(Modifier.size(7.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }.background(Mono.c.ink, CircleShape))
                    Spacer(Modifier.width(7.dp))
                }
                CapsLabel(if (rec.isRecording) "Recording" else "GPS log", color = if (rec.isRecording) Mono.c.ink else Mono.c.dim, size = 10.sp)
            }
            val secs = (w?.durationMillis(now) ?: 0) / 1000
            Text(
                String.format(Locale.US, "%02d:%02d:%02d", secs / 3600, secs % 3600 / 60, secs % 60),
                style = Mono.data(32.sp, FontWeight.ExtraBold), color = if (w == null) Mono.c.faint else Mono.c.ink,
            )
            Crossfade(w != null, label = "walkText") { recording ->
                if (recording && w != null) {
                    Text(String.format(Locale.US, "%.2f km  ·  %d fixes", w.distanceMeters / 1000, w.points.size), style = Mono.data(13.sp), color = Mono.c.dim)
                } else {
                    Text("Log your route while you shoot. Imports get locations on their own.", fontSize = 13.sp, lineHeight = 18.sp, color = Mono.c.dim)
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Box(
            Modifier.size(60.dp).tilePress(0.9f) {
                Haptics.confirm(view)
                when {
                    rec.isRecording -> rec.stopWalk()
                    rec.hasPermission -> rec.startWalk()
                    else -> perm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
                }
            }.clip(RoundedCornerShape(corner)).background(Mono.c.ink),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(rec.isRecording, label = "walkIcon") { on ->
                PhIcon(if (on) Ph.stop else Ph.play, if (on) "Stop walk" else "Start walk", size = 22.dp, tint = Mono.c.bg)
            }
        }
    }
}

/** Free tier meter: ten segments that fill one after another. */
@Composable
private fun QuotaCard(app: AppState, modifier: Modifier) {
    val left = app.quota.remaining
    val total = Quota.FREE_PER_DAY
    Column(
        modifier.fillMaxWidth().tilePress(0.98f) { app.showPaywall = true }.card().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CapsLabel("Free today", size = 10.sp)
            Spacer(Modifier.weight(1f))
            AnimatedCount(left, Mono.data(14.sp, FontWeight.ExtraBold), Mono.c.ink)
            Text(" of $total left", style = Mono.data(14.sp, FontWeight.Bold), color = Mono.c.dim)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(total) { i ->
                val fill by animateFloatAsState(if (i < left) 1f else 0f, tween(260, delayMillis = i * 40), label = "seg")
                Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Mono.c.raised)) {
                    Box(Modifier.fillMaxWidth(fill).height(6.dp).background(Mono.c.ink, CircleShape))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Unlimited imports, auto-import picks, every frame, no watermark.", Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp, color = Mono.c.dim)
            Spacer(Modifier.width(12.dp))
            Row(Modifier.height(36.dp).background(Mono.c.ink, CircleShape).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                PhIcon(Ph.crown, null, size = 15.dp, tint = Mono.c.bg)
                Spacer(Modifier.width(6.dp))
                Text("Get Pro", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Mono.c.bg)
            }
        }
    }
}
