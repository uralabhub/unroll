package app.unroll.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.unroll.core.ByteRange
import app.unroll.core.FilmRecipe
import app.unroll.core.FrameOptions
import app.unroll.core.PhotoWalk
import app.unroll.core.ShotMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/** Tiny JSON-on-disk persistence helper (app-private files dir). */
class Disk(context: Context) {
    private val dir = context.filesDir
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun <T> load(s: KSerializer<T>, name: String): T? = try {
        File(dir, name).takeIf { it.exists() }?.readText()?.let { json.decodeFromString(s, it) }
    } catch (_: Exception) {
        null
    }

    fun <T> save(s: KSerializer<T>, value: T, name: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(json.encodeToString(s, value))
        tmp.renameTo(File(dir, name))
    }
}

/** Parsed headers keyed by shot id — reconnecting the same card is instant. */
class MetaCache(private val disk: Disk, private val scope: CoroutineScope) {
    @Serializable
    data class Entry(val meta: ShotMeta, val preview: ByteRange? = null)

    private val serializer = MapSerializer(String.serializer(), Entry.serializer())
    private val map: MutableMap<String, Entry> = java.util.concurrent.ConcurrentHashMap(disk.load(serializer, "meta-cache.json") ?: emptyMap())
    private var saveJob: Job? = null

    operator fun get(id: String): Entry? = map[id]

    operator fun set(id: String, e: Entry) {
        map[id] = e
        synchronized(this) {
            saveJob?.cancel()
            saveJob = scope.launch(Dispatchers.IO) {
                delay(2000)
                if (map.size > 30_000) map.clear()
                disk.save(serializer, HashMap(map), "meta-cache.json")
            }
        }
    }
}

/** Remembers what was already imported so "Import new" never duplicates. */
class ImportLedger(private val disk: Disk) {
    private val serializer = SetSerializer(String.serializer())
    var ids by mutableStateOf(disk.load(serializer, "imported.json") ?: emptySet())
        private set

    fun contains(id: String) = id in ids

    @Synchronized
    fun mark(id: String) {
        ids = ids + id
        disk.save(serializer, ids, "imported.json")
    }

    fun reset() {
        ids = emptySet()
        disk.save(serializer, ids, "imported.json")
    }
}

/** Every recipe seen on imported shots, grouped by signature. */
class RecipeBook(private val disk: Disk) {
    @Serializable
    data class Item(
        val recipe: FilmRecipe,
        val name: String? = null,
        val camera: String? = null,
        val count: Int,
        val lastUsed: Long,
    ) {
        val id: String get() = recipe.signature
        val title: String get() = name ?: recipe.filmSimulation
    }

    private val serializer = ListSerializer(Item.serializer())
    var items by mutableStateOf(disk.load(serializer, "recipes.json") ?: emptyList())
        private set

    val sorted: List<Item> get() = items.sortedByDescending { it.lastUsed }

    @Synchronized
    fun record(recipe: FilmRecipe, camera: String?, date: Long) {
        val i = items.indexOfFirst { it.recipe.signature == recipe.signature }
        items = if (i >= 0) {
            items.toMutableList().also { it[i] = it[i].copy(count = it[i].count + 1, lastUsed = maxOf(it[i].lastUsed, date)) }
        } else {
            items + Item(recipe, null, camera, 1, date)
        }
        disk.save(serializer, items, "recipes.json")
    }

    fun rename(id: String, name: String) {
        items = items.map { if (it.id == id) it.copy(name = name.ifBlank { null }) else it }
        disk.save(serializer, items, "recipes.json")
    }

    fun delete(id: String) {
        items = items.filterNot { it.id == id }
        disk.save(serializer, items, "recipes.json")
    }
}

/** Recorded Photo Walks (kept 60 days). */
class WalkStore(private val disk: Disk) {
    private val serializer = ListSerializer(PhotoWalk.serializer())
    var walks by mutableStateOf(
        (disk.load(serializer, "walks.json") ?: emptyList()).filter { it.start > System.currentTimeMillis() - 60L * 86_400_000 },
    )
        private set

    @Synchronized
    fun update(transform: (List<PhotoWalk>) -> List<PhotoWalk>) {
        walks = transform(walks)
        disk.save(serializer, walks, "walks.json")
    }
}

enum class AutoImportRule(val label: String) { OFF("Ask me"), KEEPERS("Picks only"), ALL_NEW("Everything new") }

enum class AlbumMode(val label: String) { SINGLE("“Unroll” album"), PER_CAMERA("One album per camera"), NONE("Camera folder only") }

/** User preferences (SharedPreferences-backed, observable from Compose). Every set persists. */
class Settings(context: Context, private val disk: Disk) {
    private val p: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val offsetsSerializer = MapSerializer(String.serializer(), Double.serializer())

    private val _autoImport = mutableStateOf(runCatching { AutoImportRule.valueOf(p.getString("autoImport", "OFF")!!) }.getOrDefault(AutoImportRule.OFF))
    var autoImport: AutoImportRule get() = _autoImport.value; set(v) { _autoImport.value = v; p.edit().putString("autoImport", v.name).apply() }

    private val _includeRAW = mutableStateOf(p.getBoolean("includeRAW", true))
    var includeRAW: Boolean get() = _includeRAW.value; set(v) { _includeRAW.value = v; p.edit().putBoolean("includeRAW", v).apply() }

    private val _geotag = mutableStateOf(p.getBoolean("geotag", true))
    var geotag: Boolean get() = _geotag.value; set(v) { _geotag.value = v; p.edit().putBoolean("geotag", v).apply() }

    private val _borrow = mutableStateOf(p.getBoolean("borrowPhone", true))
    var borrowPhoneLocations: Boolean get() = _borrow.value; set(v) { _borrow.value = v; p.edit().putBoolean("borrowPhone", v).apply() }

    private val _album = mutableStateOf(runCatching { AlbumMode.valueOf(p.getString("albumMode", "SINGLE")!!) }.getOrDefault(AlbumMode.SINGLE))
    var albumMode: AlbumMode get() = _album.value; set(v) { _album.value = v; p.edit().putString("albumMode", v.name).apply() }

    private val _autoEject = mutableStateOf(p.getBoolean("autoEject", false))
    var autoEject: Boolean get() = _autoEject.value; set(v) { _autoEject.value = v; p.edit().putBoolean("autoEject", v).apply() }

    /** Seconds the camera clock runs AHEAD of true time, per camera model. */
    private val _offsets = mutableStateOf(disk.load(offsetsSerializer, "clock-offsets.json") ?: emptyMap())
    val clockOffsets: Map<String, Double> get() = _offsets.value

    private val _frame = mutableStateOf(disk.load(FrameOptions.serializer(), "frame-options.json") ?: FrameOptions())
    var frameOptions: FrameOptions get() = _frame.value; set(v) { _frame.value = v; disk.save(FrameOptions.serializer(), v, "frame-options.json") }

    fun clockOffset(model: String?): Double = model?.let { clockOffsets[it] } ?: 0.0

    fun setClockOffset(model: String, seconds: Double?) {
        _offsets.value = if (seconds == null) clockOffsets - model else clockOffsets + (model to seconds)
        disk.save(offsetsSerializer, clockOffsets, "clock-offsets.json")
    }
}

/** Free tier: N imports per day. Pro: unlimited. */
class Quota(context: Context) {
    companion object { const val FREE_PER_DAY = 10 }

    private val p = context.getSharedPreferences("quota", Context.MODE_PRIVATE)
    private val today: String get() = LocalDate.now().toString()

    var usedToday by mutableStateOf(if (p.getString("day", null) == LocalDate.now().toString()) p.getInt("used", 0) else 0)
        private set

    val remaining: Int get() {
        if (p.getString("day", null) != today && usedToday != 0) usedToday = 0
        return maxOf(0, FREE_PER_DAY - usedToday)
    }

    @Synchronized
    fun consume() {
        if (p.getString("day", null) != today) usedToday = 0
        usedToday += 1
        p.edit().putString("day", today).putInt("used", usedToday).apply()
    }
}
