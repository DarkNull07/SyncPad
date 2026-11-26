package dev.syncpad.app

import dev.syncpad.app.net.Updates
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test fun comparesVersions() {
        assertTrue(Updates.newer("v0.3.0", "0.2.1"))
        assertTrue(Updates.newer("0.10.0", "0.9.9"))
        assertTrue(Updates.newer("1.0", "0.9.9"))
        assertFalse(Updates.newer("0.2.1", "0.2.1"))
        assertFalse(Updates.newer("0.2.0", "0.2.1"))
        assertFalse(Updates.newer("nightly", "0.2.1"))
        assertFalse(Updates.newer("0.3.0", ""))
    }
}
