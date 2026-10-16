package thor.companion.strip

import kotlin.test.Test
import kotlin.test.assertEquals

/** The short forms, as the addon's ns.Short makes them (taken from it). */
class CompactTest {
    @Test
    fun position() {
        assertEquals("TP2|21|0.5300|0.4430|6.2", Compact.expand("P00LXwuSMX/"))
        assertEquals("TP2|1429|0.0000|1.0000|", Compact.expand("P0ML000///"))
    }

    @Test
    fun status() {
        assertEquals(
            "TS1|Zyphos|8|1234|21|0.5300|0.4430|6.2|1760000000|51234|0.24.6|5:111,6:222,7:333,8:444,10:555,11:0",
            Compact.expand("SZyphos|8|JI|00LXwuSMX/|1evtW0|CWY|0.24.6|01l03U05D06y08h000"),
        )
        assertEquals(
            "TS1|Tester|10|1234|1429|0.5000|0.5000||1000|0||5:0,6:0,7:0,8:0,10:0,11:0",
            Compact.expand("STester|A|JI|0MLW00W00|Fe|000||000000000000000000"),
        )
    }

    @Test
    fun otherMessagesStayAsTheyAre() {
        for (m in listOf("TB1|3/98|1:2", "TH1|x", "Sbroken", "Pzz")) assertEquals(m, Compact.expand(m))
    }
}
