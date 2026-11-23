package com.trading.bot.config

import com.trading.bot.ci.RepoFiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/** DB 를 손으로 되돌리기 전에 서버 디스크에 덤프한다. S3 백업(backup.sh) 없이도 되돌리기 절차가 시작되게 한다. */
@DisabledOnOs(OS.WINDOWS, disabledReason = "bash 스크립트 — 서버(Linux)에서 돈다")
class DumpLocalScriptTest {

    @TempDir
    lateinit var dir: File

    private class Run(val exit: Int, val output: String)

    private val backups get() = File(dir, "backups")

    /** pg_dump 17.x 의 실제 끝 — 완료 표시 뒤에 `\unrestrict` 줄이 더 붙는다. */
    private val pg17Tail = "--\\n-- PostgreSQL database dump complete\\n--\\n\\n\\\\unrestrict abc123\\n\\n"

    /** 배포가 서버에 올리는 그 파일을 /opt/app 대신 임시 디렉터리를 가리켜 가짜 docker 로 돌린다. 실행 위치는 일부러 다른 곳이다. */
    private fun run(vararg args: String, dumpTail: String = pg17Tail): Run {
        val bin = File(dir, "bin").apply { mkdirs() }
        File(bin, "docker").apply {
            writeText("#!/usr/bin/env bash\ncase \"\$*\" in\n  *pg_dump*) printf 'CREATE TABLE t();\\n$dumpTail' ;;\nesac\n")
            setExecutable(true)
        }
        val out = File(dir, "out.txt")
        val process = ProcessBuilder(listOf("bash", RepoFiles.file("deploy/vultr/dump_local.sh").path) + args)
            .directory(bin)
            .redirectErrorStream(true)
            .redirectOutput(out)
            .apply {
                environment()["PATH"] = "${bin.path}:${environment()["PATH"]}"
                environment()["DUMP_LOCAL_APP_DIR"] = dir.path
            }
            .start()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "dump_local.sh 가 끝나지 않았다" }
        return Run(process.exitValue(), out.readText())
    }

    private fun mode(file: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))

    @Test
    fun `완전한 덤프만 root 전용으로 남기고 같은 이름표의 덤프만 최근 5개로 줄인다`() {
        backups.mkdirs()
        (1..5).forEach { File(backups, "manual-before-V40-down-2026010${it}T000000Z.sql.gz").apply { writeText("old"); setLastModified(it * 1000L) } }
        File(backups, "manual-before-V39-down-20260101T000000Z.sql.gz").apply { writeText("other"); setLastModified(1L) }
        File(backups, "pre-deploy-keep.sql.gz").apply { writeText("deploy"); setLastModified(1L) }

        val result = run("before-V40-down")

        assertThat(result.exit).describedAs(result.output).isEqualTo(0)
        val made = backups.listFiles()!!.single { it.name.startsWith("manual-before-V40-down-") && it.readText() != "old" }
        assertThat(result.output).contains(made.name)
        assertThat(mode(made)).isEqualTo("rw-------")
        assertThat(mode(backups)).isEqualTo("rwx------")
        val kept = backups.listFiles()!!.map { it.name }
        assertThat(kept.filter { it.startsWith("manual-before-V40-down-") }).hasSize(5)
            .doesNotContain("manual-before-V40-down-20260101T000000Z.sql.gz")
        assertThat(kept).contains("manual-before-V39-down-20260101T000000Z.sql.gz", "pre-deploy-keep.sql.gz").noneMatch { it.endsWith(".partial") }
    }

    @Test
    fun `끝 표시가 없는 덤프는 지우고 실패해 뒤의 되돌리기가 돌지 않는다`() {
        val result = run("before-V40-down", dumpTail = "-- cut off\\n")

        assertThat(result.exit).isEqualTo(1)
        assertThat(result.output).contains("덤프 실패")
        assertThat(backups.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `이름표가 없거나 경로 문자가 섞이면 덤프하지 않는다`() {
        assertThat(run().exit).isEqualTo(2)
        assertThat(run("../x").exit).isEqualTo(2)
        assertThat(File(dir, "backups").exists()).isFalse()
    }
}
