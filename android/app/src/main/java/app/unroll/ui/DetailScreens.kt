package app.unroll.ui

import android.app.Activity
import android.graphics.Bitmap
import android.location.Geocoder
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// Shot pager

/** Black, full-bleed photos. Tap toggles the chrome; Info shows the EXIF panel (hidden by default). */
@Composable
fun ShotPagerScreen(app: AppState, shots: List<Shot>, startId: String) {
    val session = app.session
    val start = remember(shots, startId) { shots.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
    val pager = rememberPagerState(start) { shots.size }
    var chrome by rememberSaveable { mutableStateOf(true) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var loadingFrame by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val current = shots.getOrNull(pager.currentPage)

    // System bars follow the chrome, like Photos.
    val view = LocalView.current
    DisposableEffect(chrome) {
        val window = (view.context as? Activity)?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        if (!chrome) {
            ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { ctl?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    fun openFrame(s: Shot) {
        scope.launch {
            loadingFrame = true
            val img = Previews.full(s, session)
            loadingFrame = false
            if (img != null) {
                val m = s.meta
                val loc = if (m?.latitude != null && m.longitude != null) Fix(m.latitude!!, m.longitude!!, 0.0)
                else app.walks.location(app.correctedDate(s))
                app.push(Screen.Frame(img, m, loc))
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            pager,
            Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null, onClickLabel = "Show or hide controls") {
                chrome = !chrome; if (!chrome) showInfo = false
            },
            pageSpacing = 8.dp, beyondViewportPageCount = 1, key = { shots[it].id },
        ) { page ->
            // The zoom from the grid is keyed on the page being shown now, so closing returns to the right cell.
            ShotPreview(shots[page], session, shared = page == pager.currentPage)
        }

        AnimatedVisibility(chrome, Modifier.align(Alignment.TopCenter), enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(Ph.back, "Back") { app.pop() }
                Column(Modifier.weight(1f).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        current?.captureDate?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)) ?: "",
                        style = Mono.subhead.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text("${pager.currentPage + 1} of ${shots.size}", style = Mono.data(12.sp), color = Mono.c.dim, maxLines = 1)
                }
                Spacer(Modifier.width(48.dp))
            }
        }

        AnimatedVisibility(chrome && current != null, Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
            val s = current ?: return@AnimatedVisibility
            Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.78f)).navigationBarsPadding()) {
                AnimatedVisibility(showInfo, enter = expandVertically(tween(300)) + fadeIn(tween(300)), exit = shrinkVertically(tween(250)) + fadeOut(tween(200))) {
                    ShotInfoPanel(app, s)
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (loadingFrame) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Spinner(Color.White, 20.dp) }
                    else IconButton(Ph.frame, "Frame", tint = if (s.isVideo) Mono.c.faint else Color.White) { if (!s.isVideo) openFrame(s) }
                    IconButton(if (showInfo) Ph.infoFill else Ph.info, if (showInfo) "Hide info" else "Info") { showInfo = !showInfo }
                    ImportAction(app, s)
                }
            }
        }
    }
}

@Composable
private fun ImportAction(app: AppState, s: Shot) {
    when (val st = s.importState) {
        is ImportState.Importing -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { st.fraction.toFloat().coerceIn(0.02f, 1f) }, modifier = Modifier.size(24.dp),
                color = Mono.c.accent, trackColor = Mono.c.tile, strokeWidth = 3.dp, gapSize = 0.dp,
            )
        }
        ImportState.Queued -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { PhIcon(Ph.timer, "Queued", size = 22.dp, tint = Mono.c.dim) }
        else -> if (s.wasImported) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { PhIcon(Ph.checkCircle, "In Photos", size = 22.dp, tint = Mono.c.dim) }
        } else {
            val failed = st is ImportState.Failed
            IconButton(if (failed) Ph.retry else Ph.download, if (failed) "Retry import" else "Import") {
                if (failed) s.importState = ImportState.None
                app.importShots(listOf(s))
            }
        }
    }
}

@Composable
private fun ShotPreview(shot: Shot, session: CameraSession?, shared: Boolean) {
    var image by remember(shot.id) { mutableStateOf<Bitmap?>(null) }
    var slow by remember(shot.id) { mutableStateOf(false) }
    val fade = remember(shot.id) { Animatable(0f) }
    LaunchedEffect(shot.id) {
        // Only show a spinner if the preview is actually slow.
        val spinner = launch { delay(350); slow = true }
        image = Previews.large(shot, session)
        spinner.cancel(); slow = false
        fade.animateTo(1f, tween(200))
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val thumb = shot.thumbnail
        val large = remember(image) { image?.asImageBitmap() }
        val ref = large ?: thumb
        if (ref != null) {
            val ratio = ref.width.toFloat() / ref.height.coerceAtLeast(1)
            val tall = ratio < maxWidth.value / maxHeight.value.coerceAtLeast(1f)
            Box(Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = tall).then(if (shared) Modifier.sharedPhoto(shot.id) else Modifier)) {
                if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                if (large != null) Image(large, null, Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }, contentScale = ContentScale.Fit)
            }
        } else if (shot.isVideo) {
            PhIcon(Ph.video, "Video", size = 28.dp, tint = Mono.c.dim)
        }
        if (image == null && slow) Spinner(Color.White)
    }
}

/** EXIF, recipe, location and file facts. Shown under the photo when Info is on. */
@Composable
private fun ShotInfoPanel(app: AppState, shot: Shot) {
    val context = LocalContext.current
    val m = shot.meta
    var showRecipe by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = Mono.gutter).padding(top = 14.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(m?.cameraName ?: "Unknown camera", style = Mono.headline, color = Mono.c.ink)
                m?.lens?.let { Text(it, style = Mono.footnote, color = Mono.c.dim) }
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shot.rating > 0) StarRow(shot.rating)
                if (shot.isLocked) PhIcon(Ph.lock, "Protected", size = 12.dp)
            }
        }
        if (m != null && m.exposureLine.isNotEmpty()) {
            Column {
                Hairline()
                Row(Modifier.padding(vertical = 8.dp)) {
                    for (t in listOfNotNull(m.focalText, m.apertureText, m.shutterText, m.isoText, m.biasText)) {
                        Text(t, Modifier.weight(1f), style = Mono.data(15.sp, FontWeight.Medium), color = Mono.c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Hairline()
            }
        }
        val recipe = m?.recipe
        if (recipe != null) RecipeSummary(recipe, showRecipe) { showRecipe = !showRecipe }
        else m?.look?.let { DataRow("Look", it) }

        Row(verticalAlignment = Alignment.Top) {
            Text(app.locationSource(shot), Modifier.weight(1f), style = Mono.data(12.sp), color = Mono.c.dim, maxLines = 2)
            Spacer(Modifier.width(8.dp))
            val size = if (shot.primary.location is FileLocation.Http) "Wi‑Fi" else Formatter.formatShortFileSize(context, shot.totalBytes)
            Text("${shot.primary.name} · ${shot.formatBadge} · $size", Modifier.weight(1f), style = Mono.data(12.sp), color = Mono.c.dim, textAlign = TextAlign.End, maxLines = 2)
        }
    }
}

/** Film simulation on one line; tap to see every setting. */
@Composable
fun RecipeSummary(recipe: FilmRecipe, expanded: Boolean, onToggle: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).pressDim(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CapsLabel("Recipe")
                Text(recipe.filmSimulation, style = Mono.subhead.copy(fontWeight = FontWeight.SemiBold), color = Mono.c.ink)
            }
            Text(if (expanded) "Hide" else "${recipe.rows.size - 1} settings", style = Mono.data(12.sp), color = Mono.c.dim)
            Spacer(Modifier.width(6.dp))
            Disclosure(expanded)
        }
        AnimatedVisibility(expanded, enter = expandVertically(tween(300)) + fadeIn(tween(300)), exit = shrinkVertically(tween(250)) + fadeOut(tween(200))) {
            Column(Modifier.padding(top = 6.dp)) { recipe.rows.drop(1).forEach { (k, v) -> DataRow(k, v) } }
        }
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
        Column(Modifier.fillMaxSize().background(Mono.c.bg)) {
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
                    fadeIn(tween(220)).togetherWith(fadeOut(tween(160)))
                }) { b -> if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(Color.White) } }
            }
            Hairline()
            Column(Modifier.padding(top = 14.dp, bottom = 8.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Styles
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Mono.gutter), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    for (s in FrameStyle.entries) {
                        val on = options.style == s
                        val locked = s.isPro && !app.pro.isPro
                        Column(Modifier.heightIn(min = 48.dp).clickable { if (locked) app.showPaywall = true else options = options.copy(style = s) }, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(s.label, style = Mono.subhead, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) Mono.c.ink else Mono.c.dim)
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
                        Column(Modifier.weight(1f).clickable { options = options.copy(aspect = a) }.heightIn(min = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
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
