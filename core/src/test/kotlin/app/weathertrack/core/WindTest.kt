package app.weathertrack.core

import app.weathertrack.core.wind.Wind
import org.junit.Assert.assertEquals
import org.junit.Test

class WindTest {
    @Test fun compassAndArrow() {
        assertEquals("N", Wind.compass(0.0))
        assertEquals("N", Wind.compass(355.0))
        assertEquals("SW", Wind.compass(225.0))
        assertEquals("WSW", Wind.compass(250.0))
        assertEquals("NW", Wind.compass8(310.0))
        // From the southwest, blowing towards the northeast.
        assertEquals("↗", Wind.arrow(225.0))
        assertEquals("↓", Wind.arrow(0.0))
        assertEquals("SW 18 km/h, gusts 35", Wind.summary(18.2, 34.6, 225.0))
        assertEquals(null, Wind.compass(null))
    }
}
