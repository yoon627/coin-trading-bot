package com.trading.bot.ci

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** KIS 코드는 kis 패키지 안에만 두고 엔진·Upbit 경로는 포트로만 닿는다. */
class KisImportBoundaryTest {

    private fun mainSources(): File = RepoFiles.file("bot/src/main/kotlin")

    private fun kotlinFiles(dir: File): List<File> = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun File.mentions(pkg: Regex): Boolean = useLines { lines -> lines.any { pkg.containsMatchIn(it) } }

    @Test
    fun `no main source outside the kis package refers to it`() {
        val root = mainSources()
        val kisDir = File(root, "com/trading/bot/kis").canonicalFile
        val files = kotlinFiles(root)
        assertTrue(files.size > 20, "main 소스를 거의 못 찾았다: ${files.size}개 (${root.absolutePath})")

        val offenders = files
            .filterNot { it.canonicalPath.startsWith(kisDir.path + File.separator) }
            .filter { it.mentions(Regex("""\bcom\.trading\.bot\.kis\b""")) }
            .map { it.relativeTo(root).path }

        assertEquals(emptyList<String>(), offenders)
    }

    /** 포트 패키지가 Upbit 어댑터에 기대면 다른 거래소 구현이 전이적으로 Upbit 에 묶인다. */
    @Test
    fun `the exchange port package does not depend on the upbit client package`() {
        val exchangeDir = File(mainSources(), "com/trading/bot/exchange")
        val files = kotlinFiles(exchangeDir)
        assertTrue(files.isNotEmpty(), "exchange 패키지를 못 찾았다: ${exchangeDir.absolutePath}")

        val offenders = files
            .filter { it.mentions(Regex("""\bcom\.trading\.bot\.client\b""")) }
            .map { it.name }

        assertEquals(emptyList<String>(), offenders)
    }
}
