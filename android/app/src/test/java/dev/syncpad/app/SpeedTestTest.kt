package dev.syncpad.app

import dev.syncpad.app.net.SpeedTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Uses the real internet, so it runs only when asked: -Psyncpad.net */
class SpeedTestTest {
    @Test fun measures() {
        assumeTrue(System.getProperty("syncpad.net") == "true")
        val t = SpeedTest()
        t.run()
        val s = t.state
        println("speed: $s")
        assertTrue(s.error, s.phase == "done")
        assertNotNull(s.ping); assertNotNull(s.down); assertNotNull(s.up)
        assertTrue(s.down!! > 0.5 && s.up!! > 0.1)
    }
}
