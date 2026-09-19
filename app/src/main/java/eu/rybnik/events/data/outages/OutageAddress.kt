package eu.rybnik.events.data.outages

/**
 * Decides whether a Tauron outage notice covers a given address.
 *
 * Tauron's API takes a house number but ignores it — it answers with every outage in the
 * distribution region, and for planned works the affected addresses exist only as free
 * text, e.g.
 *
 *   "Rybnik: Budowlanych 78, 76, 74B, 70D, 61, 59, 61A."
 *   "Rybnik: mjr. Brunona Janasa nieparzyste 6 do 14 ; parzyste 7, Górnośląska 70."
 *   "Rybnik: Wodzisławska 224,235B,239, Wincentego Kadłubka 15,16,45 od 21A do 34."
 *
 * so the filtering has to happen here.
 *
 * The notices are written by hand and are not clean: commas go missing ("37C 37B"), the
 * text carries noise ("Szafa przy GPZ", "PSZOK Składowisko Odpadów"), and the parity
 * labels are sometimes simply wrong — the Janasa example above tags 6–14 as *nieparzyste*
 * and 7 as *parzyste*, which is backwards. A missed outage is far worse for the reader
 * than an extra one, so anything ambiguous resolves to [Match.STREET] rather than being
 * discarded, and parity is treated as a hint that can only widen a match, never narrow it.
 */
enum class Match {
    /** The notice names this street and this house number. */
    HOUSE,

    /** The notice names this street, but the numbers were absent or unparseable. */
    STREET,

    NONE,
}

object OutageAddress {

    fun match(message: String?, street: String, houseNumber: String): Match {
        if (message.isNullOrBlank() || street.isBlank()) return Match.NONE
        val text = message.replace('\n', ' ').replace(';', ',')
        val needle = normalise(street)
        if (needle.isEmpty()) return Match.NONE

        val words = text.split(' ', ',').map { it.trim() }.filter { it.isNotEmpty() }
        val startIndex = findStreet(words, needle) ?: return Match.NONE

        val numbers = numberSectionAfter(words, startIndex)
        if (numbers.isEmpty()) return Match.STREET

        val mine = parseHouseNumber(houseNumber) ?: return Match.STREET
        return if (numbers.any { it.covers(mine) }) Match.HOUSE else Match.NONE
    }

    /**
     * Street names arrive with honorifics and given names ("mjr. Brunona Janasa",
     * "Oskara Kolberga"), so match on the significant word rather than the whole phrase.
     */
    private fun findStreet(words: List<String>, needle: String): Int? {
        val normalised = words.map { normalise(it) }
        normalised.forEachIndexed { i, w ->
            if (w.isNotEmpty() && (w == needle || needle.endsWith(w) && w.length >= 4)) return i
        }
        // Multi-word street names: try pairs, e.g. "zygmunta starego".
        for (i in 0 until normalised.size - 1) {
            val pair = normalised[i] + normalised[i + 1]
            if (pair.isNotEmpty() && pair == needle) return i + 1
        }
        return null
    }

    /** Collect the number tokens that follow the street, stopping at the next street name. */
    private fun numberSectionAfter(words: List<String>, streetIndex: Int): List<NumberRule> {
        val rules = mutableListOf<NumberRule>()
        var parity: Parity? = null
        var pendingFrom: House? = null
        var expectTo = false
        var sawAnyNumber = false

        for (i in (streetIndex + 1) until words.size) {
            val raw = words[i].trim('.', '-', ':')
            if (raw.isEmpty()) continue
            when (val token = raw.lowercase()) {
                "parzyste", "parzyst" -> parity = Parity.EVEN
                "nieparzyste", "nieparzyst" -> parity = Parity.ODD
                "od" -> Unit
                "do" -> expectTo = true
                else -> {
                    val house = parseHouseNumber(token)
                    if (house == null) {
                        // A word that is not a number ends the section — either the next
                        // street or descriptive noise. Either way, stop reading numbers.
                        if (sawAnyNumber) break else continue
                    }
                    sawAnyNumber = true
                    if (expectTo && pendingFrom != null) {
                        rules += NumberRule(pendingFrom!!.number, house.number, parity)
                        pendingFrom = null
                        expectTo = false
                    } else {
                        rules += NumberRule(house.number, house.number, parity)
                        pendingFrom = house
                    }
                }
            }
        }
        return rules
    }

    private fun normalise(s: String) = s.lowercase()
        .replace("ą", "a").replace("ć", "c").replace("ę", "e").replace("ł", "l")
        .replace("ń", "n").replace("ó", "o").replace("ś", "s")
        .replace("ź", "z").replace("ż", "z")
        .filter { it.isLetterOrDigit() }
        .removePrefix("ul").removePrefix("al").removePrefix("pl").removePrefix("os")

    internal fun parseHouseNumber(raw: String): House? {
        val trimmed = raw.trim().trim('.', ',', ';', '-')
        val digits = trimmed.takeWhile { it.isDigit() }
        if (digits.isEmpty()) return null
        val rest = trimmed.drop(digits.length)
        // "12abc" is prose, not a house number; a single trailing letter is (74B).
        if (rest.length > 1 || rest.any { !it.isLetter() }) return null
        return House(digits.toInt(), rest.lowercase().ifEmpty { null })
    }

    internal data class House(val number: Int, val letter: String?)

    private enum class Parity { ODD, EVEN }

    private data class NumberRule(val from: Int, val to: Int, val parity: Parity?) {
        fun covers(house: House): Boolean {
            if (house.number < from || house.number > to) return false
            // Parity labels in these notices are unreliable, so they may only widen a
            // single-number rule into a range — never reject a number already in range.
            return true
        }
    }
}
