package app.unroll.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.unroll.AppState
import app.unroll.Screen
import app.unroll.media.Previews
import app.unroll.source.CameraSession
import app.unroll.source.ImportState
import app.unroll.source.SessionStatus
import app.unroll.source.Shot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ShotFilter(val label: String) {
    ALL("All"), KEEPERS("Picks"), RATED("Rated"), LOCKED("Protected"), NEW("New"), RAW("RAW"), TODAY("Today");

    fun matches(s: Shot): Boolean = when (this) {
        ALL -> true
        KEEPERS -> s.isKeeper
        RATED -> s.isRated
        LOCKED -> s.isLocked
        NEW -> !s.wasImported
        RAW -> s.hasRAW
        TODAY -> s.captureDate.atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()
    }
}

private fun day(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

@Composable
fun LibraryScreen(app: AppState, session: CameraSession) {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf(ShotFilter.ALL) }
    var minStars by rememberSaveable { mutableIntStateOf(1) }
    var selecting by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var columns by rememberSaveable { mutableIntStateOf(3) }
    var menu by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val view = androidx.compose.ui.platform.LocalView.current

    // Location sources and the import notification, asked once, where they start to matter.
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(session.id) {
        val want = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (app.settings.borrowPhoneLocations) {
                // Android 10–12 read other apps' photos with READ_EXTERNAL_STORAGE; 13+ split it.
                add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
                add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            }
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (want.isNotEmpty()) perms.launch(want.toTypedArray())
    }

    val shots = session.shots
    // Derived lists only recompute when what they read changes (the shot list, a filter, a header
    // landing), not on every recomposition — e.g. each import progress tick.
    val visible by remember(shots, filter, minStars) {
        derivedStateOf { shots.filter { filter.matches(it) && (filter != ShotFilter.RATED || it.rating >= minStars) } }
    }
    val days by remember(shots, filter, minStars) {
        derivedStateOf { visible.groupBy { day(it.fileDate) }.toSortedMap(compareByDescending { it }) }
    }
    val keepersNotImported by remember(shots) { derivedStateOf { shots.filter { it.isKeeper && !it.wasImported && !it.isVideo } } }
    val newShots by remember(shots) { derivedStateOf { shots.filter { !it.wasImported && !it.isVideo } } }
    val filterCounts by remember(shots) {
        derivedStateOf {
            val counts = IntArray(ShotFilter.entries.size)
            for (sh in shots) for (f in ShotFilter.entries) if (f.matches(sh)) counts[f.ordinal]++
            counts
        }
    }

    fun recapCandidates(): List<Shot> {
        val picked = shots.filter { it.id in selection }
        if (picked.isNotEmpty()) return picked
        val keepers = shots.filter { it.isKeeper }
        return keepers.ifEmpty { shots.take(12) }
    }

    Box(Modifier.fillMaxSize().background(Mono.c.bg)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(session.name, onBack = { app.disconnect() }) {
                TextAction(if (selecting) "Done" else "Select") { selecting = !selecting; if (!selecting) selection = emptySet() }
                Box {
                    IconButton(Ph.more, "More", filled = true) { menu = true }
                    DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(18.dp)) {
                        for ((n, label) in listOf(2 to "Large grid", 3 to "Medium grid", 5 to "Small grid")) {
                            DropdownMenuItem({ Text(label, fontWeight = if (columns == n) FontWeight.ExtraBold else FontWeight.SemiBold) }, { columns = n; menu = false },
                                trailingIcon = { if (columns == n) PhIcon(Ph.check, null, size = 16.dp) })
                        }
                        DropdownMenuItem({ Text("Make a recap card", fontWeight = FontWeight.SemiBold) }, { menu = false; app.push(Screen.Recap(recapCandidates())) },
                            leadingIcon = { PhIcon(Ph.images, null, size = 18.dp) })
                        DropdownMenuItem({ Text("Disconnect", fontWeight = FontWeight.SemiBold) }, { menu = false; app.disconnect() },
                            leadingIcon = { PhIcon(Ph.plug, null, size = 18.dp) })
                    }
                }
            }

            LazyVerticalGrid(
                GridCells.Fixed(columns), Modifier.weight(1f), state = grid,
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(if (columns >= 5) 3.dp else 5.dp),
                verticalArrangement = Arrangement.spacedBy(if (columns >= 5) 3.dp else 5.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "header") { SessionHeader(session) }
                item(span = { GridItemSpan(maxLineSpan) }, key = "filters") {
                    Column(Modifier.padding(horizontal = 0.dp)) {
                        Box(Modifier.padding(horizontal = 0.dp)) {
                            TabStrip(ShotFilter.entries, filter, { it.label }, { f -> filterCounts[f.ordinal] }) { filter = it }
                        }
                        AnimatedVisibility(filter == ShotFilter.RATED, enter = expandVertically(Mono.press()) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                CapsLabel("At least")
                                Spacer(Modifier.width(8.dp))
                                StarRow(minStars, 18.dp) { minStars = it }
                                Spacer(Modifier.weight(1f))
                                if (!session.scanComplete) Text("Reading ${session.scanned}/${shots.size}", style = Mono.data(12.sp), color = Mono.c.dim)
                            }
                        }
                    }
                }
                val status = session.status
                when {
                    status is SessionStatus.Failed -> item(span = { GridItemSpan(maxLineSpan) }) { QuietMessage("Connection lost", status.message) }
                    shots.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Spinner()
                            Spacer(Modifier.height(14.dp))
                            Text(
                                when (status) {
                                    SessionStatus.Connecting -> "Opening session…"
                                    is SessionStatus.Cataloging -> if (status.percent > 0) "Reading card  ${status.percent}%" else "Reading card…"
                                    else -> "No photos on this card"
                                },
                                style = Mono.data(14.sp), color = Mono.c.dim,
                            )
                        }
                    }
                    visible.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }, key = "empty-$filter") {
                        Box(Modifier.riseIn(0, filter)) {
                            QuietMessage(
                                if (filter == ShotFilter.NEW) "All caught up" else "Nothing here",
                                when (filter) {
                                    ShotFilter.KEEPERS, ShotFilter.RATED, ShotFilter.LOCKED -> "Rate or protect shots on the camera body and they show up here. Picks are shots that are rated or protected."
                                    ShotFilter.NEW -> "Everything on this card is already in your photo library."
                                    else -> "No shots match this filter."
                                },
                            )
                        }
                    }
                    else -> for ((d, list) in days) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "day-$d") {
                            Row(Modifier.animateItem().padding(start = 6.dp, end = 2.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(dayTitle(d), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Mono.c.ink)
                                Spacer(Modifier.width(8.dp))
                                AnimatedCount(list.size, Mono.data(14.sp, FontWeight.Bold), Mono.c.dim)
                                Spacer(Modifier.weight(1f))
                                AnimatedVisibility(selecting, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut()) {
                                    val allIn = list.all { it.id in selection }
                                    TextAction(if (allIn) "Deselect day" else "Select day") {
                                        selection = if (allIn) selection - list.map { it.id }.toSet() else selection + list.map { it.id }
                                    }
                                }
                            }
                        }
                        itemsIndexed(list, key = { _, it -> it.id }) { index, shot ->
                            LaunchedEffect(shot.id) { session.prioritize(shot) }
                            ShotCell(
                                session, shot, index, columns, selecting, shot.id in selection,
                                Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = Mono.press()),
                                onTap = {
                                    if (selecting) { Haptics.tick(view); selection = if (shot.id in selection) selection - shot.id else selection + shot.id }
                                    else app.push(Screen.Detail(visible, shot.id))
                                },
                                onLong = { Haptics.press(view); selecting = true; selection = selection + shot.id },
                            )
                        }
                    }
                }
            }
        }

        // Floating action bar: switches between import and selection modes.
        val selected = remember(shots, selection) { if (selection.isEmpty()) emptyList() else shots.filter { it.id in selection } }
        val mode = when { selecting -> 1; !app.importer.isRunning -> 0; else -> 2 }
        AnimatedContent(
            mode, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            transitionSpec = { (slideInVertically(Mono.press()) { it / 2 } + fadeIn()).togetherWith(slideOutVertically { it / 2 } + fadeOut(tween(120))) },
            label = "actions",
        ) { m ->
            when (m) {
                1 -> Row(Modifier.fillMaxWidth().card(RoundedCornerShape(22.dp), 16.dp).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selected.isEmpty()) Text("Tap to select", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Mono.c.ink)
                            else { AnimatedCount(selected.size, TextStyle(fontFamily = Mono.sans, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold), Mono.c.ink); Text(" selected", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Mono.c.ink) }
                        }
                        if (selected.isNotEmpty()) Text(Formatter.formatShortFileSize(context, selected.sumOf { it.totalBytes }), style = Mono.data(12.sp), color = Mono.c.dim)
                    }
                    SecondaryButton("Recap", Modifier.width(92.dp), enabled = selected.isNotEmpty()) { app.push(Screen.Recap(selected)) }
                    PrimaryButton("Import", Modifier.width(104.dp), enabled = selected.isNotEmpty()) {
                        app.importShots(selected); selecting = false; selection = emptySet()
                    }
                }
                0 -> Row(Modifier.fillMaxWidth().card(RoundedCornerShape(22.dp), 16.dp).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Import picks  ${keepersNotImported.size}${if (session.scanComplete) "" else "+"}", Modifier.weight(1f), enabled = keepersNotImported.isNotEmpty()) {
                        app.importShots(keepersNotImported)
                    }
                    SecondaryButton("All new  ${newShots.size}", Modifier.weight(1f), enabled = newShots.isNotEmpty()) { app.importShots(newShots) }
                }
                else -> Spacer(Modifier.height(1.dp))
            }
        }
    }
}

private fun dayTitle(d: LocalDate): String = when (d) {
    LocalDate.now() -> "Today"
    LocalDate.now().minusDays(1) -> "Yesterday"
    else -> d.format(DateTimeFormatter.ofPattern("EEEE d MMM", Locale.getDefault()))
}

@Composable
private fun SessionHeader(session: CameraSession) {
    val shots = session.shots
    Column(Modifier.padding(top = 4.dp, bottom = 8.dp).card(RoundedCornerShape(22.dp), 8.dp)) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Stat(shots.size, "shots", Modifier.weight(1f))
            Stat(shots.count { it.isKeeper }, "picks", Modifier.weight(1f))
            Stat(shots.count { it.wasImported }, "imported", Modifier.weight(1f))
            Column(Modifier.weight(1f)) {
                Text(session.kind.label, style = Mono.data(22.sp, FontWeight.ExtraBold), color = Mono.c.ink, maxLines = 1)
                CapsLabel("source", size = 10.sp)
            }
        }
        val status = session.status
        val progress: Float? = when {
            status is SessionStatus.Cataloging -> status.percent / 100f
            status == SessionStatus.Ready && !session.scanComplete && shots.isNotEmpty() -> session.scanned.toFloat() / shots.size
            else -> null
        }
        val p by animateFloatAsState(progress ?: 1f, Mono.settle(), label = "progress")
        AnimatedVisibility(progress != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically(Mono.settle()) + fadeOut()) {
            Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp)) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Mono.c.raised)) {
                    Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(4.dp).background(Mono.c.ink, CircleShape))
                }
                val line = when (status) {
                    SessionStatus.Connecting -> "Connecting over ${session.kind.label}…"
                    is SessionStatus.Cataloging -> "Cataloging card  ${status.percent}%"
                    is SessionStatus.Failed -> ""
                    SessionStatus.Ready -> "Reading ratings  ${session.scanned}/${shots.size}"
                }
                Text(line, style = Mono.data(12.sp), color = Mono.c.dim, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun Stat(value: Int, label: String, modifier: Modifier) {
    Column(modifier) {
        AnimatedCount(value, Mono.data(22.sp, FontWeight.ExtraBold), Mono.c.ink)
        CapsLabel(label, size = 10.sp)
    }
}

private val badgeStyle = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, color = Color.White, fontFeatureSettings = "tnum")

/** Small frosted pill over a photo. */
@Composable
private fun Badge(modifier: Modifier, content: @Composable () -> Unit) {
    Row(
        modifier.padding(5.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) { content() }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ShotCell(
    session: CameraSession, shot: Shot, index: Int, columns: Int, selecting: Boolean, selected: Boolean,
    modifier: Modifier, onTap: () -> Unit, onLong: () -> Unit,
) {
    // Re-runs when the thumbnail cache evicted this cell's image while it was still on screen.
    val missing = shot.thumbnail == null
    LaunchedEffect(shot.id, missing) { Previews.thumbnail(shot, session) }
    val shape = RoundedCornerShape(if (columns >= 5) 6.dp else 12.dp)
    val inset by animateFloatAsState(if (selecting && selected) 0.88f else if (selecting) 0.95f else 1f, Mono.press(), label = "inset")
    val imageAlpha by animateFloatAsState(if (shot.thumbnail != null) 1f else 0f, tween(260), label = "thumb")
    Box(
        modifier.aspectRatio(1f).popIn(index, shot.id)
            .combinedClickable(onClick = onTap, onLongClick = onLong),
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { scaleX = inset; scaleY = inset }
                .sharedPhoto(shot.id).clip(shape).background(Mono.c.raised),
        ) {
            shot.thumbnail?.let { Image(it, null, Modifier.fillMaxSize().graphicsLayer { alpha = imageAlpha }, contentScale = ContentScale.Crop) }
                ?: if (shot.isVideo) Glyph(GlyphKind.VIDEO, Modifier.align(Alignment.Center), Mono.c.dim) else Unit
            if (shot.isKeeper) {
                Badge(Modifier.align(Alignment.TopStart)) {
                    if (shot.rating > 0) {
                        PhIcon(Ph.star, null, size = 10.dp, tint = Color.White)
                        Text("${shot.rating}", style = badgeStyle)
                    }
                    if (shot.isLocked) PhIcon(Ph.lock, "Protected", size = 10.dp, tint = Color.White)
                }
            }
            if (shot.hasRAW || shot.isVideo) {
                Badge(Modifier.align(Alignment.BottomStart)) { Text(if (shot.isVideo) "VID" else if (shot.raw != null) "J+R" else "RAW", style = badgeStyle) }
            }
            // Keyed by the kind of state, so a progress tick updates the % text instead of starting a transition.
            AnimatedContent(shot.importState to shot.wasImported, Modifier.align(Alignment.BottomEnd), label = "state",
                transitionSpec = { scaleIn(Mono.press(), initialScale = 0.6f) + fadeIn() togetherWith fadeOut() },
                contentKey = { (st, done) -> (if (st is ImportState.Importing) "importing" else st.toString()) to done }) { (st, done) ->
                when (st) {
                    ImportState.Queued -> Badge(Modifier) { Text("···", style = badgeStyle) }
                    is ImportState.Importing -> Badge(Modifier) { Text("${(st.fraction * 100).toInt()}%", style = badgeStyle) }
                    is ImportState.Failed -> Badge(Modifier) { Text("!", style = badgeStyle) }
                    else -> if (done) Badge(Modifier) { PhIcon(Ph.check, "Imported", size = 10.dp, tint = Color.White) } else Spacer(Modifier.size(1.dp))
                }
            }
        }
        AnimatedVisibility(selecting, Modifier.align(Alignment.TopEnd).padding(8.dp), enter = scaleIn(Mono.press()) + fadeIn(), exit = scaleOut() + fadeOut()) {
            Box(
                Modifier.size(22.dp).background(if (selected) Mono.c.ink else Color.Black.copy(alpha = 0.25f), CircleShape)
                    .border(1.5.dp, if (selected) Mono.c.bg else Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedVisibility(selected, enter = scaleIn(Mono.press(), initialScale = 0.3f), exit = scaleOut()) {
                    PhIcon(Ph.check, null, size = 12.dp, tint = Mono.c.bg)
                }
            }
        }
    }
}
