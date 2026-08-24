package com.metrolist.innertubex.cipher

import com.metrolist.innertubex.InnerTubeLogger
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class EjsChallengeSolverTest {
    @Test
    fun oversizedChallengesAreRejectedBeforeInitializingQuickJs() =
        runBlocking {
            val solver = EjsChallengeSolver(QuickJsEngine(), InnerTubeLogger.NONE)

            val result =
                solver.solve(
                    playerUrl = "https://www.youtube.com/s/player/12345678/player.js",
                    fullPlayerJs = "var player = true;",
                    requestOrder = listOf("sig" to listOf("x".repeat(64 * 1024 + 1))),
                )

            assertTrue(result.sigByChallenge.isEmpty())
            assertTrue(result.nByChallenge.isEmpty())
            assertTrue(result.preprocessedPlayer == null)
        }
}
