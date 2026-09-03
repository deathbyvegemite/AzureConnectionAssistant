package com.deathbyvegemite.platewatch.core.plate

/** What kind of character is allowed in one position of a plate format. */
enum class Slot { LETTER, DIGIT, ANY }

/**
 * A single plate layout, described by a mask.
 *
 * Mask characters:
 *  - `L` letter only
 *  - `D` digit only
 *  - `A` letter or digit
 *
 * The mask is what lets us repair OCR mistakes: if slot 3 can only ever hold a
 * digit then a recognised `O` there is really a `0`, and we can say so with
 * confidence instead of guessing.
 */
data class PlateFormat(val id: String, val label: String, val mask: String) {

    val slots: List<Slot> = mask.map { c ->
        when (c) {
            'L' -> Slot.LETTER
            'D' -> Slot.DIGIT
            'A' -> Slot.ANY
            else -> throw IllegalArgumentException("Unsupported mask character '$c' in mask '$mask'")
        }
    }

    val length: Int get() = slots.size

    /** How specific this layout is; used to break ties between competing matches. */
    val specificity: Int = slots.count { it != Slot.ANY }
}
