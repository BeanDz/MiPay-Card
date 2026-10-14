package dev.doji.mipaycard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardArrivalTest {
    @Test
    fun reportsExactArrivalAfterMovingUpward() {
        val arrival = CardArrival()

        assertFalse(arrival.update(100, 400))
        assertFalse(arrival.update(100, 250))
        assertFalse(arrival.update(100, 101))
        assertTrue(arrival.update(100, 100))
        assertFalse(arrival.update(100, 99))
        assertFalse(arrival.update(100, 102))
    }

    @Test
    fun reportsOnlyFirstCrossingDespiteRebound() {
        val arrival = CardArrival()

        assertFalse(arrival.update(100, 400))
        assertFalse(arrival.update(100, 101))
        assertTrue(arrival.update(100, 98))
        assertFalse(arrival.update(100, 103))
        assertFalse(arrival.update(100, 97))
        assertFalse(arrival.update(100, 100))
    }

    @Test
    fun reportsArrivalWhenMovingDownward() {
        val arrival = CardArrival()

        assertFalse(arrival.update(400, 100))
        assertFalse(arrival.update(400, 390))
        assertTrue(arrival.update(400, 401))
        assertFalse(arrival.update(400, 399))
    }

    @Test
    fun retargetingDiscardsPreviousDirectionAndArrival() {
        val arrival = CardArrival()

        assertFalse(arrival.update(100, 400))
        assertFalse(arrival.update(100, 200))
        assertFalse(arrival.update(300, 200))
        assertFalse(arrival.update(300, 290))
        assertTrue(arrival.update(300, 305))

        assertFalse(arrival.update(100, 305))
        assertTrue(arrival.update(100, 99))
    }

    @Test
    fun reportsInitiallyStationaryTargetOnce() {
        val arrival = CardArrival()

        assertTrue(arrival.update(150, 150))
        assertFalse(arrival.update(150, 150))
    }
}
