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
import androidx.compose.foundation.shape.CircleShape
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
            Box(Modifier.fillMaxWidth().height(460.dp), contentAlignment = Alignment.Center) {
                rendered?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    ?: Box(Modifier.fillMaxWidth().height(300.dp).background(Mono.c.raised), contentAlignment = Alignment.Center) {
                        if (busy) Spinner() else Text("Pick up to 6 photos", color = Mono.c.dim)
                    }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CapsLabel("Title")
                BasicTextField(
                    title, { title = it }, singleLine = true,
                    textStyle = TextStyle(fontFamily = Mono.sans, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Mono.c.ink),
                    cursorBrush = SolidColor(Mono.c.ink), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                Hairline()
            }
            Row {
                CapsLabel("Photos")
                Spacer(Modifier.weight(1f))
                Text("${chosen.size} / 6", style = Mono.data(12.sp), color = Mono.c.dim)
            }
            pool.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    row.forEach { s ->
                        LaunchedEffect(s.id) { Previews.thumbnail(s, app.session) }
                        val i = chosen.indexOf(s.id)
                        Box(Modifier.weight(1f).aspectRatio(1f).background(Mono.c.raised).clickable {
                            chosen = if (i >= 0) chosen - s.id else if (chosen.size < 6) chosen + s.id else chosen
                        }) {
                            s.thumbnail?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            if (i >= 0) {
                                Box(Modifier.fillMaxSize().border(2.dp, Color.White))
                                Box(Modifier.align(Alignment.TopEnd).size(22.dp).background(Color.White), contentAlignment = Alignment.Center) {
                                    Text("${i + 1}", style = Mono.data(12.sp, FontWeight.SemiBold), color = Color.Black)
                                }
                            }
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

// Recipes

@Composable
fun RecipesScreen(app: AppState) {
    Column(Modifier.fillMaxSize().background(Mono.c.bg)) {
        TopBar("Film recipes", onBack = { app.pop() })
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            val items = app.recipes.sorted
            if (items.isEmpty()) {
                QuietMessage("No recipes yet", "Import Fujifilm shots and every recipe you used is collected here, ready to share or dial back in.")
            }
            items.forEachIndexed { i, item ->
                Row(
                    Modifier.riseIn(i).padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
                        .tilePress(0.98f) { app.push(Screen.RecipeDetail(item.id)) }.card(elevation = 6.dp).padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp).background(Mono.c.raised, CircleShape), contentAlignment = Alignment.Center) { PhIcon(Ph.film, null, size = 22.dp) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Mono.c.ink)
                        Text(item.recipe.shortLine.removePrefix(item.recipe.filmSimulation + " · "), style = Mono.data(13.sp), color = Mono.c.dim)
                        item.camera?.let { CapsLabel(it, color = Mono.c.faint, size = 10.sp) }
                    }
                    Text(if (item.count == 1) "1 shot" else "${item.count} shots", style = Mono.data(13.sp, FontWeight.Bold), color = Mono.c.dim)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun RecipeDetailScreen(app: AppState, id: String) {
    val context = LocalContext.current
    val item = app.recipes.items.firstOrNull { it.id == id } ?: run { app.pop(); return }
    var name by remember { mutableStateOf(item.name ?: "") }
    DisposableEffect(Unit) { onDispose { app.recipes.rename(id, name) } }
    Column(Modifier.fillMaxSize().background(Mono.c.bg)) {
        TopBar(item.title, onBack = { app.pop() }) {
            IconButton(Ph.trash, "Delete") { app.recipes.delete(id); app.pop() }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.padding(horizontal = Mono.gutter), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CapsLabel("Name")
                Box {
                    if (name.isEmpty()) Text(item.recipe.filmSimulation, fontSize = 17.sp, color = Mono.c.faint, modifier = Modifier.padding(vertical = 8.dp))
                    BasicTextField(name, { name = it }, singleLine = true, textStyle = TextStyle(fontFamily = Mono.sans, fontSize = 17.sp, color = Mono.c.ink),
                        cursorBrush = SolidColor(Mono.c.ink), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
                Hairline()
            }
            MonoSection("Settings", rows = item.recipe.rows.map { (k, v) -> { Box(Modifier.padding(horizontal = Mono.gutter)) { DataRow(k, v) } } })
            Column(Modifier.padding(horizontal = Mono.gutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Share recipe card", Modifier.fillMaxWidth()) {
                    val card = RecipeCard.render(name.ifBlank { item.title }, item.camera, item.recipe, !app.pro.isPro)
                    shareJpeg(context, card.jpeg(), "unroll-recipe.jpg")
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
                Modifier.padding(horizontal = Mono.gutter), fontSize = 15.sp, color = Mono.c.dim)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.background(Color.White).padding(14.dp)) {
                    Canvas(Modifier.size(240.dp)) {
                        val n = matrix.width
                        val cell = size.width / n
                        for (y in 0 until n) for (x in 0 until n) if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                    }
                }
                Text(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss")), style = Mono.data(40.sp, FontWeight.Medium), color = Mono.c.ink)
            }
            MonoSection("How", rows = listOf(
                "Turn the screen brightness up and photograph this code with the camera.",
                "Connect the camera by cable or card.",
                "Tap Read from camera. Unroll compares the code with the photo’s timestamp.",
            ).mapIndexed { i, t ->
                {
                    Row(Modifier.fillMaxWidth().padding(horizontal = Mono.gutter, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(String.format(Locale.US, "%02d", i + 1), style = Mono.data(13.sp, FontWeight.Medium), color = Mono.c.dim)
                        Text(t, fontSize = 15.sp, color = Mono.c.ink)
                    }
                }
            })
            Column(Modifier.padding(horizontal = Mono.gutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Read from camera", Modifier.fillMaxWidth(), enabled = app.session != null && !reading, busy = reading) {
                    scope.launch { reading = true; result = readClock(app); reading = false }
                }
                if (app.session == null) Text("Connect a camera first.", fontSize = 13.sp, color = Mono.c.dim)
                result?.let { Text(it, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Mono.c.ink) }
            }
            if (app.settings.clockOffsets.isNotEmpty()) {
                MonoSection("Corrections in use", rows = app.settings.clockOffsets.toSortedMap().map { (model, off) ->
                    {
                        Row(Modifier.fillMaxWidth().padding(horizontal = Mono.gutter, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(model, Modifier.weight(1f), color = Mono.c.ink)
                            Text(ClockSync.describe(off), style = Mono.data(14.sp), color = Mono.c.dim)
                            Box(Modifier.padding(start = 8.dp).size(32.dp).clickable { app.settings.setClockOffset(model, null) }, contentAlignment = Alignment.Center) {
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
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(30.dp)) {
            Column {
                MonoSection("Import", rows = listOf(
                    { MonoRow("When a camera connects", value = s.autoImport.label, onClick = { pickAuto = !pickAuto }) },
                    { ToggleRow("Keep RAW with JPEG", s.includeRAW) { s.includeRAW = it } },
                    { MonoRow("Save to", value = s.albumMode.label, onClick = { pickAlbum = !pickAlbum }) },
                    { ToggleRow("Eject camera when done", s.autoEject) { s.autoEject = it } },
                ))
                if (pickAuto) Choices(AutoImportRule.entries, s.autoImport, { it.label }) { s.autoImport = it; pickAuto = false }
                if (pickAlbum) Choices(AlbumMode.entries, s.albumMode, { it.label }) { s.albumMode = it; pickAlbum = false }
                Footer("Picks are shots you rated or protected on the camera body. Auto-import of picks is part of Pro. JPG and RAW are saved side by side in DCIM/Unroll under the same name.")
            }
            Column {
                MonoSection("Location", rows = listOf(
                    { ToggleRow("Add location to imports", s.geotag) { s.geotag = it } },
                    { ToggleRow("Borrow location from phone photos", s.borrowPhoneLocations) { s.borrowPhoneLocations = it } },
                    { MonoRow("Camera clock corrections", value = "${s.clockOffsets.size}", onClick = { app.push(Screen.ClockSync) }) },
                ))
                Footer("Uses your Photo Walk trail first, then phone photos taken within 20 minutes. Android galleries only read location from inside the file, so a JPEG that needs one gets a GPS block appended to its EXIF; image data, maker notes and recipe stay byte-identical. RAW files are never changed.")
            }
            MonoSection("Unroll Pro", rows = buildList {
                if (app.pro.isPro) add { MonoRow("Pro is unlocked. Thank you.", chevron = false) }
                else {
                    add { MonoRow("See Pro", onClick = { app.showPaywall = true }) }
                    add { MonoRow("Free imports left today", value = "${app.quota.remaining} of ${Quota.FREE_PER_DAY}", chevron = false) }
                }
                add { MonoRow("Restore purchases", onClick = { app.pro.restore() }) }
            })
            val uri = androidx.compose.ui.platform.LocalUriHandler.current
            MonoSection(rows = listOf(
                { MonoRow("Privacy policy", onClick = { uri.openUri(Legal.PRIVACY) }) },
                { MonoRow("Support", onClick = { uri.openUri(Legal.SUPPORT) }) },
            ))
            if (BuildConfig.DEBUG) MonoSection("Developer", rows = listOf(
                { ToggleRow("Unlock Pro (testing)", app.pro.debugUnlock) { app.pro.debugUnlock = it } },
                { MonoRow("Forget import history", chevron = false, onClick = { app.ledger.reset(); app.toast = "Import history cleared" }) },
            ))
            Column {
                MonoSection(rows = listOf({
                    MonoRow("Version", value = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—", chevron = false)
                }))
                Footer("No account, no analytics, no cloud. Everything stays on your phone.")
            }
        }
    }
}

@Composable
private fun Footer(text: String) = Text(text, Modifier.padding(horizontal = Mono.gutter, vertical = 8.dp), fontSize = 13.sp, color = Mono.c.dim)

@Composable
private fun ToggleRow(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
    MonoRow(title, chevron = false, onClick = { onChange(!on) }) {
        Switch(
            on, onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Mono.c.bg, checkedTrackColor = Mono.c.ink, checkedBorderColor = Mono.c.ink,
                uncheckedThumbColor = Mono.c.dim, uncheckedTrackColor = Mono.c.bg, uncheckedBorderColor = Mono.c.faint,
            ),
        )
    }
}

@Composable
private fun <T> Choices(items: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Column(Modifier.padding(start = Mono.gutter)) {
        items.forEach { item ->
            Row(Modifier.fillMaxWidth().clickable { onPick(item) }.padding(horizontal = Mono.gutter, vertical = 12.dp)) {
                Text(label(item), Modifier.weight(1f), color = if (item == selected) Mono.c.ink else Mono.c.dim, fontWeight = if (item == selected) FontWeight.SemiBold else FontWeight.Normal)
                if (item == selected) Text("✓", color = Mono.c.ink)
            }
        }
        Hairline()
    }
}

// Paywall

@Composable
fun PaywallSheet(app: AppState, onClose: () -> Unit) {
    val context = LocalContext.current
    var lifetime by remember { mutableStateOf(true) }
    val pro = app.pro
    LaunchedEffect(pro.isPro) { if (pro.isPro) onClose() }
    val perks = listOf(
        "Unlimited imports" to "Free plan: ${Quota.FREE_PER_DAY} a day",
        "Auto-import picks" to "Plug in and your rated or protected shots go straight to the library",
        "Automatic locations" to "From your walk or phone photos, corrected for clock drift",
        "Every frame style" to "Recipe card, Instant and Film strip at full resolution",
        "No watermark" to "On frames, recaps and recipe cards",
    )
    Column(Modifier.fillMaxSize().background(Mono.c.bg)) {
        Box(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Box(Modifier.align(Alignment.TopCenter).size(40.dp, 5.dp).background(Mono.c.faint, CircleShape))
            IconButton(Ph.close, "Close", Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = 6.dp), filled = true, onClick = onClose)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
            Column(Modifier.padding(horizontal = Mono.gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CapsLabel("Unroll Pro")
                Text("Camera to posted, without the busywork.", fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, color = Mono.c.ink)
            }
            MonoSection(rows = perks.map { (t, d) ->
                {
                    Column(Modifier.fillMaxWidth().padding(horizontal = Mono.gutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(t, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Mono.c.ink)
                        Text(d, fontSize = 13.sp, color = Mono.c.dim)
                    }
                }
            })
            Column(Modifier.padding(horizontal = Mono.gutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PlanRow(lifetime, "Lifetime", pro.lifetimePrice, "Pay once. Founder price.") { lifetime = true }
                PlanRow(!lifetime, "Yearly", pro.yearlyPrice + " / yr", "7-day free trial") { lifetime = false }
            }
        }
        Column(Modifier.background(Mono.c.bg).navigationBarsPadding()) {
            Hairline()
            Column(Modifier.padding(horizontal = Mono.gutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PrimaryButton(if (lifetime) "Buy lifetime" else "Start free trial", Modifier.fillMaxWidth(), busy = pro.purchasing) {
                    val product = if (lifetime) pro.lifetime else pro.yearly
                    val activity = context as? Activity
                    if (product == null || activity == null) pro.message = "Google Play isn’t reachable right now. Try again shortly."
                    else pro.buy(activity, product)
                }
                pro.message?.let { Text(it, fontSize = 13.sp, color = Mono.c.dim) }
                if (!lifetime) Text("7 days free, then a yearly charge. Renews automatically until you cancel in Google Play › Subscriptions.",
                    fontSize = 11.sp, color = Mono.c.dim, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                val uri = androidx.compose.ui.platform.LocalUriHandler.current
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Restore", Modifier.clickable { pro.restore() }.padding(4.dp), fontSize = 13.sp, color = Mono.c.dim)
                    Text("Privacy", Modifier.clickable { uri.openUri(Legal.PRIVACY) }.padding(4.dp), fontSize = 13.sp, color = Mono.c.dim)
                }
            }
        }
    }
}

@Composable
private fun PlanRow(on: Boolean, title: String, price: String, note: String, onClick: () -> Unit) {
    val border by androidx.compose.animation.animateColorAsState(if (on) Mono.c.ink else Mono.c.line, Mono.settle(), label = "plan")
    val dot by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, Mono.press(), label = "dot")
    Row(
        Modifier.fillMaxWidth().tilePress(0.98f, onClick = onClick).card(RoundedCornerShape(20.dp), if (on) 12.dp else 4.dp)
            .border(2.dp, border, RoundedCornerShape(20.dp)).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(22.dp).border(2.dp, border, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(11.dp).graphicsLayer { scaleX = dot; scaleY = dot }.background(Mono.c.ink, CircleShape))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Mono.c.ink)
            Text(note, fontSize = 13.sp, color = Mono.c.dim)
        }
        Text(price, style = Mono.data(16.sp, FontWeight.ExtraBold), color = Mono.c.ink)
    }
}

// Walks

@Composable
fun WalksScreen(app: AppState) {
    Column(Modifier.fillMaxSize().background(Mono.c.bg)) {
        TopBar("Walks", onBack = { app.pop() })
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            val walks = app.walks.walks.reversed()
            if (walks.isEmpty()) QuietMessage("No walks yet", "Start a photo walk before you head out. Imports get their location from it automatically.")
            walks.forEach { w ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().card(elevation = 6.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (w.points.size > 1) {
                        val bmp = remember(w.id, w.points.size) {
                            val b = Bitmap.createBitmap(936, 280, Bitmap.Config.ARGB_8888)
                            RecapRenderer.drawRoute(android.graphics.Canvas(b), w.points.map { it.lat to it.lon }, android.graphics.RectF(0f, 0f, 936f, 280f))
                            b.asImageBitmap()
                        }
                        Image(bmp, null, Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Instant.ofEpochMilli(w.start).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())),
                            fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Mono.c.ink)
                        if (w.isDemo) { Spacer(Modifier.width(8.dp)); CapsLabel("Demo") }
                        Spacer(Modifier.weight(1f))
                        Text(if (w.end == null) "Recording" else String.format(Locale.US, "%.2f km", w.distanceMeters / 1000), style = Mono.data(14.sp), color = Mono.c.dim)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val mins = w.durationMillis() / 60_000
                        Text("${mins / 60}h ${mins % 60}m  ·  ${w.points.size} GPS fixes", Modifier.weight(1f), style = Mono.data(12.sp), color = Mono.c.dim)
                        Text("Delete", Modifier.clickable { app.walks.delete(w) }.padding(4.dp), fontSize = 13.sp, color = Mono.c.dim)
                    }
                }
                Hairline(Modifier.padding(start = Mono.gutter))
            }
        }
    }
}

