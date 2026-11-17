package app.unroll.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Regression run over the raw.pixls.us compatibility corpus (one real header per camera model).
 * Skipped unless UNROLL_CORPUS points at a folder with:
 *   headers/NNNN.bin   the first 192 KB of each file (NNNN = row in models.json)
 *   models.json        [{brand, model, file}, …]
 *   extra/NNNN_<offset>_<length>.bin   optional extra ranges fetched for adaptive reads
 *   sizes.tsv          optional "NNNN<TAB>fileSize"
 * Writes kotlin-report.tsv there: one row per model, plus the ranges an adaptive read asked for
 * that are not cached yet (wanted.tsv), so they can be fetched and the run repeated.
 */
class CorpusRegressionTest {
    @Test fun corpus() {
        val root = System.getenv("UNROLL_CORPUS")?.let(::File)
        assumeTrue("UNROLL_CORPUS not set", root != null && File(root, "headers").isDirectory)
        root!!
        val files = Regex("\"file\"\\s*:\\s*\"([^\"]*)\"").findAll(File(root, "models.json").readText()).map { it.groupValues[1] }.toList()
        val sizes = File(root, "sizes.tsv").takeIf { it.exists() }?.readLines()
            ?.mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0].toInt() to it[1].toLong() } }?.toMap() ?: emptyMap()
        val extraDir = File(root, "extra")
        val extras = extraDir.listFiles().orEmpty().mapNotNull { f ->
            val p = f.nameWithoutExtension.split('_')
            if (p.size == 3) Triple(p[0].toInt(), p[1].toLong(), f) else null
        }.groupBy { it.first }

        val report = StringBuilder("idx\tfile\tlisted\tformat\tmake\tmodel\tpreview\tpreviewKnownJPEG\trgb\textra\trounds\n")
        val wanted = StringBuilder()
        files.forEachIndexed { i, name ->
            val head = File(root, "headers/%04d.bin".format(i)).takeIf { it.exists() }?.readBytes() ?: return@forEachIndexed
            val res = HeaderParser.parseAdaptive(head, sizes[i]) { r ->
                val hit = extras[i]?.firstOrNull { (_, off, f) -> off <= r.offset && off + f.length() >= r.offset + minOf(r.length, (sizes[i] ?: Long.MAX_VALUE) - r.offset) }
                if (hit == null) {
                    wanted.append("%04d\t%d\t%d\n".format(i, r.offset, r.length)); null
                } else {
                    val b = hit.third.readBytes()
                    val from = (r.offset - hit.second).toInt()
                    b.copyOfRange(from, minOf(b.size, from + r.length.toInt()))
                }
            }
            val info = res.info
            val p = info.preview
            val known = p?.let { res.view.u8(it.offset.toInt()) == 0xFF && res.view.u8(it.offset.toInt() + 1) == 0xD8 }
            report.append(listOf(
                i, name, ShotFormat.isMedia(name), info.format, info.meta.make, info.meta.model,
                p?.let { "${it.offset}+${it.length}" }, known,
                info.rgbPreview?.let { "${it.width}x${it.height}@${it.range.offset}" }, res.extraBytesRead, res.rounds,
            ).joinToString("\t") { it?.toString() ?: "" }).append('\n')
        }
        File(root, "kotlin-report.tsv").writeText(report.toString())
        File(root, "wanted.tsv").writeText(wanted.toString())
    }
}
