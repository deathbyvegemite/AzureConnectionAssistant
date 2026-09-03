package com.deathbyvegemite.platewatch.core.plate

/**
 * Turns raw OCR text into ranked plate candidates.
 *
 * A single camera frame of a car typically yields the plate number plus a pile of
 * junk: the state name, a dealer frame, a slogan, part of a bumper sticker. This
 * class throws that away and repairs what is left using the region's formats.
 */
class PlateTextParser(private val region: PlateRegion) {

    /** All candidates found across [lines], best first, one entry per distinct plate. */
    fun parse(lines: List<RecognizedLine>): List<PlateCandidate> {
        val best = LinkedHashMap<String, PlateCandidate>()
        for (line in lines) {
            for (candidate in parseLine(line)) {
                val incumbent = best[candidate.plate]
                if (incumbent == null || candidate.score > incumbent.score) {
                    best[candidate.plate] = candidate
                }
            }
        }
        return best.values.sortedByDescending { it.score }
    }

    /** The single most trustworthy reading in [lines], or `null` if nothing looked like a plate. */
    fun best(lines: List<RecognizedLine>): PlateCandidate? = parse(lines).firstOrNull()

    private fun parseLine(line: RecognizedLine): List<PlateCandidate> {
        val tokens = tokenize(line.text)
        if (tokens.isEmpty()) return emptyList()

        // Try each word on its own, and also the whole line glued together —
        // recognisers often split "BK47" and "QT" across two boxes.
        val subjects = buildList {
            addAll(tokens)
            if (tokens.size > 1) add(tokens.joinToString(""))
        }

        val out = ArrayList<PlateCandidate>()
        for (subject in subjects) {
            if (subject.length < MIN_PLATE_LENGTH) continue
            if (subject in region.noiseWords) continue
            val wholeLine = subject.length == tokens.sumOf { it.length }
            for (format in region.formats) {
                out += matchesIn(subject, format, line.confidence, wholeLine)
            }
        }
        return out
    }

    private fun matchesIn(
        subject: String,
        format: PlateFormat,
        ocrConfidence: Float,
        wholeLine: Boolean,
    ): List<PlateCandidate> {
        if (subject.length < format.length) return emptyList()
        val results = ArrayList<PlateCandidate>()
        val exactFit = subject.length == format.length

        for (start in 0..(subject.length - format.length)) {
            val builder = StringBuilder(format.length)
            var coercions = 0
            var ok = true
            for (i in 0 until format.length) {
                val original = subject[start + i]
                val fixed = CharCoercion.coerce(original, format.slots[i])
                if (fixed == null) { ok = false; break }
                if (fixed != original) coercions++
                builder.append(fixed)
            }
            if (!ok) continue

            val plate = builder.toString()
            if (plate in region.noiseWords) continue
            // A wholly wildcard format matches anything, so insist on a real
            // plate shape: at least one letter and at least one digit.
            if (format.specificity == 0 &&
                (plate.none { it.isDigit() } || plate.none { it.isLetter() })
            ) continue

            results += PlateCandidate(
                plate = plate,
                raw = subject.substring(start, start + format.length),
                formatId = format.id,
                coercions = coercions,
                score = score(format, ocrConfidence, coercions, exactFit, wholeLine),
            )
        }
        return results
    }

    /**
     * Confidence for one frame, on 0..1. Rewards an exact-length match against a
     * specific format, punishes every character we had to repair.
     */
    private fun score(
        format: PlateFormat,
        ocrConfidence: Float,
        coercions: Int,
        exactFit: Boolean,
        wholeLine: Boolean,
    ): Float {
        var s = BASE_SCORE
        s += ocrConfidence.coerceIn(0f, 1f) * OCR_WEIGHT
        s += if (exactFit) EXACT_FIT_BONUS else PARTIAL_FIT_BONUS
        if (exactFit && wholeLine) s += WHOLE_LINE_BONUS
        s += (format.specificity - 5).coerceIn(0, 3) * SPECIFICITY_WEIGHT
        s -= coercions * COERCION_PENALTY
        return s.coerceIn(0f, 1f)
    }

    private fun tokenize(text: String): List<String> =
        text.uppercase()
            .map { if (it in 'A'..'Z' || it in '0'..'9') it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }

    private companion object {
        const val MIN_PLATE_LENGTH = 5
        const val BASE_SCORE = 0.35f
        const val OCR_WEIGHT = 0.30f
        const val EXACT_FIT_BONUS = 0.25f
        const val PARTIAL_FIT_BONUS = 0.05f
        const val WHOLE_LINE_BONUS = 0.05f
        const val SPECIFICITY_WEIGHT = 0.03f
        const val COERCION_PENALTY = 0.10f
    }
}
