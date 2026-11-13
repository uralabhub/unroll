package app.unroll.core

/**
 * Decodes the Fujifilm maker note into a shareable film recipe.
 * Layout: "FUJIFILM" + u32 LE offset to IFD; IFD is little-endian, offsets relative to the note start.
 * Tag IDs and value tables follow ExifTool's FujiFilm table.
 */
object FujiMakerNote {
    object Tag {
        const val SHARPNESS = 0x1001
        const val WHITE_BALANCE = 0x1002
        const val SATURATION = 0x1003
        const val COLOR_TEMPERATURE = 0x1005
        const val WB_FINE_TUNE = 0x100A
        const val NOISE_REDUCTION = 0x100E
        const val CLARITY = 0x100F
        const val SHADOW_TONE = 0x1040
        const val HIGHLIGHT_TONE = 0x1041
        const val GRAIN_ROUGHNESS = 0x1047
        const val COLOR_CHROME = 0x1048
        const val BW_WARM_COOL = 0x1049
        const val BW_MAGENTA_GREEN = 0x104B
        const val GRAIN_SIZE = 0x104C
        const val COLOR_CHROME_BLUE = 0x104E
        const val FILM_MODE = 0x1401
        const val DYNAMIC_RANGE_SETTING = 0x1402
        const val DEVELOPMENT_DR = 0x1403
        const val RATING = 0x1431
        /** DRangePriority: 0 = Auto, 1 = Fixed. */
        const val DRANGE_PRIORITY = 0x1443
        /** DRangePriorityAuto: 1 = Weak, 2 = Strong, 3 = Plus. */
        const val DRANGE_PRIORITY_AUTO = 0x1444
        /** DRangePriorityFixed: 1 = Weak, 2 = Strong. */
        const val DRANGE_PRIORITY_FIXED = 0x1445
    }

    val filmModes: Map<Int, String> = mapOf(
        0x000 to "Provia", 0x100 to "Studio Portrait", 0x110 to "Studio Portrait Enhanced Saturation",
        0x120 to "Astia", 0x130 to "Studio Portrait Sharp", 0x200 to "Velvia", 0x300 to "Studio Portrait Ex",
        0x400 to "Velvia", 0x500 to "Pro Neg. Std", 0x501 to "Pro Neg. Hi", 0x600 to "Classic Chrome",
        0x700 to "Eterna", 0x800 to "Classic Negative", 0x900 to "Eterna Bleach Bypass",
        0xA00 to "Nostalgic Neg.", 0xB00 to "Reala Ace",
    )

    /** Saturation values that actually encode a monochrome film simulation. */
    val monoModes: Map<Int, String> = mapOf(
        0x300 to "Monochrome", 0x301 to "Monochrome + R", 0x302 to "Monochrome + Ye", 0x303 to "Monochrome + G",
        0x310 to "Sepia", 0x500 to "Acros", 0x501 to "Acros + R", 0x502 to "Acros + Ye", 0x503 to "Acros + G",
    )

    val colorLevels: Map<Int, String> = mapOf(
        0x000 to "0", 0x080 to "+1", 0x100 to "+2", 0x0C0 to "+3", 0x0E0 to "+4",
        0x180 to "-1", 0x400 to "-2", 0x4C0 to "-3", 0x4E0 to "-4", 0x200 to "Low",
    )

    val sharpnessLevels: Map<Int, String> = mapOf(
        0x00 to "-4", 0x01 to "-3", 0x02 to "-2", 0x03 to "0", 0x04 to "+2", 0x05 to "+3", 0x06 to "+4",
        0x82 to "-1", 0x84 to "+1",
    )

    val nrLevels: Map<Int, String> = mapOf(
        0x000 to "0", 0x100 to "+2", 0x180 to "+1", 0x1C0 to "+3", 0x1E0 to "+4",
        0x200 to "-2", 0x280 to "-1", 0x2C0 to "-3", 0x2E0 to "-4",
    )

    val whiteBalances: Map<Int, String> = mapOf(
        0x000 to "Auto", 0x001 to "Auto (White Priority)", 0x002 to "Auto (Ambience Priority)",
        0x100 to "Daylight", 0x200 to "Cloudy", 0x300 to "Fluorescent 1", 0x301 to "Fluorescent 2",
        0x302 to "Fluorescent 3", 0x303 to "Fluorescent 4", 0x304 to "Fluorescent 5",
        0x400 to "Incandescent", 0x500 to "Flash", 0x600 to "Underwater",
        0xF00 to "Custom 1", 0xF01 to "Custom 2", 0xF02 to "Custom 3", 0xF03 to "Custom 4", 0xF04 to "Custom 5",
        0xFF0 to "Kelvin",
    )

    fun effect(v: Int?): String? = when (v) {
        0 -> "Off"; 32 -> "Weak"; 64 -> "Strong"; else -> null
    }

    /**
     * GrainEffectRoughness (0x1047). Some bodies (e.g. X-HF1) fold the size into this tag:
     * ExifTool lists 16 = Weak/Small and 48 = Strong/Small.
     */
    fun grainRoughness(v: Int?): Pair<String, String?>? = when (v) {
        16 -> "Weak" to "Small"
        48 -> "Strong" to "Small"
        else -> effect(v)?.let { it to null }
    }

    /**
     * D-Range Priority. 0x1443 says Auto (0) or Fixed (1); the level lives in 0x1444 for Auto
     * (1 Weak, 2 Strong, 3 Plus) and 0x1445 for Fixed (1 Weak, 2 Strong). Auto shows as "Auto"
     * whatever level the camera picked. Anything else means the feature is off.
     */
    fun dRangePriority(mode: Int?, autoLevel: Int?, fixedLevel: Int?): String? = when (mode) {
        0 -> if (autoLevel in 1..3) "Auto" else null
        1 -> when (fixedLevel) { 1 -> "Weak"; 2 -> "Strong"; else -> null }
        else -> null
    }

    class Result(val recipe: FilmRecipe?, val rating: Int?)

    /** Returns the recipe (null if the note carries no film-simulation info) and the in-camera rating. */
    fun parse(v: ByteView, start: Int): Result {
        if (!v.matches(start, "FUJIFILM")) return Result(null, null)
        val ifd = v.u32(start + 8, true) ?: return Result(null, null)
        val t = TiffStructure(v, start, true)
        val e = t.entries(ifd)
        if (e.isEmpty()) return Result(null, null)
        fun int(tag: Int): Int? = t.int(e.tag(tag))?.toInt()

        val rating = int(Tag.RATING)
        val saturation = int(Tag.SATURATION)

        val sim: String = if (saturation != null && monoModes[saturation] != null) {
            monoModes.getValue(saturation)
        } else {
            val fm = int(Tag.FILM_MODE)
                // No FilmMode tag (e.g. the original X100, per ExifTool): we can't tell the simulation.
                ?: return Result(null, rating)
            // Values newer than the table (future firmware) are shown as-is, never guessed.
            filmModes[fm] ?: "Film sim 0x${fm.toString(16).uppercase()}"
        }

        val r = FilmRecipe(filmSimulation = sim)

        // Grain: roughness + size (size tag only exists on newer bodies).
        grainRoughness(int(Tag.GRAIN_ROUGHNESS))?.let { (rough, implied) ->
            if (rough == "Off") {
                r.grainEffect = "Off"
            } else {
                val size = when (int(Tag.GRAIN_SIZE)) { 16 -> "Small"; 32 -> "Large"; else -> implied }
                r.grainEffect = listOfNotNull(rough, size).joinToString(", ")
            }
        }
        r.colorChrome = effect(int(Tag.COLOR_CHROME))
        r.colorChromeBlue = effect(int(Tag.COLOR_CHROME_BLUE))

        int(Tag.WHITE_BALANCE)?.let { wb ->
            r.whiteBalance = whiteBalances[wb] ?: "WB ${wb.toString(16)}"
            if (wb == 0xFF0) r.kelvin = int(Tag.COLOR_TEMPERATURE)
        }
        val ft = e.tag(Tag.WB_FINE_TUNE)
        if (ft != null && ft.count >= 2) {
            val red = t.int(ft, 0)?.toInt()
            val blue = t.int(ft, 1)?.toInt()
            if (red != null && blue != null) {
                // Newer bodies store 20 × the in-camera ±9 step (ExifTool 0x100A). Like Exiv2, only
                // scale when both values are whole multiples of 20; otherwise keep the raw numbers.
                val scaled = red % 20 == 0 && blue % 20 == 0
                r.wbShiftRed = if (scaled) red / 20 else red
                r.wbShiftBlue = if (scaled) blue / 20 else blue
            }
        }

        val drSetting = int(Tag.DYNAMIC_RANGE_SETTING)
        if (drSetting == 0) {
            r.dynamicRange = "DR-Auto"
        } else {
            val dr = int(Tag.DEVELOPMENT_DR)
            if (dr != null && dr >= 100) r.dynamicRange = "DR$dr"
        }
        r.dRangePriority = dRangePriority(int(Tag.DRANGE_PRIORITY), int(Tag.DRANGE_PRIORITY_AUTO), int(Tag.DRANGE_PRIORITY_FIXED))

        // Tones: stored as -16 × value, half steps allowed (-8 = +0.5). "+ 0.0" turns -0.0 into 0.0.
        int(Tag.HIGHLIGHT_TONE)?.let { r.highlight = -it.toDouble() / 16 + 0.0 }
        int(Tag.SHADOW_TONE)?.let { r.shadow = -it.toDouble() / 16 + 0.0 }

        if (saturation != null && monoModes[saturation] == null) r.color = colorLevels[saturation]
        int(Tag.SHARPNESS)?.let { r.sharpness = sharpnessLevels[it] }
        int(Tag.NOISE_REDUCTION)?.let { r.noiseReduction = nrLevels[it] }
        int(Tag.CLARITY)?.let { r.clarity = it / 1000 }
        if (r.isMonochrome) {
            // Both are signed bytes (ExifTool int8s, Exiv2 casts to int8_t): -2 is stored as 0xFE.
            r.monoWarmCool = int(Tag.BW_WARM_COOL)?.toByte()?.toInt()
            r.monoMagentaGreen = int(Tag.BW_MAGENTA_GREEN)?.toByte()?.toInt()
        }
        return Result(r, rating)
    }
}
