package app.unroll.ui

import android.graphics.Bitmap
import android.location.Geocoder
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.unroll.AppState
import app.unroll.Screen
import app.unroll.core.FilmRecipe
import app.unroll.core.Fix
import app.unroll.core.FrameAspect
import app.unroll.core.FrameStyle
import app.unroll.core.ShotMeta
import app.unroll.importer.MediaStoreSaver
import app.unroll.media.Previews
import app.unroll.render.FrameRenderer
import app.unroll.source.CameraSession
import app.unroll.source.FileLocation
import app.unroll.source.ImportState
import app.unroll.source.Shot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Shot pager

@Composable
fun ShotPagerScreen(app: AppState, shots: List<Shot>, startId: String) {
    val session = app.session
    val start = shots.indexOfFirst { it.id == startId }.coerceAtLeast(0)
    val pager = rememberPagerState(start) { shots.size }
    var showInfo by remember { mutableStateOf(true) }
    var loadingFrame by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val current = shots.getOrNull(pager.currentPage)

    DarkSurface {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                pager, Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { showInfo = !showInfo },
                pageSpacing = 12.dp, beyondViewportPageCount = 1,
            ) { page ->
                // Pages shrink and dim as they slide away, so swiping feels like moving prints on a table.
                val off = ((pager.currentPage - page) + pager.currentPageOffsetFraction).let { kotlin.math.abs(it) }.coerceIn(0f, 1f)
                Box(Modifier.graphicsLayer { val k = 1f - off * 0.12f; scaleX = k; scaleY = k; alpha = 1f - off * 0.5f }) {
                    ShotPreview(shots[page], session, sharedKey = page == start)
                }
            }
            Column(Modifier.align(Alignment.TopStart)) {
                TopBar(current?.primary?.name ?: "", onBack = { app.pop() })
            }
            AnimatedVisibility(
                showInfo && current != null, Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(Mono.press()) { it / 2 } + fadeIn(), exit = slideOutVertically(Mono.settle()) { it / 2 } + fadeOut(),
            ) {
                if (current != null) ShotInfoPanel(app, current, loadingFrame) {
                    scope.launch {
                        loadingFrame = true
                        val img = Previews.full(current, session)
                        loadingFrame = false
                        if (img != null) {
                            val m = current.meta
                            val loc = if (m?.latitude != null && m.longitude != null) Fix(m.latitude!!, m.longitude!!, 0.0)
                            else app.walks.location(app.correctedDate(current))
                            app.push(Screen.Frame(img, m, loc))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShotPreview(shot: Shot, session: CameraSession?, sharedKey: Boolean) {
    var image by remember(shot.id) { mutableStateOf<Bitmap?>(null) }
    var loadMs by remember(shot.id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(shot.id) {
        val t0 = System.currentTimeMillis()
        image = Previews.large(shot, session)
        loadMs = System.currentTimeMillis() - t0
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bmp = image?.asImageBitmap() ?: shot.thumbnail
        // The opened photo morphs out of its grid cell (shared element), then the sharp preview fades in over it.
        bmp?.let {
            val ratio = it.width.toFloat() / it.height.coerceAtLeast(1)
            Image(it, null, Modifier.fillMaxWidth().aspectRatio(ratio).then(if (sharedKey) Modifier.sharedPhoto(shot.id) else Modifier), contentScale = ContentScale.Fit)
        }
        AnimatedVisibility(image == null, enter = fadeIn(), exit = fadeOut()) { Spinner(Color.White) }
        val ms = loadMs
        if (ms != null && shot.primary.isRemote) {
            Text("$ms ms", style = Mono.data(11.sp), color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 100.dp, end = 10.dp).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 3.dp))
        }
    }
}

@Composable
private fun ShotInfoPanel(app: AppState, shot: Shot, loadingFrame: Boolean, onFrame: () -> Unit) {
    val context = LocalContext.current
    val m = shot.meta
    var showRecipe by remember { mutableStateOf(false) }
    var showExif by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).navigationBarsPadding().padding(bottom = 8.dp)
            .card(RoundedCornerShape(26.dp)).heightIn(max = 560.dp).verticalScroll(rememberScrollState()).animateContentSize(Mono.settle()),
    ) {
        Column(Modifier.padding(horizontal = Mono.gutter).padding(top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(m?.cameraName ?: "Unknown camera", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Mono.c.ink)
                    m?.lens?.let { Text(it, fontSize = 13.sp, color = Mono.c.dim) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (shot.rating > 0) StarRow(shot.rating)
                    if (shot.isLocked) PhIcon(Ph.lock, "Protected", size = 13.dp)
                }
            }
            if (m != null && m.exposureLine.isNotEmpty()) {
                Column {
                    Hairline()
                    Row(Modifier.padding(vertical = 10.dp)) {
                        for (t in listOfNotNull(m.focalText, m.apertureText, m.shutterText, m.isoText, m.biasText)) {
                            Text(t, Modifier.weight(1f), style = Mono.data(15.sp, FontWeight.ExtraBold), color = Mono.c.ink, maxLines = 1, overflow = TextOverflow.Clip)
                        }
                    }
                    Hairline()
                }
            }
            val recipe = m?.recipe
            if (recipe != null) RecipeSummary(recipe, showRecipe) { showRecipe = !showRecipe }
            else m?.look?.let { DataRow("Look", it) }

            if (m != null) ExifSummary(m, showExif) { showExif = !showExif }

            Row {
                Text(app.locationSource(shot), style = Mono.data(11.sp), color = Mono.c.dim)
                Spacer(Modifier.weight(1f))
                val size = if (shot.primary.location is FileLocation.Http) "Wi-Fi" else Formatter.formatShortFileSize(context, shot.totalBytes)
                Text("${shot.formatBadge}  $size", style = Mono.data(11.sp), color = Mono.c.dim)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val label = when {
                    shot.wasImported -> "In library"
                    shot.importState == ImportState.Queued -> "Queued"
                    shot.importState is ImportState.Importing -> "Importing…"
                    shot.importState is ImportState.Failed -> "Retry import"
                    shot.raw != null && app.settings.includeRAW -> "Import JPG + RAW"
                    else -> "Import"
                }
                PrimaryButton(label, Modifier.weight(1f), enabled = !shot.wasImported && shot.importState == ImportState.None || shot.importState is ImportState.Failed) {
                    if (shot.importState is ImportState.Failed) shot.importState = ImportState.None
                    app.importShots(listOf(shot))
                }
                SecondaryButton("Frame", Modifier.width(110.dp), enabled = !shot.isVideo, busy = loadingFrame, onClick = onFrame)
            }
        }
    }
}

/** Film simulation on one line; tap to see every setting. */
@Composable
fun RecipeSummary(recipe: FilmRecipe, expanded: Boolean, onToggle: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CapsLabel("Recipe")
                Text(recipe.filmSimulation, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Mono.c.ink)
            }
            Text(if (expanded) "Hide" else "${recipe.rows.size - 1} settings", style = Mono.data(11.sp), color = Mono.c.dim)
            Disclosure(expanded)
        }
        AnimatedVisibility(expanded, enter = expandVertically(Mono.settle()) + fadeIn(), exit = shrinkVertically(Mono.settle()) + fadeOut()) {
            Column(Modifier.padding(top = 6.dp)) { recipe.rows.drop(1).forEach { (k, v) -> DataRow(k, v) } }
        }
    }
}

@Composable
private fun ExifSummary(m: ShotMeta, expanded: Boolean, onToggle: () -> Unit) {
    val rows = buildList {
        m.captureDate()?.let { add("Captured" to it.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy  HH:mm:ss", Locale.getDefault()))) }
        m.offsetTime?.let { add("Time zone" to it) }
        m.lens?.let { add("Lens" to it) }
        m.focalLength35?.let { add("35mm equiv." to "${it}mm") }
        m.biasText?.let { add("Exposure comp." to it) }
        if (m.width != null && m.height != null) add("Pixels" to "${m.width} × ${m.height}")
        if (m.latitude != null && m.longitude != null) add("GPS" to String.format(Locale.US, "%.5f, %.5f", m.latitude, m.longitude))
        m.look?.let { add("Look" to it) }
    }
    if (rows.isEmpty()) return
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
            CapsLabel("EXIF", Modifier.weight(1f))
            Text(if (expanded) "Hide" else "${rows.size} fields", style = Mono.data(11.sp), color = Mono.c.dim)
            Disclosure(expanded)
        }
        AnimatedVisibility(expanded, enter = expandVertically(Mono.settle()) + fadeIn(), exit = shrinkVertically(Mono.settle()) + fadeOut()) { Column(Modifier.padding(top = 6.dp)) { rows.forEach { (k, v) -> DataRow(k, v) } } }
    }
}

// Frame Studio

@Composable
fun FrameStudioScreen(app: AppState, image: Bitmap, meta: ShotMeta?, location: Fix?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf(app.settings.frameOptions) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var proxy by remember { mutableStateOf<Bitmap?>(null) }
    var place by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val resolved = meta?.let { m -> if (m.latitude != null && m.longitude != null) Fix(m.latitude!!, m.longitude!!, 0.0) else null } ?: location

    fun current() = options.copy(placeName = place, watermark = !app.pro.isPro)

    LaunchedEffect(Unit) {
        proxy = withContext(Dispatchers.Default) {
            val k = 1400f / maxOf(image.width, image.height)
            if (k < 1) Bitmap.createScaledBitmap(image, (image.width * k).toInt(), (image.height * k).toInt(), true) else image
        }
        resolved?.let { loc ->
            place = withContext(Dispatchers.IO) {
                runCatching {
                    @Suppress("DEPRECATION")
                    Geocoder(context, Locale.getDefault()).getFromLocation(loc.lat, loc.lon, 1)?.firstOrNull()
                        ?.let { a -> listOfNotNull(a.subLocality, a.locality).firstOrNull() ?: a.featureName }
                }.getOrNull()
            }
        }
    }
    LaunchedEffect(options, proxy, place, app.pro.isPro) {
        val p = proxy ?: return@LaunchedEffect
        app.settings.frameOptions = options.copy(placeName = null)
        val o = current()
        preview = withContext(Dispatchers.Default) { FrameRenderer.render(p, meta, o) }
    }

    suspend fun renderFull(): ByteArray = withContext(Dispatchers.Default) {
        val out = FrameRenderer.render(image, meta, current())
        ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
    }

    DarkSurface {
        Column(Modifier.fillMaxSize().background(Color(0xFF121212))) {
            TopBar("Frame", onBack = { app.pop() }, backIcon = Ph.close) {
                IconButton(Ph.share, "Share") { scope.launch { shareJpeg(context, renderFull(), "unroll-frame.jpg") } }
                TextAction(if (saving) "Saving…" else "Save", bold = true, enabled = !saving) {
                    scope.launch {
                        saving = true
                        val result = runCatching {
                            val bytes = renderFull()
                            withContext(Dispatchers.IO) { MediaStoreSaver.saveJpeg(context, bytes, "Unroll_${System.currentTimeMillis()}.jpg") }
                        }
                        saving = false
                        app.toast = result.fold({ "Framed photo saved at full resolution" }, { it.message ?: "Couldn't save" })
                        if (result.isSuccess) app.pop()
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                // Each option change cross-fades to the new render with a slight settle, like a print being swapped.
                AnimatedContent(preview, label = "frame", transitionSpec = {
                    (fadeIn(tween(220)) + scaleIn(Mono.press(), initialScale = 0.97f)).togetherWith(fadeOut(tween(160)))
                }) { b -> if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(Color.White) } }
            }
            Hairline()
            Column(Modifier.padding(top = 14.dp, bottom = 8.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Styles
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Mono.gutter), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    for (s in FrameStyle.entries) {
                        val on = options.style == s
                        val locked = s.isPro && !app.pro.isPro
                        Column(Modifier.clickable { if (locked) app.showPaywall = true else options = options.copy(style = s) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(s.label, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) Mono.c.ink else Mono.c.dim)
                                if (locked) PhIcon(Ph.lock, "Pro", size = 11.dp, tint = Mono.c.dim)
                            }
                            Spacer(Modifier.height(7.dp))
                            Box(Modifier.height(1.5.dp).width(40.dp).background(if (on) Mono.c.ink else Color.Transparent))
                        }
                    }
                }
                // Ratio
                Row(Modifier.padding(horizontal = Mono.gutter), verticalAlignment = Alignment.CenterVertically) {
                    CapsLabel("Ratio", Modifier.width(64.dp))
                    for (a in FrameAspect.entries) {
                        val on = options.aspect == a
                        Column(Modifier.weight(1f).clickable { options = options.copy(aspect = a) }.heightIn(min = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(a.label, style = Mono.data(13.sp, if (on) FontWeight.SemiBold else FontWeight.Normal), color = if (on) Mono.c.ink else Mono.c.dim)
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.height(1.5.dp).width(28.dp).background(if (on) Mono.c.ink else Color.Transparent))
                        }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Mono.gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChipToggle("Dark", options.dark) { options = options.copy(dark = it) }
                    ChipToggle("Exposure", options.showExposure) { options = options.copy(showExposure = it) }
                    ChipToggle(if (meta?.recipe != null) "Recipe" else "Look", options.showRecipe) { options = options.copy(showRecipe = it) }
                    ChipToggle("Date", options.showDate) { options = options.copy(showDate = it) }
                    if (place != null) ChipToggle("Place", options.showPlace) { options = options.copy(showPlace = it) }
                }
            }
        }
    }
    BackHandler { app.pop() }
}
