package com.bitnesttechs.hms.patient.core.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A notification tap on a cold start used to navigate before the NavHost had
 * set its graph, which throws. The tap now waits, pending, until the graph
 * is there.
 */
class PushNavigationTest {

    @Test
    fun `a tap waits while the graph is not set, then is consumed exactly once`() {
        PushNavigation.consume()
        PushNavigation.offer(PushTarget("6f1c0f3e-0a11-4c22-9f3e-1a2b3c4d5e6f"))

        assertNull(PushNavigation.consumeWhenReady(graphReady = false))
        assertEquals(PushTarget("6f1c0f3e-0a11-4c22-9f3e-1a2b3c4d5e6f"), PushNavigation.pending.value)

        assertEquals(PushTarget("6f1c0f3e-0a11-4c22-9f3e-1a2b3c4d5e6f"), PushNavigation.consumeWhenReady(graphReady = true))
        assertNull(PushNavigation.consumeWhenReady(graphReady = true))
    }
}
