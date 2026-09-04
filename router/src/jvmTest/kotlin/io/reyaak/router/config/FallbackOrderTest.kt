package io.reyaak.router.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reordering the manual chain by rank.
 *
 * The interesting part is not the happy path: `removeAt` shifts every index above
 * it, so a naive implementation puts the row one place off whenever it moves
 * downwards.
 */
class FallbackOrderTest {

    private val list = listOf("a", "b", "c", "d")

    @Test
    fun `moves the last entry to the front`() {
        assertEquals(listOf("d", "a", "b", "c"), list.movedTo("d", 1))
    }

    @Test
    fun `moves the first entry to the back`() {
        assertEquals(listOf("b", "c", "d", "a"), list.movedTo("a", 4))
    }

    @Test
    fun `moving down lands on the requested rank, not one short of it`() {
        // "a" leaves index 0 and every later entry shifts left, so asking for
        // rank 3 has to still mean rank 3 in the finished list.
        assertEquals(listOf("b", "c", "a", "d"), list.movedTo("a", 3))
    }

    @Test
    fun `a rank past the end lands at the end`() {
        assertEquals(listOf("b", "c", "d", "a"), list.movedTo("a", 999))
    }

    @Test
    fun `a rank below one lands at the front`() {
        assertEquals(listOf("c", "a", "b", "d"), list.movedTo("c", 0))
        assertEquals(listOf("c", "a", "b", "d"), list.movedTo("c", -5))
    }

    @Test
    fun `the same rank is a no-op`() {
        assertEquals(list, list.movedTo("b", 2))
    }

    @Test
    fun `an unknown key leaves the order alone`() {
        assertEquals(list, list.movedTo("zzz", 1))
    }

    @Test
    fun `an empty order is left alone`() {
        val empty: List<String> = emptyList()
        assertEquals(empty, empty.movedTo("a", 1))
    }

    /** The real case: rank 100 to the front of a hundred-model chain, one edit. */
    @Test
    fun `moves a deep entry to the front in one edit`() {
        val long = (1..100).map { "m$it" }
        val moved = long.movedTo("m100", 1)
        assertEquals(listOf("m100", "m1", "m2"), moved.take(3))
        assertEquals("m99", moved.last())
        // Nothing is lost or duplicated on the way.
        assertEquals(long.sorted(), moved.sorted())
    }
}
