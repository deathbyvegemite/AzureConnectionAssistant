package com.deathbyvegemite.platewatch.core.plate

/**
 * The classic OCR confusion pairs. Text recognisers have no idea whether a glyph
 * on a number plate is meant to be a letter or a digit, so `0`/`O` and `1`/`I`
 * come back more or less at random. Knowing the format lets us pick correctly.
 */
internal object CharCoercion {

    private val TO_DIGIT: Map<Char, Char> = mapOf(
        'O' to '0', 'Q' to '0', 'D' to '0',
        'I' to '1', 'L' to '1', 'J' to '1',
        'Z' to '2', 'A' to '4', 'S' to '5',
        'G' to '6', 'T' to '7', 'B' to '8',
    )

    private val TO_LETTER: Map<Char, Char> = mapOf(
        '0' to 'O', '1' to 'I', '2' to 'Z',
        '4' to 'A', '5' to 'S', '6' to 'G',
        '7' to 'T', '8' to 'B',
    )

    /**
     * Coerce [c] into [slot], or return `null` when it simply cannot fit
     * (e.g. an `X` where only a digit is legal — no plausible confusion exists).
     */
    fun coerce(c: Char, slot: Slot): Char? = when (slot) {
        Slot.LETTER -> if (c in 'A'..'Z') c else TO_LETTER[c]
        Slot.DIGIT -> if (c in '0'..'9') c else TO_DIGIT[c]
        Slot.ANY -> if (c in 'A'..'Z' || c in '0'..'9') c else null
    }
}
