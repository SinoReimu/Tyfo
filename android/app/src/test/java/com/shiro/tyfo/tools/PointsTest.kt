package com.shiro.tyfo.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class PointsTest {

    @Test
    fun `device pixels convert to 0-1000 and back`() {
        assertEquals(209 to 659, Points.fromPixels(226, 1542, 1080, 2340))
        assertEquals(1000 to 1000, Points.fromPixels(1080, 2340, 1080, 2340))
        assertEquals(225 to 1542, Points.toPixels(209, 659, 1080, 2340))
    }

    @Test
    fun `an unknown screen size leaves the numbers alone`() {
        assertEquals(540 to 585, Points.fromPixels(540, 585, 0, 0))
    }
}
