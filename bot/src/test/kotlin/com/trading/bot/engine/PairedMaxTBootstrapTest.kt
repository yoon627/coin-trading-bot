package com.trading.bot.engine

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PairedMaxTBootstrapTest {

    private fun frame(vararg sizes: Int) = PairedMaxTBootstrap.Frame(
        sizes.mapIndexed { w, n -> (0 until n).map { "w$w/d$it" } },
    )

    @Test
    fun `draw keeps window lengths, stays inside window and uses contiguous blocks`() {
        val f = frame(12, 3, 7)
        val rng = Random(1)
        repeat(200) {
            val d = f.draw(rng, block = 5)
            assertEquals(f.size, d.size)
            // 창 0: 0..11, 창 1: 12..14(블록보다 작아 통째), 창 2: 15..21
            assertTrue(d.slice(0 until 12).all { it in 0 until 12 })
            assertEquals(listOf(12, 13, 14), d.slice(12 until 15))
            assertTrue(d.slice(15 until 22).all { it in 15 until 22 })
            // 창 0 의 블록 3개(⌈12/5⌉) 중 잘리지 않은 앞 두 블록은 연속 인덱스다.
            for (start in listOf(0, 5)) {
                val blk = d.slice(start until start + 5)
                assertEquals((blk.first() until blk.first() + 5).toList(), blk, "블록이 연속이 아니다: $blk")
            }
        }
    }

    @Test
    fun `maxT passes a clear signal and rejects pure noise in the same family`() {
        val f = frame(150, 150)
        val rng = Random(7)
        val noise = DoubleArray(f.size) { rng.nextDouble(-1.0, 1.0) }
        val signal = DoubleArray(f.size) { noise[it] + 0.3 }
        val sums = PairedMaxTBootstrap.resampleSums(f, listOf(signal, noise), resamples = 4_000)
        val fam = PairedMaxTBootstrap.maxT(doubleArrayOf(signal.sum(), noise.sum()), sums)
        assertTrue(fam.cells[0].pass, "신호 셀이 통과해야 한다: ${fam.cells[0]}")
        assertFalse(fam.cells[1].pass, "잡음 셀이 통과하면 안 된다: ${fam.cells[1]}")
        assertTrue(fam.cells[0].lowerBound > 0 && fam.cells[1].lowerBound <= 0)
        assertTrue(fam.cells[1].marginalP > 0.05)
    }

    @Test
    fun `same seed gives identical resample sums so families share draws`() {
        val f = frame(40, 40)
        val a = DoubleArray(f.size) { it.toDouble() }
        val once = PairedMaxTBootstrap.resampleSums(f, listOf(a), resamples = 50)[0]
        val twice = PairedMaxTBootstrap.resampleSums(f, listOf(a, a), resamples = 50)
        assertTrue(once.contentEquals(twice[0]) && once.contentEquals(twice[1]))
    }

    @Test
    fun `spearman handles ties and monotone relations`() {
        assertEquals(1.0, PairedMaxTBootstrap.spearman(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(10.0, 20.0, 30.0)), 1e-12)
        assertEquals(-1.0, PairedMaxTBootstrap.spearman(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(3.0, 2.0, 1.0)), 1e-12)
        // 동률 평균 순위: x=(1,1,2) → (1.5,1.5,3), y=(1,2,3) → ρ = 0.866
        assertEquals(0.8660, PairedMaxTBootstrap.spearman(doubleArrayOf(1.0, 1.0, 2.0), doubleArrayOf(1.0, 2.0, 3.0)), 1e-3)
    }
}
