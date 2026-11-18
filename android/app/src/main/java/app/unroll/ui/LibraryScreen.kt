package app.unroll.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import app.unroll.source.SourceKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The three filters that cover almost every visit. Everything else lives in [FilterOptions]. */
enum class ShotFilter(val label: String) {
    ALL("All"), NEW("Not imported"), PICKS("Picks");

    fun matches(s: Shot): Boolean = when (this) {
        ALL -> true
        NEW -> !s.wasImported
        PICKS -> s.isKeeper
    }
}

/** Secondary filters, in the filter menu. */
data class FilterOptions(val minStars: Int = 0, val protectedOnly: Boolean = false, val rawOnly: Boolean = false, val todayOnly: Boolean = false) {
    val isActive: Boolean get() = minStars > 0 || protectedOnly || rawOnly || todayOnly
    fun matches(s: Shot, today: LocalDate): Boolean {
        if (minStars > 0 && s.rating < minStars) return false
        if (protectedOnly && !s.isLocked) return false
        if (rawOnly && !s.hasRAW) return false
        if (todayOnly && s.captureDate.atZone(ZoneId.systemDefault()).toLocalDate() != today) return false
        return true
    }

    companion object {
        val Saver = listSaver<FilterOptions, Int>(
            save = { listOf(it.minStars, if (it.protectedOnly) 1 else 0, if (it.rawOnly) 1 else 0, if (it.todayOnly) 1 else 0) },
            restore = { FilterOptions(it[0], it[1] == 1, it[2] == 1, it[3] == 1) },
        )
    }
}

private val SelectionSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/**
 * Everything the library shows, computed once per catalog change / filter change, outside the
 * cells, so header reads and import ticks don't re-render the whole grid.
 */
private class LibrarySnapshot(
    val counts: IntArray,
    val picksToImport: List<Shot>,
    val newToImport: List<Shot>,
    val total: Int,
    /** Not-yet-imported videos: left out of the bulk buttons (they can be gigabytes). */
    val videosToImport: List<Shot> = emptyList(),
)

/** Counts and import candidates. The grid's own structure is [buildLayout]. */
private fun buildSnapshot(all: List<Shot>): LibrarySnapshot {
    var nNew = 0; var nPicks = 0
    val picks = ArrayList<Shot>(); val fresh = ArrayList<Shot>(); val videos = ArrayList<Shot>()
    for (s in all) {
        val isNew = !s.wasImported
        val isPick = s.isKeeper
        if (isNew) nNew++
        if (isPick) nPicks++
        if (isNew && !s.isVideo) { fresh += s; if (isPick) picks += s }
        if (isNew && s.isVideo) videos += s
    }
    return LibrarySnapshot(intArrayOf(all.size, nNew, nPicks), picks, fresh, all.size, videos)
}

/**
 * Just the grid's structure. Kept apart from the counts so an import finishing (which flips
 * `wasImported`) doesn't rebuild the grid unless the filter actually depends on it ("Not imported").
 * A data class, so an unchanged layout compares equal and nothing downstream recomposes.
 */
private data class GridLayout(val days: List<Pair<LocalDate, List<Shot>>>, val visible: List<Shot>)

private fun buildLayout(all: List<Shot>, filter: ShotFilter, options: FilterOptions): GridLayout {
    val today = LocalDate.now()
    val vis = ArrayList<Shot>()
    for (s in all) if (filter.matches(s) && options.matches(s, today)) vis += s
    val groups = LinkedHashMap<LocalDate, MutableList<Shot>>()
    for (s in vis) groups.getOrPut(day(s.fileDate)) { ArrayList() }.add(s)
    return GridLayout(groups.entries.sortedByDescending { it.key }.map { it.key to it.value as List<Shot> }, vis)
}

private fun day(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

/** Grid sizes in the overflow menu: large / medium / small tiles. Columns follow the screen width. */
private fun columnsFor(width: Dp, size: Int): Int = when (size) {
    0 -> maxOf(3, (width / 132.dp).toInt())
    1 -> maxOf(4, (width / 96.dp).toInt())
    else -> maxOf(6, (width / 64.dp).toInt())
}

@Composable
fun LibraryScreen(app: AppState, session: CameraSession) {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf(ShotFilter.ALL) }
    var options by rememberSaveable(stateSaver = FilterOptions.Saver) { mutableStateOf(FilterOptions()) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf(setOf<String>()) }
    var gridSize by rememberSaveable { mutableIntStateOf(1) }
    val grid = rememberLazyGridState()
    val view = androidx.compose.ui.platform.LocalView.current

    // The import notification and the photo-location source are asked for when the first import
    // starts (that's when they matter), never just for opening a card.
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    val importing = app.importer.isRunning
    // Waits for the paywall (shown when the free quota can't cover the whole run) to close, so
    // the system dialogs come one at a time instead of stacking on top of it.
    LaunchedEffect(importing, app.showPaywall) {
        if (!importing || app.showPaywall || app.askedImportPermissions) return@LaunchedEffect
        val want = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (app.settings.borrowPhoneLocations) {
                // Android 10–12 read other apps' photos with READ_EXTERNAL_STORAGE; 13+ split it.
                add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
                add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            }
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        app.askedImportPermissions = true
        if (want.isNotEmpty()) perms.launch(want.toTypedArray())
    }

    // Select mode is a temporary state: back leaves it. Back never disconnects.
    BackHandler(enabled = selecting) { selecting = false; selection = emptySet() }

    val shots = session.shots
    // Recomputes only when what it reads changes (the shot list, a filter, a header landing,
    // an import finishing), not on each progress tick.
    // Counts and the import buttons: read only inside the pieces that show them (lambdas below),
    // so an import tick recomposes the tab counts and the bottom bar, not this whole screen.
    val snapState = remember(shots) { derivedStateOf { buildSnapshot(shots) } }
    val snapOf = { snapState.value }
    val layout by remember(shots, filter, options) { derivedStateOf(structuralEqualityPolicy()) { buildLayout(shots, filter, options) } }

    fun recapCandidates(): List<Shot> {
        val picked = layout.visible.filter { it.id in selection }
        if (picked.isNotEmpty()) return picked
        val keepers = shots.filter { it.isKeeper }
        return keepers.ifEmpty { shots.take(12) }
    }

    Column(Modifier.fillMaxSize().background(Mono.c.bg)) {
        LibraryTopBar(
            app, session, snapOf, selecting, gridSize,
            onSelectToggle = { selecting = !selecting; if (!selecting) selection = emptySet() },
            onGridSize = { gridSize = it },
            onRecap = { app.push(Screen.Recap(recapCandidates())) },
        )
        // Pinned filter bar.
        Column(Modifier.background(Mono.c.bg)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                TabStrip(ShotFilter.entries, filter, { it.label }, { snapOf().counts[it.ordinal] }, Modifier.weight(1f)) { filter = it }
                FilterMenu(options) { options = it }
                Spacer(Modifier.width(4.dp))
            }
            ScanProgressLine(session)
        }

        // A filter change dips the grid and lifts it back in, so the change reads as one move.
        val reduce = LocalReduceMotion.current
        val swap = remember { Animatable(1f) }
        var lastFilter by remember { mutableStateOf(filter to options) }
        LaunchedEffect(filter, options) {
            if (lastFilter == (filter to options)) return@LaunchedEffect
            lastFilter = filter to options
            swap.snapTo(0f)
            swap.animateTo(1f, if (reduce) tween(180) else spring(dampingRatio = 0.85f, stiffness = 380f))
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().graphicsLayer {
            alpha = (0.15f + 0.85f * swap.value).coerceIn(0f, 1f)
            if (!reduce) translationY = (1f - swap.value) * 24.dp.toPx()
        }) {
            val columns = columnsFor(maxWidth, gridSize)
            val status = session.status
            when {
                status is SessionStatus.Failed -> QuietMessage("Connection lost", status.message)
                shots.isEmpty() -> LoadingState(status, columns)
                layout.visible.isEmpty() -> {
                    val plain = !options.isActive
                    QuietMessage(
                        if (filter == ShotFilter.NEW && plain) "All caught up" else "Nothing here",
                        when {
                            filter == ShotFilter.NEW && plain -> "Everything on this card is already in Photos."
                            filter == ShotFilter.PICKS && plain -> "Rate or protect shots on the camera and they show up here."
                            else -> "No shots match these filters."
                        },
                    )
                }
                else -> LazyVerticalGrid(
                    GridCells.Fixed(columns), Modifier.fillMaxSize(), state = grid,
                    contentPadding = PaddingValues(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for ((d, list) in layout.days) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "day-$d", contentType = "day") {
                            DayHeader(d, list, selecting, selection) { selection = it }
                        }
                        items(list, key = { it.id }, contentType = { "shot" }) { shot ->
                            ShotCell(
                                session, shot, selecting, shot.id in selection,
                                onTap = {
                                    if (selecting) { Haptics.tick(view); selection = if (shot.id in selection) selection - shot.id else selection + shot.id }
                                    else app.push(Screen.Detail(layout.visible, shot.id))
                                },
                                onOpen = { app.push(Screen.Detail(layout.visible, shot.id)) },
                                onImport = { app.importShots(listOf(shot)) },
                                onSelect = { selecting = true; selection = selection + shot.id },
                            )
                        }
                    }
                }
            }
        }

        LibraryBottomBar(app, session, snapOf, layout.visible, selecting, selection) { selecting = false; selection = emptySet() }
    }
}

private fun dayTitle(d: LocalDate): String = when (d) {
    LocalDate.now() -> "Today"
    LocalDate.now().minusDays(1) -> "Yesterday"
    else -> d.format(DateTimeFormatter.ofPattern("EEEE d MMM", Locale.getDefault()))
}

@Composable
private fun DayHeader(d: LocalDate, list: List<Shot>, selecting: Boolean, selection: Set<String>, onSelection: (Set<String>) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = Mono.gutter, end = 4.dp, top = 14.dp, bottom = 4.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(dayTitle(d), style = Mono.subhead.copy(fontWeight = FontWeight.SemiBold), color = Mono.c.ink)
        Spacer(Modifier.width(8.dp))
        AnimatedCount(list.size, Mono.data(13.sp), Mono.c.dim)
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(selecting, enter = fadeIn(tween(200)) + scaleIn(Motion.bouncy(), initialScale = 0.8f), exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.9f)) {
            val allIn = list.all { it.id in selection }
            Box(Modifier.heightIn(min = 48.dp).pressDim {
                onSelection(if (allIn) selection - list.map { it.id }.toSet() else selection + list.map { it.id })
            }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(if (allIn) "Deselect" else "Select all", label = "dayAll", transitionSpec = { fadeIn(tween(180)).togetherWith(fadeOut(tween(120))) }) {
                    Text(it, style = Mono.footnote.copy(fontWeight = FontWeight.Medium), color = Mono.c.ink)
                }
            }
        }
    }
}

/** Grey tiles with a light sweeping across them while the card is opened and read. One canvas, one sweep. */
@Composable
private fun SkeletonGrid(columns: Int, rows: Int = 4) {
    val tile = Mono.c.tile
    val reduce = LocalReduceMotion.current
    val sweep = if (reduce) null else rememberSweep(1500)
    Canvas(Modifier.fillMaxWidth().aspectRatio(columns.toFloat() / rows)) {
        val gap = 2.dp.toPx()
        val side = (size.width - gap * (columns - 1)) / columns
        val t = sweep?.value ?: 0f
        val band = size.width * 0.6f
        val x = -band + (size.width + size.height + 2 * band) * t
        val brush = if (sweep == null) SolidColor(tile) else Brush.linearGradient(
            listOf(tile, Color(0xFF353535), tile),
            start = Offset(x - band / 2, 0f), end = Offset(x + band / 2, size.height),
        )
        for (r in 0 until rows) for (c in 0 until columns) {
            // Later tiles fade, so the skeleton suggests "more below" without a hard edge.
            drawRect(brush, Offset(c * (side + gap), r * (side + gap)), Size(side, side), alpha = 1f - r * 0.22f)
        }
    }
}

@Composable
private fun LoadingState(status: SessionStatus, columns: Int) {
    val loading = status == SessionStatus.Connecting || status is SessionStatus.Cataloging
    Column(Modifier.fillMaxWidth().padding(top = if (loading) 0.dp else 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // A skeleton only while something is actually loading: a ready, empty card is final.
        if (loading) {
            SkeletonGrid(columns)
            Spacer(Modifier.height(20.dp))
        }
        Text(
            when (status) {
                SessionStatus.Connecting -> "Opening the camera…"
                is SessionStatus.Cataloging -> if (status.percent > 0) "Reading card · ${status.percent}%" else "Reading card…"
                else -> "No photos on this card"
            },
            style = Mono.data(13.sp), color = Mono.c.dim,
        )
        if (status == SessionStatus.Ready) {
            // Some cameras (and phones) share their photos only after a prompt on the device.
            Spacer(Modifier.height(8.dp))
            Text(
                "If the camera shows a prompt, confirm it. Photos appear here on their own.",
                style = Mono.data(12.sp), color = Mono.c.faint, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
        }
    }
}

// Top bar

private val SourceKind.icon: Int get() = when (this) {
    SourceKind.USB -> Ph.usb
    SourceKind.CARD -> Ph.sd
    SourceKind.WIFI -> Ph.wifi
    SourceKind.DEMO -> Ph.film
}

@Composable
private fun LibraryTopBar(
    app: AppState, session: CameraSession, snap: () -> LibrarySnapshot, selecting: Boolean, gridSize: Int,
    onSelectToggle: () -> Unit, onGridSize: (Int) -> Unit, onRecap: () -> Unit,
) {
    var connMenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    Row(
        Modifier.background(Mono.c.bg).statusBarsPadding().fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            IconButton(session.kind.icon, "Connection: ${session.kind.label}") { connMenu = true }
            MonoMenu(connMenu, { connMenu = false }) {
                MenuLabel(session.kind.label)
                MenuItem("Disconnect", Ph.eject) { connMenu = false; app.disconnect() }
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 6.dp)) {
            Text(session.name, style = Mono.headline, color = Mono.c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle(session, snap()), style = Mono.data(12.sp), color = Mono.c.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextAction(if (selecting) "Done" else "Select", bold = selecting, onClick = onSelectToggle)
        Box {
            IconButton(Ph.moreVert, "More options") { more = true }
            MonoMenu(more, { more = false }) {
                for ((n, label) in listOf(0 to "Large tiles", 1 to "Medium tiles", 2 to "Small tiles")) {
                    MenuItem(label, checked = gridSize == n) { onGridSize(n); more = false }
                }
                MenuDivider()
                MenuItem("Make a recap card", Ph.images) { more = false; onRecap() }
                MenuDivider()
                MenuLabel("Tools")
                MenuItem("Photo walks", Ph.path) { more = false; app.push(Screen.Walks) }
                MenuItem("Recipes", Ph.film) { more = false; app.push(Screen.Recipes) }
                MenuItem("Clock sync", Ph.timer) { more = false; app.push(Screen.ClockSync) }
                MenuItem("Settings", Ph.gear) { more = false; app.push(Screen.Settings) }
                MenuDivider()
                MenuItem("Disconnect", Ph.eject) { more = false; app.disconnect() }
            }
        }
    }
}

/** "342 photos · 128 not imported", or what the connection is doing. */
private fun subtitle(session: CameraSession, snap: LibrarySnapshot): String = when (val st = session.status) {
    SessionStatus.Connecting -> "Connecting…"
    is SessionStatus.Cataloging -> if (st.percent > 0) "Reading card · ${st.percent}%" else "Reading card…"
    is SessionStatus.Failed -> "Disconnected"
    SessionStatus.Ready -> {
        val new = snap.counts[ShotFilter.NEW.ordinal]
        val total = snap.total
        "$total ${if (total == 1) "photo" else "photos"}" + if (new in 1 until total) " · $new not imported" else ""
    }
}

@Composable
private fun FilterMenu(options: FilterOptions, onChange: (FilterOptions) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        val tint by animateColorAsState(if (options.isActive) Mono.c.accent else Mono.c.dim, Motion.snap(), label = "filterTint")
        IconButton(Ph.filter, if (options.isActive) "More filters, on" else "More filters", Modifier.popOn(options.isActive, 1.2f), tint = tint) { open = true }
        MonoMenu(open, { open = false }) {
            MenuLabel("Stars")
            MenuItem("Any rating", checked = options.minStars == 0) { onChange(options.copy(minStars = 0)); open = false }
            for (n in 1..5) {
                MenuItem("★".repeat(n) + if (n < 5) " or more" else "", checked = options.minStars == n) { onChange(options.copy(minStars = n)); open = false }
            }
            MenuDivider()
            MenuItem("Protected only", checked = options.protectedOnly) { onChange(options.copy(protectedOnly = !options.protectedOnly)); open = false }
            MenuItem("RAW only", checked = options.rawOnly) { onChange(options.copy(rawOnly = !options.rawOnly)); open = false }
            MenuItem("Shot today", checked = options.todayOnly) { onChange(options.copy(todayOnly = !options.todayOnly)); open = false }
            if (options.isActive) {
                MenuDivider()
                MenuItem("Clear filters") { onChange(FilterOptions()); open = false }
            }
        }
    }
}

// Menus: flat surface, hairline border, no shadow.

@Composable
fun MonoMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(
        expanded, onDismiss, shape = RoundedCornerShape(12.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp, shadowElevation = 0.dp, border = BorderStroke(0.5.dp, Mono.c.line), content = content,
    )
}

@Composable
fun MenuItem(text: String, icon: Int? = null, checked: Boolean? = null, onClick: () -> Unit) {
    val lead: (@Composable () -> Unit)? = icon?.let { res -> { PhIcon(res, null, size = 20.dp, tint = Mono.c.dim) } }
    val trail: (@Composable () -> Unit)? = if (checked == true) { { PhIcon(Ph.check, "Selected", size = 16.dp) } } else null
    DropdownMenuItem(
        text = { Text(text, style = Mono.body, color = Mono.c.ink) },
        onClick = onClick,
        leadingIcon = lead,
        trailingIcon = trail,
        modifier = Modifier.widthIn(min = 200.dp).then(if (checked != null) Modifier.semantics { selected = checked } else Modifier),
    )
}

@Composable
private fun MenuLabel(text: String) {
    Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = Mono.footnote.copy(fontWeight = FontWeight.SemiBold), color = Mono.c.dim)
}

@Composable
private fun MenuDivider() = HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = 0.5.dp, color = Mono.c.line)

/** A 2 dp amber line under the filters while the card is cataloged or ratings are being read. */
@Composable
private fun ScanProgressLine(session: CameraSession) {
    val status = session.status
    val shots = session.shots
    val progress: Float? = when {
        status is SessionStatus.Cataloging -> status.percent / 100f
        status == SessionStatus.Ready && shots.isNotEmpty() && !session.scanComplete ->
            session.scanned.toFloat() / maxOf(1, shots.count { !it.isVideo })
        else -> null
    }
    if (progress == null) { Hairline(); return }
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), spring(dampingRatio = 1f, stiffness = 120f), label = "scan")
    Box(Modifier.fillMaxWidth().height(2.dp).background(Mono.c.line).semantics { contentDescription = "Reading ratings" }) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            scaleX = p.coerceAtLeast(0.02f); transformOrigin = TransformOrigin(0f, 0.5f)
        }.background(Mono.c.accent).sheen(true, 0.5f))
    }
}

// Bottom bar: one smart import button, the selection, or the running import.

@Composable
private fun LibraryBottomBar(app: AppState, session: CameraSession, snapOf: () -> LibrarySnapshot, visible: List<Shot>, selecting: Boolean, selection: Set<String>, endSelection: () -> Unit) {
    val context = LocalContext.current
    val snap = snapOf()
    val mode = when {
        selecting -> 1
        app.importVisible -> 2
        session.shots.isNotEmpty() -> 0
        else -> -1
    }
    if (mode == -1) { Spacer(Modifier.navigationBarsPadding()); return }
    val reduce = LocalReduceMotion.current
    Column(Modifier.fillMaxWidth().background(Mono.c.surface)) {
        Hairline()
        AnimatedContent(
            mode, Modifier.fillMaxWidth().navigationBarsPadding(), label = "bar",
            transitionSpec = {
                // Bars swap by sliding: the new one comes up from below, the old one drops away.
                if (reduce) fadeIn(tween(220)).togetherWith(fadeOut(tween(150)))
                else (slideInVertically(Motion.float()) { it / 2 } + fadeIn(tween(220)) + scaleIn(Motion.float(), initialScale = 0.96f))
                    .togetherWith(slideOutVertically(tween(180)) { it / 3 } + fadeOut(tween(140)))
                    .using(SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 0.9f, stiffness = 450f) })
            },
        ) { m ->
            Box(Modifier.fillMaxWidth().padding(start = Mono.gutter, end = Mono.gutter, top = 10.dp, bottom = 8.dp)) {
                when (m) {
                    1 -> {
                        val selected = remember(visible, selection) { visible.filter { it.id in selection } }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                if (selected.isEmpty()) Text("Select photos", style = Mono.data(15.sp, FontWeight.SemiBold), color = Mono.c.ink)
                                else AnimatedCount(selected.size, Mono.data(15.sp, FontWeight.SemiBold), Mono.c.ink, suffix = " selected")
                                if (selected.isNotEmpty()) Text(Formatter.formatShortFileSize(context, selected.sumOf { it.totalBytes }), style = Mono.data(12.sp), color = Mono.c.dim)
                            }
                            SecondaryButton("Recap", Modifier.widthIn(min = 88.dp), enabled = selected.isNotEmpty()) { app.push(Screen.Recap(selected)) }
                            PrimaryButton("Import", Modifier.widthIn(min = 100.dp), enabled = selected.isNotEmpty()) { app.importShots(selected); endSelection() }
                        }
                    }
                    2 -> ImportStatusBar(app)
                    else -> {
                        val picks = snap.picksToImport
                        val fresh = snap.newToImport
                        when {
                            picks.isNotEmpty() -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PrimaryButton("Import ${picks.size} ${if (picks.size == 1) "pick" else "picks"}", Modifier.weight(1f)) { app.importShots(picks) }
                                if (fresh.size > picks.size) SecondaryButton("All ${fresh.size}", Modifier.widthIn(min = 112.dp)) { app.importShots(fresh) }
                            }
                            fresh.isNotEmpty() -> PrimaryButton("Import ${fresh.size} new ${if (fresh.size == 1) "photo" else "photos"}", Modifier.fillMaxWidth()) { app.importShots(fresh) }
                            // Every photo is in; only videos are left. Say so instead of "everything".
                            snap.videosToImport.isNotEmpty() -> snap.videosToImport.let { v ->
                                SecondaryButton("Import ${v.size} ${if (v.size == 1) "video" else "videos"}", Modifier.fillMaxWidth()) { app.importShots(v) }
                            }
                            else -> Row(Modifier.fillMaxWidth().heightIn(min = 50.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                PhIcon(Ph.checkCircle, null, Modifier.riseIn(0, "all-in").popOn(snap.total, 1.3f), size = 18.dp, tint = Mono.c.dim)
                                Spacer(Modifier.width(8.dp))
                                Text("Everything here is in Photos", style = Mono.subhead, color = Mono.c.dim)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Cell

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShotCell(
    session: CameraSession, shot: Shot, selecting: Boolean, selected: Boolean,
    onTap: () -> Unit, onOpen: () -> Unit, onImport: () -> Unit, onSelect: () -> Unit,
) {
    // Re-runs when the thumbnail cache evicted this cell's image while it was still on screen.
    val thumb = shot.thumbnail
    LaunchedEffect(shot.id, thumb == null) { if (thumb == null) Previews.thumbnail(shot, session) }
    LaunchedEffect(shot.id) { session.prioritize(shot) }
    // A cached thumbnail shows at once; one that arrives later fades in over 200 ms.
    val fade = remember(shot.id) { Animatable(if (thumb != null) 1f else 0f) }
    LaunchedEffect(thumb != null) { if (thumb != null) fade.animateTo(1f, tween(200)) }
    var menu by remember { mutableStateOf(false) }
    val view = androidx.compose.ui.platform.LocalView.current
    // Pop the "in Photos" check only if the import finished while this cell was on screen.
    val importedAtStart = remember(shot.id) { shot.wasImported }

    Box(
        Modifier.aspectRatio(1f).background(Mono.c.tile)
            .semantics(mergeDescendants = true) {
                contentDescription = buildList {
                    add(shot.primary.name)
                    if (shot.rating > 0) add("${shot.rating} stars")
                    if (shot.isLocked) add("protected")
                    if (shot.wasImported) add("in Photos")
                }.joinToString(", ")
                if (selecting) this.selected = selected
            }
            .combinedClickable(
                onClickLabel = if (selecting) "Select" else "Open", onLongClickLabel = "More options",
                onClick = onTap, onLongClick = { Haptics.press(view); menu = true },
            ),
    ) {
        Box(Modifier.fillMaxSize().sharedPhoto(shot.id)) {
            if (thumb != null) Image(thumb, null, Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }, contentScale = ContentScale.Crop)
            else if (shot.isVideo) PhIcon(Ph.video, null, Modifier.align(Alignment.Center), size = 20.dp, tint = Mono.c.dim)
        }
        if (shot.isKeeper) {
            Row(
                Modifier.align(Alignment.TopStart).padding(4.dp).background(Color.Black.copy(alpha = 0.55f), Mono.badgeShape).padding(horizontal = 5.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (shot.rating > 0) {
                    PhIcon(Ph.star, null, size = 10.dp, tint = Color.White)
                    Text("${shot.rating}", style = Mono.data(10.sp, FontWeight.Bold), color = Color.White)
                }
                if (shot.isLocked) PhIcon(Ph.lock, null, size = 10.dp, tint = Color.White)
            }
        }
        if (shot.isVideo && thumb != null) {
            PhIcon(Ph.video, null, Modifier.align(Alignment.BottomStart).padding(5.dp), size = 12.dp, tint = Color.White)
        }
        ImportOverlay(shot, selecting, pop = !importedAtStart)
        if (selecting) SelectionMark(selected, Modifier.align(Alignment.TopEnd))

        MonoMenu(menu, { menu = false }) {
            MenuItem("Open", Ph.images) { menu = false; onOpen() }
            if (!shot.wasImported && shot.importState == ImportState.None || shot.importState is ImportState.Failed) {
                MenuItem("Import", Ph.download) { menu = false; if (shot.importState is ImportState.Failed) shot.importState = ImportState.None; onImport() }
            }
            if (!selecting) MenuItem("Select", Ph.checkCircle) { menu = false; onSelect() }
        }
    }
}

/** Queued: dimmed. Importing: a thin amber fill along the bottom. Failed: a warning. Done: a small check. */
@Composable
private fun ImportOverlay(shot: Shot, selecting: Boolean, pop: Boolean) {
    when (val st = shot.importState) {
        ImportState.Queued -> Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        is ImportState.Importing -> {
            val f by animateFloatAsState(st.fraction.toFloat().coerceIn(0f, 1f), tween(250), label = "cell")
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f))) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).graphicsLayer {
                    scaleX = f.coerceAtLeast(0.03f); transformOrigin = TransformOrigin(0f, 0.5f)
                }.background(Mono.c.accent))
            }
        }
        is ImportState.Failed -> Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
            PhIcon(Ph.warning, "Import failed", size = 18.dp, tint = Color.White)
        }
        else -> if (shot.wasImported && !selecting) {
            val reduce = LocalReduceMotion.current
            val k = remember { Animatable(if (pop && !reduce) 0f else 1f) }
            LaunchedEffect(Unit) { if (k.value < 1f) k.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f)) }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                Box(Modifier.padding(4.dp).size(16.dp).graphicsLayer { scaleX = k.value; scaleY = k.value; alpha = k.value.coerceIn(0f, 1f) }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(11.dp).background(Color.Black, CircleShape))
                    PhIcon(Ph.checkCircle, null, size = 16.dp, tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier) {
    val reduce = LocalReduceMotion.current
    // 0 = empty ring, 1 = filled check; springs with a little overshoot. Finite: nothing loops in cells.
    val on by animateFloatAsState(if (selected) 1f else 0f, if (reduce) tween(120) else spring(dampingRatio = 0.5f, stiffness = 600f), label = "sel")
    val scrim = on.coerceIn(0f, 1f)
    if (scrim > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = scrim }.background(Color.Black.copy(alpha = 0.25f)))
    Box(
        modifier.padding(6.dp).size(22.dp).graphicsLayer { val k = 0.85f + 0.15f * on; scaleX = k; scaleY = k }
            .background(lerp(Color.Black.copy(alpha = 0.2f), Color.White, scrim), CircleShape)
            .border(1.5.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on > 0.01f) PhIcon(Ph.check, null, Modifier.graphicsLayer { scaleX = on; scaleY = on; alpha = scrim }, size = 12.dp, tint = Color.Black)
    }
}
