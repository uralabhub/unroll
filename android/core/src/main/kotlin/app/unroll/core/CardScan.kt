package app.unroll.core

import java.io.File

/** One file on a card / camera, whatever the transport. [handle] is the transport's own reference. */
class CardItem<T>(
    val handle: T,
    val name: String,
    /** Parent folder name or path — RAW+JPEG halves share folder and stem. */
    val folder: String,
    val size: Long,
    val dateMillis: Long,
    /** Per-file "Protect" (FAT read-only attribute / PTP protection status). */
    val locked: Boolean,
) {
    val format: ShotFormat = ShotFormat.fromFilename(name)
    val stem: String get() = "$folder/${name.substringBeforeLast('.')}".lowercase()
}

/** A capture as the user thinks of it: a JPEG/HEIF (or video) and, optionally, its RAW twin. */
class PairedShot<T>(val primary: CardItem<T>, val raw: CardItem<T>?, val locked: Boolean) {
    val dateMillis: Long get() = primary.dateMillis
}

object Pairing {
    /** Every extension we list — see [ShotFormat.byExtension]. */
    val mediaExtensions: Set<String> get() = ShotFormat.byExtension.keys

    fun isMedia(name: String): Boolean = ShotFormat.isMedia(name)

    /**
     * Folder + file stem is how cameras name the halves of a RAW+JPEG pair (DSCF1234.JPG / .RAF).
     * A write-protected card or read-only mount makes *every* file read-only — that is not the
     * camera's per-file Protect, so it does not turn the whole card into keepers.
     */
    fun <T> pair(items: List<CardItem<T>>): List<PairedShot<T>> {
        val groups = LinkedHashMap<String, MutableList<CardItem<T>>>()
        for (i in items) if (i.format != ShotFormat.UNKNOWN) groups.getOrPut(i.stem) { mutableListOf() } += i
        val all = groups.values.flatten()
        val cardLocked = all.size > 1 && all.all { it.locked }
        return groups.values.mapNotNull { g ->
            val developed = g.firstOrNull { it.format.isDeveloped }
            val raw = g.firstOrNull { it.format.isRAW }
            val main = developed ?: raw ?: return@mapNotNull null
            PairedShot(main, if (developed != null) raw else null, !cardLocked && g.any { it.locked })
        }
    }
}

/** Where cameras put media on a card (DCF photos, Sony XAVC clips, AVCHD streams). */
object CardLayout {
    /** Paths relative to the card root, case-insensitive. The first one is the DCF tree. */
    val mediaRoots: List<List<String>> = listOf(
        listOf("DCIM"),
        listOf("PRIVATE", "M4ROOT", "CLIP"),
        listOf("PRIVATE", "AVCHD", "BDMV", "STREAM"),
    )
}

/** SD card in a reader, a copied DCIM folder, or the demo roll — anything with a DCIM tree on a file system. */
object FolderScanner {
    private fun child(dir: File, name: String): File? =
        dir.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }

    /** Existing media roots on the card; the card root itself when it has none (a copied folder). */
    fun roots(root: File): List<File> {
        val found = CardLayout.mediaRoots.mapNotNull { path -> path.fold(root as File?) { d, n -> d?.let { child(it, n) } } }
        return found.ifEmpty { listOf(root) }
    }

    fun scan(root: File): List<PairedShot<File>> {
        val items = mutableListOf<CardItem<File>>()
        for (dir in roots(root)) {
            dir.walkTopDown()
                .onEnter { it == dir || !it.name.startsWith(".") }
                .filter { it.isFile && !it.name.startsWith(".") && Pairing.isMedia(it.name) }
                .forEach { f ->
                    // Cameras implement "Protect" with the FAT read-only attribute.
                    items += CardItem(f, f.name, f.parentFile?.path ?: "", f.length(), f.lastModified(), !f.canWrite())
                }
        }
        return Pairing.pair(items)
    }
}

/**
 * Ricoh GR III / IIIx Wi-Fi (reverse-engineered HTTP API). The listing carries no dates or sizes,
 * but folder + file numbers are chronological and stable, so they give an ordering key.
 */
object RicohOrder {
    /** "100RICOH" + "R0001234.DNG" → 1_000_001_234. Null when the names aren't DCF-style. */
    fun orderKey(dir: String, file: String): Long? {
        val d = dir.takeWhile { it.isDigit() }.toLongOrNull() ?: return null
        val digits = file.substringBeforeLast('.').reversed().takeWhile { it.isDigit() }.reversed()
        val n = digits.toLongOrNull() ?: return null
        return d * 10_000_000 + n
    }
}
