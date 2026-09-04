package com.deathbyvegemite.platewatch.core.plate

/**
 * A set of plate layouts to look for, plus the words printed on plates in that
 * region that must never be mistaken for a plate number.
 */
data class PlateRegion(
    val id: String,
    val label: String,
    val formats: List<PlateFormat>,
    val noiseWords: Set<String> = emptySet(),
)

/**
 * Built-in regions. These are deliberately a *common subset*, not an exhaustive
 * registry — every jurisdiction issues custom and personalised plates that no
 * mask will match. Pick the closest region in Settings and add masks as needed;
 * [GENERIC] is the permissive catch-all.
 */
object PlateRegions {

    private val COMMON_NOISE = setOf(
        "AUSTRALIA", "NEWSOUTHWALES", "NSW", "VICTORIA", "VIC", "QUEENSLAND", "QLD",
        "SOUTHAUSTRALIA", "WESTERNAUSTRALIA", "TASMANIA", "TAS", "NORTHERNTERRITORY",
        "ACT", "CANBERRA", "NEWZEALAND", "AOTEAROA",
        "CALIFORNIA", "TEXAS", "FLORIDA", "NEWYORK", "ONTARIO", "QUEBEC",
        "SUNSHINESTATE", "GARDENSTATE", "FIRSTINFLIGHT", "GRANDCANYON",
        "THEPLACETOBE", "SUNSHINE", "STATE", "TERRITORY", "GOV", "GOVT",
        "DEALER", "DEMO", "TAXI", "TRIAL", "SAMPLE", "PLATE", "PLATES",
    )

    /** Common Australian issue formats (NSW, VIC, QLD, WA, SA, TAS, ACT, NT). */
    val AU = PlateRegion(
        id = "AU",
        label = "Australia",
        formats = listOf(
            PlateFormat("au-lldll", "AB12CD (NSW/ACT current)", "LLDDLL"),
            PlateFormat("au-dlldll", "1AB2CD (VIC current)", "DLLDLL"),
            PlateFormat("au-dddlll", "123ABC (QLD older)", "DDDLLL"),
            PlateFormat("au-lllddd", "ABC123 (multi-state older)", "LLLDDD"),
            PlateFormat("au-dlllddd", "1ABC234 (WA)", "DLLLDDD"),
            PlateFormat("au-llldd", "ABC12", "LLLDD"),
            PlateFormat("au-lldddd", "AB1234", "LLDDDD"),
        ),
        noiseWords = COMMON_NOISE,
    )

    val NZ = PlateRegion(
        id = "NZ",
        label = "New Zealand",
        formats = listOf(
            PlateFormat("nz-lllddd", "ABC123", "LLLDDD"),
            PlateFormat("nz-llldd", "ABC12", "LLLDD"),
            PlateFormat("nz-lldddd", "AB1234", "LLDDDD"),
        ),
        noiseWords = COMMON_NOISE,
    )

    /** United States plates vary wildly by state; these cover the bulk of issue plates. */
    val US = PlateRegion(
        id = "US",
        label = "United States / Canada",
        formats = listOf(
            PlateFormat("us-llldddd", "ABC1234", "LLLDDDD"),
            PlateFormat("us-ddddlll", "1234ABC", "DDDDLLL"),
            PlateFormat("us-dlllddd", "1ABC234", "DLLLDDD"),
            PlateFormat("us-lllddd", "ABC123", "LLLDDD"),
            PlateFormat("us-dddllll", "123ABCD", "DDDLLLL"),
            PlateFormat("us-llldd", "ABC12", "LLLDD"),
        ),
        noiseWords = COMMON_NOISE,
    )

    val UK = PlateRegion(
        id = "UK",
        label = "United Kingdom",
        formats = listOf(
            PlateFormat("uk-current", "AB12CDE (current)", "LLDDLLL"),
            PlateFormat("uk-prefix", "A123BCD (prefix)", "LDDDLLL"),
            PlateFormat("uk-suffix", "ABC123D (suffix)", "LLLDDDL"),
        ),
        noiseWords = COMMON_NOISE + setOf("GB", "UK", "GREATBRITAIN"),
    )

    /**
     * Permissive fallback: any 5-8 alphanumerics. Catches personalised plates and
     * unusual regions, at the cost of many more false positives — raise the
     * confirmation count in Settings if you use it.
     */
    val GENERIC = PlateRegion(
        id = "GENERIC",
        label = "Generic (any 5-8 characters)",
        formats = listOf(
            PlateFormat("gen-8", "8 characters", "AAAAAAAA"),
            PlateFormat("gen-7", "7 characters", "AAAAAAA"),
            PlateFormat("gen-6", "6 characters", "AAAAAA"),
            PlateFormat("gen-5", "5 characters", "AAAAA"),
        ),
        noiseWords = COMMON_NOISE,
    )

    val all: List<PlateRegion> = listOf(AU, NZ, US, UK, GENERIC)

    fun byId(id: String?): PlateRegion = all.firstOrNull { it.id == id } ?: US
}
