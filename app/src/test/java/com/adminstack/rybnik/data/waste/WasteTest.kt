package com.adminstack.rybnik.data.waste

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The house number decides which rejon an address lands in, so a number the rules cannot
 * read is worse than no number at all: [StreetDto.matches] falls back to accepting the
 * street, and the app hands out a confident schedule for the wrong side of the district.
 */
class WasteTest {

    @Test
    fun `plain and lettered numbers are usable`() {
        assertTrue(isUsableHouseNumber("38"))
        assertTrue(isUsableHouseNumber("128B"))
        assertTrue(isUsableHouseNumber("  7a  "))
        assertTrue(isUsableHouseNumber("12/3"))
    }

    @Test
    fun `anything that does not start with a digit is rejected`() {
        assertFalse(isUsableHouseNumber("abc"))
        assertFalse(isUsableHouseNumber(""))
        assertFalse(isUsableHouseNumber("   "))
        assertFalse(isUsableHouseNumber("/12"))
        assertFalse(isUsableHouseNumber("nr 38"))
    }

    /** Typed by someone leaning on the keyboard; it used to be accepted. */
    @Test
    fun `a number too large for Int is rejected`() {
        assertFalse(isUsableHouseNumber("99999999999999999999"))
    }

    /** Why the validation exists at all: the matcher itself lets unreadable input pass. */
    @Test
    fun `the rule matcher accepts an unreadable number on a street with ranges`() {
        val street = StreetDto(
            name = "Budowlanych",
            rules = listOf(NumberRuleDto(from = 1, to = 20, parity = null)),
        )
        assertFalse(street.matches("50"))
        assertTrue(street.matches("abc"))
    }

    @Test
    fun `number rules respect range and parity`() {
        val odd = StreetDto("Wierzbowa", listOf(NumberRuleDto(from = 27, to = null, parity = "ODD")))
        assertTrue(odd.matches("27"))
        assertTrue(odd.matches("41"))
        assertFalse(odd.matches("28"))
        assertFalse(odd.matches("25"))
    }

    @Test
    fun `a street without rules covers every number`() {
        val all = StreetDto("Bukowa", emptyList())
        assertTrue(all.matches("1"))
        assertTrue(all.matches("999"))
    }
}
