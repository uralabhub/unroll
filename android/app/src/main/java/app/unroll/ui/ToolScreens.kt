package app.unroll.ui

import app.unroll.Legal
import app.unroll.BuildConfig
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.location.Geocoder
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.unroll.AppState
import app.unroll.Screen
import app.unroll.core.ClockSync
import app.unroll.data.AlbumMode
import app.unroll.data.AutoImportRule
import app.unroll.data.Quota
import app.unroll.importer.MediaStoreSaver
import app.unroll.media.Previews
import app.unroll.render.RecapRenderer
import app.unroll.render.RecipeCard
import app.unroll.source.Shot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun Bitmap.jpeg(quality: Int = 93): ByteArray = ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

/** Hands a rendered JPEG to the system share sheet (via FileProvider). */
fun shareJpeg(context: Context, bytes: ByteArray, name: String = "unroll.jpg") {
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    val f = File(dir, name).apply { writeBytes(bytes) }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
    context.startActivity(
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

// Recap card

@Composable
fun RecapScreen(app: AppState, shots: List<Shot>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pool = remember(shots) { shots.filter { !it.isVideo } }
    var chosen by remember { mutableStateOf(pool.sortedByDescending { it.rating }.take(6).map { it.id }) }
    fun defaultTitle(): String {
        val d = pool.minOfOrNull { it.captureDate } ?: Instant.now()
        return d.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())) + " walk"
    }
    var title by remember { mutableStateOf(defaultTitle()) }
    var rendered by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun render() {
        busy = true
        val picks = chosen.mapNotNull { id -> pool.firstOrNull { it.id == id } }
        val images = picks.mapNotNull { Previews.large(it, app.session) }
        val dates = pool.map { app.correctedDate(it) }
        val walk = app.walks.walkCovering(dates)
        val route = walk?.points?.map { it.lat to it.lon }.orEmpty()
        var subtitle = Instant.ofEpochMilli(dates.minOrNull() ?: System.currentTimeMillis()).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()))
        route.firstOrNull()?.let { (la, lo) ->
            val place = withContext(Dispatchers.IO) {
                runCatching {
                    @Suppress("DEPRECATION") Geocoder(context, Locale.getDefault()).getFromLocation(la, lo, 1)?.firstOrNull()
                        ?.let { a -> listOfNotNull(a.subLocality, a.locality, a.adminArea).distinct().take(2).joinToString(", ") }
                }.getOrNull()
            }
            if (!place.isNullOrBlank()) subtitle = "$place · $subtitle"
        }
        val input = RecapRenderer.Input(
            title = title.ifBlank { defaultTitle() }, subtitle = subtitle, photos = images, shotCount = pool.size,
            keeperCount = pool.count { it.isKeeper }, distanceKm = walk?.distanceMeters?.div(1000), durationMillis = walk?.durationMillis(),
            cameras = pool.mapNotNull { it.meta?.cameraName }.distinct().sorted(),
            looks = pool.mapNotNull { it.meta?.recipe?.filmSimulation ?: it.meta?.look }.distinct().sorted(),
            route = route, watermark = !app.pro.isPro,
        )
        rendered = withContext(Dispatchers.Default) { RecapRenderer.render(input) }
        busy = false
    }
    LaunchedEffect(chosen, title) { delay(150); render() }

    Column(Modifier.fillMaxSize().background(Mono.c.bg)) {
        TopBar("Recap card", onBack = { app.pop() }, backIcon = Ph.close) {
            IconButton(Ph.share, "Share") { rendered?.let { shareJpeg(context, it.jpeg(), "unroll-recap.jpg") } }
            TextAction("Save", bold = true, enabled = rendered != null) {
                val r = rendered ?: return@TextAction
                scope.launch {
                    val res = runCatching { withContext(Dispatchers.IO) { MediaStoreSaver.saveJpeg(context, r.jpeg(), "Unroll_recap_${System.currentTimeMillis()}.jpg") } }
                    app.toast = if (res.isSuccess) "Recap saved to your photo library" else res.exceptionOrNull()?.message
                    if (res.isSuccess) app.pop()
                }
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.fillMaxWidth().height(460.dp).riseIn(0), contentAlignment = Alignment.Center) {
                // Each re-render cross-fades over the last one instead of blinking.
                Crossfade(rendered, Modifier.fillMaxSize(), animationSpec = tween(260), label = "recap") { r ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        r?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                            ?: Box(Modifier.fillMaxWidth().height(300.dp).clip(Mono.shape).background(Mono.c.tile).sheen(busy, 0.12f), contentAlignment = Alignment.Center) {
                                if (!busy) Text("Pick up to 6 photos", color = Mono.c.dim)
                            }
                    }
                }
            }
            Column(Modifier.riseIn(1), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CapsLabel("Title")
                BasicTextField(
                    title, { title = it }, singleLine = true,
                    textStyle = Mono.title.copy(fontWeight = FontWeight.SemiBold, color = Mono.c.ink),
                    cursorBrush = SolidColor(Mono.c.ink), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                Hairline()
            }
            Row(Modifier.riseIn(2)) {
                CapsLabel("Photos")
                Spacer(Modifier.weight(1f))
                AnimatedCount(chosen.size, Mono.data(12.sp), Mono.c.dim, suffix = " / 6")
            }
            pool.chunked(4).forEachIndexed { ri, row ->
                Row(Modifier.riseIn(3 + ri), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { s ->
                        LaunchedEffect(s.id) { Previews.thumbnail(s, app.session) }
                        val i = chosen.indexOf(s.id)
                        val inset by animateFloatAsState(if (i >= 0) 0.92f else 1f, Motion.bouncy(), label = "pick")
                        Box(Modifier.weight(1f).aspectRatio(1f).background(Mono.c.raised).pressDim(role = Role.Checkbox) {
                            chosen = if (i >= 0) chosen - s.id else if (chosen.size < 6) chosen + s.id else chosen
                        }) {
                            s.thumbnail?.let { Image(it, null, Modifier.fillMaxSize().graphicsLayer { scaleX = inset; scaleY = inset }, contentScale = ContentScale.Crop) }
                            PickMark(i)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** White frame and order number on a chosen recap photo; both pop in. */
@Composable
private fun PickMark(i: Int) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(i >= 0, enter = fadeIn(tween(150)) + scaleIn(Motion.bouncy(), initialScale = 1.15f), exit = fadeOut(tween(120))) {
            Box(Modifier.fillMaxSize().border(2.dp, Color.White))
        }
        AnimatedVisibility(i >= 0, Modifier.align(Alignment.TopEnd), enter = scaleIn(spring(dampingRatio = 0.45f, stiffness = 600f)), exit = scaleOut(tween(120))) {
            Box(Modifier.size(22.dp).background(Color.White), contentAlignment = Alignment.Center) {
                AnimatedCount(maxOf(i, 0) + 1, Mono.data(12.sp, FontWeight.SemiBold), Color.Black)
            }
        }
    }
}

// Recipes

@Composable
fun RecipesScreen(app: AppState) {
    Column(Modifier.fillMaxSize().background(Mono.c.bg)) {
        TopBar("Recipes", onBack = { app.pop() })
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            val items = app.recipes.sorted
            if (items.isEmpty()) {
                QuietMessage("No recipes yet", "Import Fujifilm shots and every recipe you used is collected here, ready to share or dial back in.")
            }
            if (items.isNotEmpty()) Column(Modifier.padding(top = 8.dp)) {
                MonoSection(rows = items.map { item ->
                    {
                        Row(
                            Modifier.fillMaxWidth().pressDim(scale = 0.98f) { app.push(Screen.RecipeDetail(item.id)) }.heightIn(min = 64.dp)
                                .padding(horizontal = Mono.gutter, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PhIcon(Ph.film, null, size = 20.dp, tint = Mono.c.dim)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(item.title, style = Mono.body, color = Mono.c.ink)
                                Text(item.recipe.shortLine.removePrefix(item.recipe.filmSimulation + " · "), style = Mono.data(13.sp), color = Mono.c.dim)
                                item.camera?.let { Text(it, style = Mono.footnote, color = Mono.c.faint) }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(if (item.count == 1) "1 shot" else "${item.count} shots", style = Mono.data(13.sp), color = Mono.c.dim)
                            Spacer(Modifier.width(8.dp))
                            PhIcon(Ph.chevron, null, size = 13.dp, tint = Mono.c.faint)
                        }
                    }
                })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun RecipeDetailScreen(app: AppState, id: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val item = app.recipes.items.firstOrNull { it.id == id } ?: run { app.pop(); return }
    var name by remember { mutableStateOf(item.name ?: "") }
    DisposableEffect(Unit) { onDispose { app.recipes.rename(id, name) } }
    Column(Modifier.fillMaxSize().background(Mono.c.bg)) {
        TopBar(item.title, onBack = { app.pop() }) {
            IconButton(Ph.trash, "Delete") { app.recipes.delete(id); app.pop() }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.padding(horizontal = Mono.gutter).riseIn(0), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CapsLabel("Name")
                Box {
                    if (name.isEmpty()) Text(item.recipe.filmSimulation, fontSize = 17.sp, color = Mono.c.faint, modifier = Modifier.padding(vertical = 8.dp))
                    BasicTextField(name, { name = it }, singleLine = true, textStyle = TextStyle(fontFamily = Mono.sans, fontSize = 17.sp, color = Mono.c.ink),
                        cursorBrush = SolidColor(Mono.c.ink), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
                Hairline()
            }
            MonoSection("Settings", index = 1, rows = item.recipe.rows.map { (k, v) -> { Box(Modifier.padding(horizontal = Mono.gutter)) { DataRow(k, v) } } })
            Column(Modifier.padding(horizontal = Mono.gutter).riseIn(6), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Share recipe card", Modifier.fillMaxWidth()) {
                    val title = name.ifBlank { item.title }
                    val watermark = !app.pro.isPro
                    scope.launch {
                        val jpeg = withContext(Dispatchers.Default) { RecipeCard.render(title, item.camera, item.recipe, watermark).jpeg() }
                        shareJpeg(context, jpeg, "unroll-recipe.jpg")
                    }
                }
                SecondaryButton("Copy as text", Modifier.fillMaxWidth()) {
                    val text = (listOf(name.ifBlank { item.title }) + item.recipe.rows.map { "${it.first}: ${it.second}" }).joinToString("\n")
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Recipe", text))
                    app.toast = "Recipe copied"
                }
            }
        }
    }
}

// Clock sync

@Composable
fun ClockSyncScreen(app: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var result by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000 - now % 1000) } }
    // Keep the screen on while the code is shown.
    DisposableEffect(Unit) {
        val w = (context as? Activity)?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val seconds = now / 1000
    val matrix = remember(seconds) { ClockSync.matrix(seconds, 0) }

    Column(Modifier.fillMaxSize().background(Mono.c.bg)) {
        TopBar("Clock sync", onBack = { app.pop() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Text("Camera clocks drift and ignore time zones, which puts photos in the wrong place on the map. Fix it once per camera.",
                Modifier.padding(horizontal = Mono.gutter).riseIn(0), style = Mono.subhead, color = Mono.c.dim)
            Column(Modifier.fillMaxWidth().riseIn(1), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(14.dp)) {
                    Canvas(Modifier.size(240.dp)) {
                        val n = matrix.width
                        val cell = size.width / n
                        for (y in 0 until n) for (x in 0 until n) if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                    }
                }
                RollingText(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss")), Mono.data(28.sp, FontWeight.Medium), Mono.c.ink)
            }
            MonoSection("How", index = 2, rows = listOf(
                "Turn the screen brightness up and photograph this code with the camera.",
                "Connect the camera by cable or card.",
                "Tap Read from camera. Unroll compares the code with the photo’s timestamp.",
            ).mapIndexed { i, t ->
                {
                    Row(Modifier.fillMaxWidth().padding(horizontal = Mono.gutter, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(String.format(Locale.US, "%02d", i + 1), style = Mono.data(13.sp, FontWeight.Medium), color = Mono.c.dim)
                        Text(t, style = Mono.subhead, color = Mono.c.ink)
                    }
                }
            })
            Column(Modifier.padding(horizontal = Mono.gutter).riseIn(6).animateContentSize(Motion.float()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Read from camera", Modifier.fillMaxWidth(), enabled = app.session != null && !reading, busy = reading) {
                    scope.launch { reading = true; result = readClock(app); reading = false }
                }
                if (app.session == null) Text("Connect a camera first.", style = Mono.footnote, color = Mono.c.dim)
                AnimatedContent(result, label = "clockResult", transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(Motion.bouncy()) { it / 3 }).togetherWith(fadeOut(tween(120)))
                }) { r -> if (r != null) Text(r, style = Mono.subhead.copy(fontWeight = FontWeight.Medium), color = Mono.c.ink) }
            }
            if (app.settings.clockOffsets.isNotEmpty()) {
                MonoSection("Corrections in use", index = 7, rows = app.settings.clockOffsets.toSortedMap().map { (model, off) ->
                    {
                        Row(Modifier.fillMaxWidth().padding(horizontal = Mono.gutter, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(model, Modifier.weight(1f), color = Mono.c.ink)
                            Text(ClockSync.describe(off), style = Mono.data(14.sp), color = Mono.c.dim)
                            Box(Modifier.padding(start = 4.dp).size(48.dp).clip(CircleShape).pressDim(onClickLabel = "Remove correction") { app.settings.setClockOffset(model, null) }, contentAlignment = Alignment.Center) {
                                PhIcon(Ph.close, "Remove correction", size = 16.dp, tint = Mono.c.dim)
                            }
                        }
                    }
                })
            }
        }
    }
}

/** Looks at the newest few shots for the code, then stores how far the camera clock is off. */
private suspend fun readClock(app: AppState): String {
    val session = app.session ?: return "Connect a camera first."
    for (shot in session.shots.filter { !it.isVideo }.take(5)) {
        if (shot.meta == null) session.loadMeta(shot)
        val img = Previews.large(shot, session) ?: continue
        val truth = withContext(Dispatchers.Default) {
            val px = IntArray(img.width * img.height)
            img.getPixels(px, 0, img.width, 0, 0, img.width, img.height)
            ClockSync.readTimestamp(px, img.width, img.height)
        } ?: continue
        val exif = shot.meta?.captureDate() ?: continue
        val off = ClockSync.offsetSeconds(exif.toEpochMilli(), truth * 1000)
        val model = shot.meta?.model ?: session.name
        app.settings.setClockOffset(model, off)
        return "${shot.meta?.cameraName ?: model} is ${ClockSync.describe(off)}. Corrected for every import from now on."
    }
    return "Couldn’t find the code in the latest 5 shots. Take a sharp, well-lit photo of it and try again."
}

// Settings

@Composable
fun SettingsScreen(app: AppState) {
    val s = app.settings
    val context = LocalContext.current
    var pickAuto by remember { mutableStateOf(false) }
    var pickAlbum by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Mono.c.bg)) {
        TopBar("Settings", onBack = { app.pop() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Column {
                MonoSection("Import", index = 0, rows = listOf(
                    { MonoRow("When a camera connects", value = s.autoImport.label, onClick = { pickAuto = !pickAuto }) },
                    { ToggleRow("Keep RAW with JPEG", s.includeRAW) { s.includeRAW = it } },
                    { MonoRow("Save to", value = s.albumMode.label, onClick = { pickAlbum = !pickAlbum }) },
                    { ToggleRow("Eject camera when done", s.autoEject) { s.autoEject = it } },
                ))
                Reveal(pickAuto) { Choices(AutoImportRule.entries, s.autoImport, { it.label }) { s.autoImport = it; pickAuto = false } }
                Reveal(pickAlbum) { Choices(AlbumMode.entries, s.albumMode, { it.label }) { s.albumMode = it; pickAlbum = false } }
                Footer("Picks are shots you rated or protected on the camera body. Auto-import of picks is part of Pro. JPG and RAW are saved side by side in DCIM/Unroll under the same name.")
            }
            Column {
                MonoSection("Location", index = 3, rows = listOf(
                    { ToggleRow("Add location to imports", s.geotag) { s.geotag = it } },
                    { ToggleRow("Borrow location from phone photos", s.borrowPhoneLocations) { s.borrowPhoneLocations = it } },
                    { MonoRow("Camera clock corrections", value = "${s.clockOffsets.size}", onClick = { app.push(Screen.ClockSync) }) },
                ))
                Footer("Uses your Photo Walk trail first, then phone photos taken within 20 minutes. Android galleries only read location from inside the file, so a JPEG that needs one gets a GPS block appended to its EXIF; image data, maker notes and recipe stay byte-identical. RAW files are never changed.")
            }
            Column {
                MonoSection("Connections", index = 4, rows = listOf(
                    { MonoRow("Ricoh GR over Wi‑Fi", detail = "GR III, GR IIIx: join the camera's Wi‑Fi in Android settings first", onClick = { app.connectRicoh() }) },
                ))
                Footer("USB cameras and SD cards work from the home screen. Wi‑Fi transfer is only available for Ricoh GR cameras.")
            }
            MonoSection("Unroll Pro", index = 5, rows = buildList {
                if (app.pro.isPro) add { MonoRow("Pro is unlocked. Thank you.", chevron = false) }
                else {
                    add { MonoRow("See Pro", onClick = { app.showPaywall = true }) }
                    add { MonoRow("Free imports left today", chevron = false) { AnimatedCount(app.quota.remaining, Mono.data(15.sp), Mono.c.dim, suffix = " of ${Quota.FREE_PER_DAY}") } }
                }
                add { MonoRow("Restore purchases", onClick = { app.pro.restore() }) }
            })
            val uri = androidx.compose.ui.platform.LocalUriHandler.current
            MonoSection(index = 7, rows = listOf(
                { MonoRow("How Unroll works", onClick = { app.showTour = true }) },
                { MonoRow("Privacy policy", onClick = { uri.openUri(Legal.PRIVACY) }) },
                { MonoRow("Terms of use", onClick = { uri.openUri(Legal.TERMS) }) },
                { MonoRow("Support", onClick = { uri.openUri(Legal.SUPPORT) }) },
            ))
            if (BuildConfig.DEBUG) MonoSection("Developer", index = 8, rows = listOf(
                { ToggleRow("Unlock Pro (testing)", app.pro.debugUnlock) { app.pro.debugUnlock = it } },
                { MonoRow("Forget import history", chevron = false, onClick = { app.ledger.reset(); app.toast = "Import history cleared" }) },
            ))
            Column {
                MonoSection(index = 8, rows = listOf({
                    MonoRow("Version", value = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—", chevron = false)
                }))
                Footer("No account, no analytics, no cloud. Everything stays on your phone.")
            }
        }
    }
}

@Composable
private fun Footer(text: String) = Text(text, Modifier.padding(horizontal = Mono.gutter * 2, vertical = 8.dp), style = Mono.footnote, color = Mono.c.dim)

@Composable
private fun ToggleRow(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
    MonoRow(title, chevron = false, onClick = { onChange(!on) }) {
        Switch(
            on, onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Mono.c.bg, checkedTrackColor = Mono.c.ink, checkedBorderColor = Mono.c.ink,
                uncheckedThumbColor = Mono.c.dim, uncheckedTrackColor = Mono.c.tile, uncheckedBorderColor = Mono.c.faint,
            ),
        )
    }
}

/** Options that unfold under their row and fold away again. */
@Composable
private fun Reveal(open: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(open, enter = expandVertically(Motion.float()) + fadeIn(tween(200)), exit = shrinkVertically(tween(220)) + fadeOut(tween(150))) { content() }
}

@Composable
private fun <T> Choices(items: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Column(Modifier.padding(start = Mono.gutter * 2, end = Mono.gutter)) {
        items.forEach { item ->
            Row(Modifier.fillMaxWidth().pressDim(scale = 0.98f) { onPick(item) }.heightIn(min = 48.dp).padding(horizontal = Mono.gutter, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val fg by animateColorAsState(if (item == selected) Mono.c.ink else Mono.c.dim, Motion.snap(), label = "choice")
                Text(label(item), Modifier.weight(1f), style = Mono.body, color = fg)
                AnimatedVisibility(item == selected, enter = scaleIn(Motion.bouncy()) + fadeIn(), exit = scaleOut() + fadeOut()) { PhIcon(Ph.check, "Selected", size = 16.dp) }
            }
        }
        Hairline()
    }
}

// Paywall

@Composable
fun PaywallSheet(app: AppState, onClose: () -> Unit) {
    val context = LocalContext.current
    var plan by remember { mutableStateOf("lifetime") }
    val lifetime = plan == "lifetime"
    var lastSub by remember { mutableStateOf("yearly") }   // keeps the renewal note steady while it collapses
    LaunchedEffect(plan) { if (!lifetime) lastSub = plan }
    val pro = app.pro
    LaunchedEffect(pro.isPro) { if (pro.isPro) onClose() }
    val perks = listOf(
        "Unlimited imports" to "Free plan: ${Quota.FREE_PER_DAY} a day",
        "Auto-import picks" to "Plug in and your rated or protected shots go straight to the library",
        "Automatic locations" to "From your walk or phone photos, corrected for clock drift",
        "Every frame style" to "Recipe card, Instant and Film strip at full resolution",
        "No watermark" to "On frames, recaps and recipe cards",
    )
    Column(Modifier.fillMaxSize().background(Mono.c.surface)) {
        Box(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Box(Modifier.align(Alignment.TopCenter).size(36.dp, 4.dp).background(Mono.c.faint, CircleShape))
            Box(Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 4.dp)) { TextAction("Not now", onClick = onClose) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
            Column(Modifier.padding(horizontal = Mono.gutter).riseIn(0), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PhIcon(Ph.crown, null, Modifier.popOn(pro.isPro), size = 28.dp, tint = Mono.c.accent)
                CapsLabel("Unroll Pro")
                Text("Camera to posted, without the busywork.", style = Mono.largeTitle, color = Mono.c.ink)
            }
            MonoSection(index = 1, rows = perks.map { (t, d) ->
                {
                    Column(Modifier.fillMaxWidth().padding(horizontal = Mono.gutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(t, style = Mono.body.copy(fontWeight = FontWeight.Medium), color = Mono.c.ink)
                        Text(d, style = Mono.footnote, color = Mono.c.dim)
                    }
                }
            })
            Column(Modifier.padding(horizontal = Mono.gutter).riseIn(7), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PlanRow(plan == "lifetime", "Lifetime", pro.lifetimePrice, "Pay once. Founder price.") { plan = "lifetime" }
                PlanRow(plan == "yearly", "Yearly", pro.yearlyPrice + " / yr", "7-day free trial. Best value.") { plan = "yearly" }
                PlanRow(plan == "monthly", "Monthly", pro.monthlyPrice + " / mo", "Cancel anytime") { plan = "monthly" }
                PlanRow(plan == "weekly", "Weekly", pro.weeklyPrice + " / wk", "For a single trip") { plan = "weekly" }
            }
        }
        Column(Modifier.background(Mono.c.surface).navigationBarsPadding()) {
            Hairline()
            Column(Modifier.padding(horizontal = Mono.gutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val buttonTitle = when (plan) { "lifetime" -> "Buy lifetime"; "yearly" -> "Start free trial"; else -> "Subscribe" }
                PrimaryButton(buttonTitle, Modifier.fillMaxWidth(), busy = pro.purchasing) {
                    val product = when (plan) { "lifetime" -> pro.lifetime; "yearly" -> pro.yearly; "monthly" -> pro.monthly; else -> pro.weekly }
                    val activity = context as? Activity
                    if (product == null || activity == null) pro.message = "Google Play isn’t reachable right now. Try again shortly."
                    else pro.buy(activity, product)
                }
                pro.message?.let { Text(it, fontSize = 13.sp, color = Mono.c.dim) }
                AnimatedVisibility(!lifetime, enter = expandVertically(Motion.float()) + fadeIn(tween(200)), exit = shrinkVertically(tween(200)) + fadeOut(tween(120))) {
                    val lead = when (if (lifetime) lastSub else plan) {
                        "yearly" -> "7 days free, then ${pro.yearlyPrice} a year."
                        "monthly" -> "${pro.monthlyPrice} a month."
                        else -> "${pro.weeklyPrice} a week."
                    }
                    Text("$lead Renews automatically until you cancel in Google Play › Subscriptions.",
                        fontSize = 11.sp, color = Mono.c.dim, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                val uri = androidx.compose.ui.platform.LocalUriHandler.current
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Restore", Modifier.clickable { pro.restore() }.padding(4.dp), fontSize = 13.sp, color = Mono.c.dim)
                    Text("Terms", Modifier.clickable { uri.openUri(Legal.TERMS) }.padding(4.dp), fontSize = 13.sp, color = Mono.c.dim)
                    Text("Privacy", Modifier.clickable { uri.openUri(Legal.PRIVACY) }.padding(4.dp), fontSize = 13.sp, color = Mono.c.dim)
                }
            }
        }
    }
}

@Composable
private fun PlanRow(on: Boolean, title: String, price: String, note: String, onClick: () -> Unit) {
    val border by animateColorAsState(if (on) Mono.c.ink else Mono.c.line, Motion.snap(), label = "planBorder")
    val dot by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.45f, stiffness = 600f), label = "dot")
    Row(
        Modifier.fillMaxWidth().pressDim(scale = 0.97f, onClick = onClick).popOn(on, 1.03f).clip(Mono.shape)
            .border(if (on) 1.5.dp else 1.dp, border, Mono.shape).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(22.dp).border(2.dp, border, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(11.dp).graphicsLayer { scaleX = dot; scaleY = dot }.background(Mono.c.ink, CircleShape))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Mono.body.copy(fontWeight = FontWeight.SemiBold), color = Mono.c.ink)
            Text(note, style = Mono.footnote, color = Mono.c.dim)
        }
        Text(price, style = Mono.data(15.sp, FontWeight.Medium), color = Mono.c.ink)
    }
}

// Walks

@Composable
fun WalksScreen(app: AppState) {
    Column(Modifier.fillMaxSize().background(Mono.c.bg)) {
        TopBar("Photo walks", onBack = { app.pop() })
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            val walks = app.walks.walks.reversed()
            if (walks.isEmpty()) QuietMessage("No walks yet", "Start a photo walk before you head out. Imports get their location from it automatically.")
            walks.forEachIndexed { wi, w ->
                Column(Modifier.padding(horizontal = Mono.gutter, vertical = 6.dp).fillMaxWidth().riseIn(wi, w.id).card().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (w.points.size > 1) {
                        // Drawn off the main thread; the slot keeps its height while it renders.
                        val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, w.id, w.points.size) {
                            value = withContext(Dispatchers.Default) {
                                val b = Bitmap.createBitmap(936, 280, Bitmap.Config.ARGB_8888)
                                RecapRenderer.drawRoute(android.graphics.Canvas(b), w.points.map { it.lat to it.lon }, android.graphics.RectF(0f, 0f, 936f, 280f))
                                b.asImageBitmap()
                            }
                        }
                        Crossfade(bmp, animationSpec = tween(300), label = "route") { img ->
                            if (img != null) Image(img, null, Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                            else Box(Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(8.dp)).background(Mono.c.tile).sheen(true, 0.08f))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Instant.ofEpochMilli(w.start).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())),
                            style = Mono.body.copy(fontWeight = FontWeight.Medium), color = Mono.c.ink)
                        if (w.isDemo) { Spacer(Modifier.width(8.dp)); CapsLabel("Demo") }
                        Spacer(Modifier.weight(1f))
                        Text(modifier = Modifier.breathe(w.end == null), text = if (w.end == null) "Recording" else String.format(Locale.US, "%.2f km", w.distanceMeters / 1000), style = Mono.data(14.sp), color = Mono.c.dim)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val mins = w.durationMillis() / 60_000
                        Text("${mins / 60}h ${mins % 60}m  ·  ${w.points.size} GPS fixes", Modifier.weight(1f), style = Mono.data(12.sp), color = Mono.c.dim)
                        Box(Modifier.heightIn(min = 48.dp).pressDim { app.walks.delete(w) }.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                            Text("Delete", style = Mono.footnote, color = Mono.c.dim)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

