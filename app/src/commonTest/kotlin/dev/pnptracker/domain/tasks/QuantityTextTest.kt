package dev.pnptracker.domain.tasks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How every field that asks for a number of pieces reads what was typed.
 *
 * One account of it, because there used to be three: a shortage reported, a
 * shortage made good and a pipeline step each had a limit and a parser of their
 * own, and the limits disagreed with each other and with what a count can be.
 */
class QuantityTextTest {
    @Test
    fun `the largest amount there is is an amount`() {
        // Ten digits. A field that stopped at nine could not hold this, and a
        // task is allowed to need exactly this many pieces.
        assertEquals(Int.MAX_VALUE, countedQuantityOf("2147483647"))
        assertEquals(10, "2147483647".length)
        assertFalse(isUnusableQuantity("2147483647"))
    }

    @Test
    fun `one more than the largest amount is not an amount`() {
        assertNull(countedQuantityOf("2147483648"))
        assertTrue(isUnusableQuantity("2147483648"), "a number that will not fit did not say so")
    }

    @Test
    fun `hundreds of digits come back as no amount rather than as a failure`() {
        // A user can hold a key down. Parsing this must answer, not throw.
        val absurd = "9".repeat(400)

        assertNull(countedQuantityOf(absurd))
        assertTrue(isUnusableQuantity(absurd))
        assertEquals(absurd, quantityDigitsOf(absurd), "what was typed was cut short")
    }

    @Test
    fun `zeros written in front change nothing`() {
        assertEquals(15, countedQuantityOf("0015"))
        assertEquals(0, countedQuantityOf("0000"))
        assertEquals(0, countedQuantityOf("0"))
        assertFalse(isUnusableQuantity("0015"))
    }

    @Test
    fun `nothing typed is not an amount, and does not complain about itself`() {
        assertNull(countedQuantityOf(""))
        // An empty box is where every form starts. It is not an amount, but it
        // is not a mistake either — saying so while the user has yet to type
        // would answer them before they had asked.
        assertFalse(isUnusableQuantity(""))
    }

    @Test
    fun `only digits are an amount`() {
        listOf("abc", "12a", " 12", "12 ", "1,5", "1.5").forEach {
            assertNull(countedQuantityOf(it), "$it was read as an amount")
        }
    }

    @Test
    fun `a minus sign is not a number of pieces anybody could have meant`() {
        // Kept out at the keyboard rather than read and then rejected: reading
        // one would turn a slip into a movement backwards.
        assertEquals("5", quantityDigitsOf("-5"))
        assertNull(countedQuantityOf("-5"))
    }

    @Test
    fun `typing keeps every digit and nothing else`() {
        assertEquals("12345678901234", quantityDigitsOf("12345678901234"))
        assertEquals("125", quantityDigitsOf("1a2 b5"))
        assertEquals("", quantityDigitsOf("abc"))
    }

    @Test
    fun `zero is an amount to read even where it is not one to send`() {
        // The parser's job is to say what was typed. Whether nothing is a
        // movement worth writing is the form's question, and it answers it
        // where the user can see the answer.
        assertEquals(0, countedQuantityOf("0"))
        assertFalse(isUnusableQuantity("0"))
    }
}
