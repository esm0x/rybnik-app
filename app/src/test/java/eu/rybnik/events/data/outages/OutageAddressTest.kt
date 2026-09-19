package eu.rybnik.events.data.outages

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every message here is a real Tauron notice for Rybnik, copied verbatim — including the
 * ones with missing commas and with parity labels that contradict the numbers.
 */
class OutageAddressTest {

    private val budowlanych = "Rybnik: Budowlanych 78, 76, 74B, 70D, 61, 59, 61A."
    private val giedroycia =
        "Rybnik: Jerzego Giedroycia 12, 8, 6, 4, Szafa przy GPZ Paruszowiec\nProsta 23, Plac budowy Dino."
    private val kolberga = "Rybnik: Oskara Kolberga 21 do 60A, PSZOK Składowisko Odpadów."
    private val raclawicka =
        "Rybnik: Racławicka 5E, Zamenhofa 36D, Zygmunta starego 37J, 37I, 37K, 37F, 37G, 37D, 37C 37B, 37A, 37."
    private val sosnowa =
        "Rybnik: Sosnowa 7 - Centrum Chrześcijańskie Winnica, Hurtownia papierosów KING, AKFOL."
    private val wodzislawska =
        "Rybnik: Wodzisławska 224,235B,239,241,243,249, Wincentego Kadłubka 15,16,45 od 21A do 34."
    private val janasa = "Rybnik: mjr. Brunona Janasa nieparzyste 6 do 14 ; parzyste 7, Górnośląska 70."

    @Test
    fun `explicit number in a plain list matches`() {
        assertEquals(Match.HOUSE, OutageAddress.match(budowlanych, "Budowlanych", "78"))
        assertEquals(Match.HOUSE, OutageAddress.match(budowlanych, "Budowlanych", "59"))
    }

    @Test
    fun `number missing from the list does not match`() {
        assertEquals(Match.NONE, OutageAddress.match(budowlanych, "Budowlanych", "80"))
        assertEquals(Match.NONE, OutageAddress.match(budowlanych, "Budowlanych", "1"))
    }

    @Test
    fun `street not mentioned at all is not a match`() {
        assertEquals(Match.NONE, OutageAddress.match(budowlanych, "Astronautów", "9"))
        assertEquals(Match.NONE, OutageAddress.match(kolberga, "Sosnowa", "7"))
    }

    @Test
    fun `range with a letter bound covers numbers inside it`() {
        assertEquals(Match.HOUSE, OutageAddress.match(kolberga, "Oskara Kolberga", "30"))
        assertEquals(Match.HOUSE, OutageAddress.match(kolberga, "Kolberga", "21"))
        assertEquals(Match.NONE, OutageAddress.match(kolberga, "Kolberga", "61"))
    }

    @Test
    fun `street name given with an honorific still resolves`() {
        assertEquals(Match.HOUSE, OutageAddress.match(janasa, "mjr. Brunona Janasa", "8"))
        assertEquals(Match.HOUSE, OutageAddress.match(janasa, "Janasa", "14"))
    }

    @Test
    fun `contradictory parity labels never hide an in-range number`() {
        // The source tags 6-14 as "nieparzyste" and 7 as "parzyste" — both wrong. A reader
        // at number 7 or 8 must still be warned.
        assertEquals(Match.HOUSE, OutageAddress.match(janasa, "Janasa", "7"))
        assertEquals(Match.HOUSE, OutageAddress.match(janasa, "Janasa", "12"))
    }

    @Test
    fun `second street in the same notice is matched independently`() {
        assertEquals(Match.HOUSE, OutageAddress.match(janasa, "Górnośląska", "70"))
        assertEquals(Match.NONE, OutageAddress.match(janasa, "Górnośląska", "71"))
        assertEquals(Match.HOUSE, OutageAddress.match(wodzislawska, "Wincentego Kadłubka", "16"))
        assertEquals(Match.HOUSE, OutageAddress.match(wodzislawska, "Wodzisławska", "239"))
    }

    @Test
    fun `missing comma between numbers does not break the list`() {
        // "...37D, 37C 37B, 37A, 37." — 37C and 37B are not separated by a comma.
        assertEquals(Match.HOUSE, OutageAddress.match(raclawicka, "Zygmunta starego", "37"))
    }

    @Test
    fun `descriptive noise after the number ends the section`() {
        assertEquals(Match.HOUSE, OutageAddress.match(sosnowa, "Sosnowa", "7"))
        assertEquals(Match.NONE, OutageAddress.match(sosnowa, "Sosnowa", "9"))
    }

    @Test
    fun `newline inside a notice is treated as a separator`() {
        assertEquals(Match.HOUSE, OutageAddress.match(giedroycia, "Jerzego Giedroycia", "8"))
        assertEquals(Match.HOUSE, OutageAddress.match(giedroycia, "Prosta", "23"))
    }

    @Test
    fun `letter suffixes on the house number are tolerated`() {
        assertEquals(Match.HOUSE, OutageAddress.match(budowlanych, "Budowlanych", "74B"))
        assertEquals(Match.HOUSE, OutageAddress.match(budowlanych, "Budowlanych", "61a"))
    }

    @Test
    fun `blank input is never a match`() {
        assertEquals(Match.NONE, OutageAddress.match(null, "Budowlanych", "1"))
        assertEquals(Match.NONE, OutageAddress.match("", "Budowlanych", "1"))
        assertEquals(Match.NONE, OutageAddress.match(budowlanych, "", "1"))
    }

    @Test
    fun `house number parsing rejects prose`() {
        assertEquals(null, OutageAddress.parseHouseNumber("PSZOK"))
        assertEquals(null, OutageAddress.parseHouseNumber("12abc"))
        assertEquals(OutageAddress.House(74, "b"), OutageAddress.parseHouseNumber("74B"))
        assertEquals(OutageAddress.House(37, null), OutageAddress.parseHouseNumber("37,"))
    }
}
