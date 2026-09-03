package com.deathbyvegemite.platewatch.core.plate

/**
 * One plausible reading of a plate taken from a single frame.
 *
 * @param plate      the repaired text, e.g. `BK47QT`
 * @param raw        the characters exactly as the recogniser produced them
 * @param formatId   which [PlateFormat] it matched
 * @param coercions  how many characters had to be repaired to make it fit
 * @param score      0..1 — how much to trust this single frame
 */
data class PlateCandidate(
    val plate: String,
    val raw: String,
    val formatId: String,
    val coercions: Int,
    val score: Float,
)

/** A line of text from the recogniser, with its own confidence if one is available. */
data class RecognizedLine(val text: String, val confidence: Float = 0.5f)
