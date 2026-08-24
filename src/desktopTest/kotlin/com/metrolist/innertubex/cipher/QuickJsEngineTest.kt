package com.metrolist.innertubex.cipher

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuickJsEngineTest {
    @Test
    fun functionResultsAreBoundedBeforeLeavingQuickJs() =
        runBlocking {
            val engine = QuickJsEngine()
            try {
                engine.initialize()
                engine.execute("function oversized() { return 'x'.repeat(300000); }")

                assertNull(engine.callFunction("oversized", "input"))
                assertEquals("", engine.evaluate("'x'.repeat(300000)", maxResultLength = 1024))
            } finally {
                engine.dispose()
            }
        }
}
