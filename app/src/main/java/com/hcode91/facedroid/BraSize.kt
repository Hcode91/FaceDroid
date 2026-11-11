package com.hcode91.facedroid

/**
 * UK bra sizing from two tape measurements: band = under-chest rounded to the nearest even inch,
 * cup = one letter per inch that the bust is bigger than the band.
 */
object BraSize {
    val CUPS = listOf("AA", "A", "B", "C", "D", "DD", "E", "F", "FF", "G", "GG", "H", "HH", "J", "JJ", "K")

    fun band(underCm: Float): Int = Math.round(underCm / 2.54f / 2f) * 2
    fun cupIndex(bustCm: Float, band: Int): Int = Math.round(bustCm / 2.54f - band)
    fun cup(index: Int) = when {
        index < 0 -> "under AA"
        index >= CUPS.size -> "over K"
        else -> CUPS[index]
    }
    fun uk(bustCm: Float, underCm: Float): String {
        val b = band(underCm)
        val c = cupIndex(bustCm, b)
        return if (c < 0) "$b, smaller than AA" else if (c >= CUPS.size) "$b, larger than K" else "$b${CUPS[c]}"
    }
}
