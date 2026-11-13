package app.unroll.ui

import android.Manifest
import android.content.pm.PackageManager
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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

    // Location sources and the import notification, asked once, where they start to matter.
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(session.id) {
        val want = buildList {
            add(Manifest.permission.POST_NOTIFICATIONS)
            if (app.settings.borrowPhoneLocations) { add(Manifest.permission.READ_MEDIA_IMAGES); add(Manifest.permission.ACCESS_MEDIA_LOCATION) }
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (want.isNotEmpty()) perms.launch(want.toTypedArray())
    }

    val shots = session.shots
    val visible = shots.filter { filter.matches(it) && (filter != ShotFilter.RATED || it.rating >= minStars) }
    val days = visible.groupBy { day(it.fileDate) }.toSortedMap(compareByDescending { it })
    val keepersNotImported = shots.filter { it.isKeeper && !it.wasImported && !it.isVideo }
    val newShots = shots.filter { !it.wasImported && !it.isVideo }

    fun recapCandidates(): List<Shot> {
        val picked = shots.filter { it.id in selection }
        if (picked.isNotEmpty()) return picked
        val keepers = shots.filter { it.isKeeper }
        return keepers.ifEmpty { shots.take(12) }
    }

    Column(Modifier.fillMaxSize().background(Mono.c.bg)) {
        TopBar(session.name, onBack = { app.disconnect() }) {
            TextAction(if (selecting) "Done" else "Select") { selecting = !selecting; if (!selecting) selection = emptySet() }
            Box {
                Box(Modifier.size(44.dp).tilePress { menu = true }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.MoreVert, "More", tint = Mono.c.ink) }
                DropdownMenu(menu, { menu = false }) {
                    for ((n, label) in listOf(2 to "Large grid", 3 to "Medium grid", 5 to "Small grid")) {
                        DropdownMenuItem({ Text(label + if (columns == n) "  ✓" else "") }, { columns = n; menu = false })
                    }
                    DropdownMenuItem({ Text("Make a recap card") }, { menu = false; app.push(Screen.Recap(recapCandidates())) })
                    DropdownMenuItem({ Text("Disconnect") }, { menu = false; app.disconnect() })
                }
            }
        }

        LazyVerticalGrid(
            GridCells.Fixed(columns), Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp), verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") { SessionHeader(session) }
            item(span = { GridItemSpan(maxLineSpan) }, key = "filters") {
                Column {
                    TabStrip(ShotFilter.entries, filter, { it.label }, { f -> if (f == ShotFilter.ALL) shots.size else shots.count(f::matches) }) { filter = it }
                    Hairline()
                    if (filter == ShotFilter.RATED) {
                        Row(Modifier.padding(horizontal = Mono.gutter, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            CapsLabel("At least")
                            Spacer(Modifier.width(14.dp))
                            StarRow(minStars, 16.sp) { minStars = it }
                            Spacer(Modifier.weight(1f))
                            if (!session.scanComplete) Text("Reading ${session.scanned}/${shots.size}", style = Mono.data(11.sp), color = Mono.c.dim)
                        }
                        Hairline()
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
                            style = Mono.data(13.sp), color = Mono.c.dim,
                        )
                    }
                }
                visible.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    QuietMessage(
                        if (filter == ShotFilter.NEW) "All caught up" else "Nothing here",
                        when (filter) {
                            ShotFilter.KEEPERS, ShotFilter.RATED, ShotFilter.LOCKED -> "Rate or protect shots on the camera body and they show up here. Picks are shots that are rated or protected."
                            ShotFilter.NEW -> "Everything on this card is already in your photo library."
                            else -> "No shots match this filter."
                        },
                    )
                }
                else -> for ((d, list) in days) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "day-$d") {
                        Row(Modifier.padding(start = Mono.gutter, end = Mono.gutter, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
                            Text(dayTitle(d), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Mono.c.ink)
                            Spacer(Modifier.width(8.dp))
                            Text("${list.size}", style = Mono.data(13.sp), color = Mono.c.dim)
                            Spacer(Modifier.weight(1f))
                            if (selecting) {
                                val allIn = list.all { it.id in selection }
                                TextAction(if (allIn) "Deselect day" else "Select day") {
                                    selection = if (allIn) selection - list.map { it.id }.toSet() else selection + list.map { it.id }
                                }
                            }
                        }
                    }
                    items(list, key = { it.id }) { shot ->
                        LaunchedEffect(shot.id) { session.prioritize(shot) }
                        ShotCell(session, shot, selecting, shot.id in selection,
                            onTap = {
                                if (selecting) selection = if (shot.id in selection) selection - shot.id else selection + shot.id
                                else app.push(Screen.Detail(visible, shot.id))
                            },
                            onLong = { selecting = true; selection = selection + shot.id },
                        )
                    }
                }
            }
        }

        // Action bar
        val selected = shots.filter { it.id in selection }
        Column(Modifier.background(Mono.c.bg).navigationBarsPadding()) {
            Hairline()
            if (selecting) {
                Row(Modifier.padding(horizontal = Mono.gutter, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(if (selected.isEmpty()) "Tap to select" else "${selected.size} selected", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Mono.c.ink)
                        if (selected.isNotEmpty()) Text(Formatter.formatShortFileSize(context, selected.sumOf { it.totalBytes }), style = Mono.data(11.sp), color = Mono.c.dim)
                    }
                    SecondaryButton("Recap", Modifier.width(92.dp), enabled = selected.isNotEmpty()) { app.push(Screen.Recap(selected)) }
                    PrimaryButton("Import", Modifier.width(110.dp), enabled = selected.isNotEmpty()) {
                        app.importShots(selected); selecting = false; selection = emptySet()
                    }
                }
            } else if (!app.importer.isRunning) {
                Row(Modifier.padding(horizontal = Mono.gutter, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Import picks  ${keepersNotImported.size}${if (session.scanComplete) "" else "+"}", Modifier.weight(1f), enabled = keepersNotImported.isNotEmpty()) {
                        app.importShots(keepersNotImported)
                    }
                    SecondaryButton("All new  ${newShots.size}", Modifier.weight(1f), enabled = newShots.isNotEmpty()) { app.importShots(newShots) }
                }
            } else {
                Spacer(Modifier.height(74.dp))
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
    Column {
        Row(Modifier.padding(horizontal = Mono.gutter, vertical = 12.dp)) {
            for ((v, l) in listOf("${shots.size}" to "shots", "${shots.count { it.isKeeper }}" to "picks", "${shots.count { it.wasImported }}" to "imported", session.kind.label to "source")) {
                Column(Modifier.weight(1f)) {
                    Text(v, style = Mono.data(20.sp, FontWeight.Medium), color = Mono.c.ink, maxLines = 1)
                    CapsLabel(l, size = 10.sp)
                }
            }
        }
        val status = session.status
        val progress: Float? = when {
            status is SessionStatus.Cataloging -> status.percent / 100f
            status == SessionStatus.Ready && !session.scanComplete && shots.isNotEmpty() -> session.scanned.toFloat() / shots.size
            else -> null
        }
        progress?.let {
            Box(Modifier.fillMaxWidth().height(1.5.dp)) { Box(Modifier.fillMaxWidth(it.coerceIn(0f, 1f)).height(1.5.dp).background(Mono.c.ink)) }
        }
        val line = when (status) {
            SessionStatus.Connecting -> "Connecting over ${session.kind.label}…"
            is SessionStatus.Cataloging -> "Cataloging card  ${status.percent}%"
            is SessionStatus.Failed -> null
            SessionStatus.Ready -> if (session.scanComplete) null else "Reading ratings  ${session.scanned}/${shots.size}"
        }
        line?.let { Text(it, style = Mono.data(11.sp), color = Mono.c.dim, modifier = Modifier.padding(start = Mono.gutter, end = Mono.gutter, bottom = 10.dp, top = if (progress == null) 0.dp else 8.dp)) }
    }
}

private val badgeStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = Color.White)

@Composable
private fun Badge(modifier: Modifier, content: @Composable () -> Unit) {
    Row(modifier.background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 4.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) { content() }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ShotCell(session: CameraSession, shot: Shot, selecting: Boolean, selected: Boolean, onTap: () -> Unit, onLong: () -> Unit) {
    LaunchedEffect(shot.id) { Previews.thumbnail(shot, session) }
    Box(Modifier.aspectRatio(1f).background(Mono.c.raised).combinedClickable(onClick = onTap, onLongClick = onLong)) {
        shot.thumbnail?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: if (shot.isVideo) Glyph(GlyphKind.VIDEO, Modifier.size(24.dp).align(Alignment.Center), Mono.c.dim) else Unit
        if (shot.isKeeper) {
            Badge(Modifier.align(Alignment.TopStart)) {
                if (shot.rating > 0) Text("★${shot.rating}", style = badgeStyle)
                if (shot.isLocked) Icon(Icons.Filled.Lock, "Protected", Modifier.size(9.dp), tint = Color.White)
            }
        }
        if (shot.hasRAW || shot.isVideo) {
            Badge(Modifier.align(Alignment.BottomStart)) { Text(if (shot.isVideo) "VID" else if (shot.raw != null) "J+R" else "RAW", style = badgeStyle) }
        }
        when (val st = shot.importState) {
            ImportState.Queued -> Badge(Modifier.align(Alignment.BottomEnd)) { Text("…", style = badgeStyle) }
            is ImportState.Importing -> Badge(Modifier.align(Alignment.BottomEnd)) { Text("${(st.fraction * 100).toInt()}%", style = badgeStyle) }
            is ImportState.Failed -> Badge(Modifier.align(Alignment.BottomEnd)) { Text("!", style = badgeStyle) }
            else -> if (shot.wasImported) Badge(Modifier.align(Alignment.BottomEnd)) { Icon(Icons.Filled.Check, "Imported", Modifier.size(9.dp), tint = Color.White) }
        }
        if (selecting) {
            if (selected) Box(Modifier.fillMaxSize().border(3.dp, Mono.c.ink))
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)
                    .background(if (selected) Mono.c.ink else Color.Transparent, CircleShape)
                    .border(1.5.dp, if (selected) Mono.c.bg else Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (selected) Icon(Icons.Filled.Check, null, Modifier.size(12.dp), tint = Mono.c.bg) }
        }
    }
}
