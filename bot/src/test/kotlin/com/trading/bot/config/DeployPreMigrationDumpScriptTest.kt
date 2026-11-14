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

/** 스키마가 바뀔 수 있는 배포는 새 앱이 뜨기 전에 서버에서 DB 를 덤프하고, 덤프가 안 되면 멈춘다 (#323). */
@DisabledOnOs(OS.WINDOWS, disabledReason = "bash 스크립트 — 배포는 CI(Linux)·macOS 에서 돈다")
class DeployPreMigrationDumpScriptTest {

    @TempDir
    lateinit var dir: File

    private class Run(val exit: Int, val output: String)

    private val sha = "0123456789abcdef0123456789abcdef01234567"
    private val backups get() = File(dir, "backups")

    /** 원격 배포 스크립트의 `pre_migration_dump` 를 떼어 가짜 docker 로 돌린다. */
    private val calls get() = File(dir, "docker.calls").takeIf { it.exists() }?.readLines().orEmpty()

    /** pg_dump 17.x 의 실제 끝 — 완료 표시 뒤에 `\unrestrict` 줄이 더 붙는다. */
    private val pg17Tail = "--\\n-- PostgreSQL database dump complete\\n--\\n\\n\\\\unrestrict abc123\\n\\n"

    private fun dump(dumpTail: String = pg17Tail, upExit: Int = 0): Run {
        val text = RepoFiles.file("deploy/vultr/deploy.sh").readText()
        val fn = "pre_migration_dump() {" + text.substringAfter("pre_migration_dump() {").substringBefore("\n}\n") + "\n}\n"
        val bin = File(dir, "bin").apply { mkdirs() }
        File(bin, "docker").apply {
            writeText(
                "#!/usr/bin/env bash\n" +
                    "echo \"\$*\" >> '${File(dir, "docker.calls").path}'\n" +
                    "case \"\$*\" in\n" +
                    "  *'up -d --wait postgres'*) exit $upExit ;;\n" +
                    "  *pg_dump*) printf 'CREATE TABLE t();\\n$dumpTail' ;;\n" +
                    "esac\n",
            )
            setExecutable(true)
        }
        val out = File(dir, "out.txt")
        val process = ProcessBuilder("bash", "-c", fn + "pre_migration_dump")
            .directory(dir)
            .redirectErrorStream(true)
            .redirectOutput(out)
            .apply {
                environment()["PATH"] = "${bin.path}:${environment()["PATH"]}"
                environment()["TARGET_SHA"] = sha
            }
            .start()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "pre_migration_dump 가 끝나지 않았다" }
        return Run(process.exitValue(), out.readText())
    }

    private fun mode(file: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))

    @Test
    fun `완전한 덤프만 root 전용으로 남기고 최근 5개만 보존한다`() {
        backups.mkdirs()
        (1..5).forEach { File(backups, "pre-deploy-old-2026010${it}T000000Z.sql.gz").apply { writeText("old"); setLastModified(it * 1000L) } }

        val result = dump()

        assertThat(result.exit).describedAs(result.output).isEqualTo(0)
        val kept = backups.listFiles()!!.map { it.name }
        assertThat(kept).hasSize(5).noneMatch { it.endsWith(".partial") }
        val made = backups.listFiles()!!.single { it.name.startsWith("pre-deploy-${sha.take(12)}-") }
        assertThat(mode(made)).isEqualTo("rw-------")
        assertThat(mode(backups)).isEqualTo("rwx------")
        assertThat(kept).doesNotContain("pre-deploy-old-20260101T000000Z.sql.gz")
    }

    @Test
    fun `끝 표시가 없는 덤프는 지우고 실패한다`() {
        val result = dump(dumpTail = "-- cut off\\n")

        assertThat(result.exit).isEqualTo(1)
        assertThat(result.output).contains("마이그레이션 전 덤프 실패")
        assertThat(backups.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `멈춘 postgres 는 먼저 띄우고 띄우지 못하면 덤프 없이 멈춘다`() {
        val started = dump()
        assertThat(started.exit).describedAs(started.output).isEqualTo(0)
        assertThat(calls.first()).contains("up -d --wait postgres")

        File(dir, "docker.calls").delete()
        val failed = dump(upExit = 1)
        assertThat(failed.exit).isEqualTo(1)
        assertThat(failed.output).contains("postgres 를 띄우지 못했다")
        assertThat(calls).noneMatch { it.contains("pg_dump") }
    }

    @Test
    fun `덤프는 이미지를 받은 뒤 새 앱을 띄우기 바로 앞에서 실패하면 멈추게 부른다`() {
        val text = RepoFiles.file("deploy/vultr/deploy.sh").readText()
        val call = text.indexOf("    pre_migration_dump || exit 1\n")

        assertThat(call).isGreaterThan(text.indexOf("\ndocker compose pull\n")).isLessThan(text.indexOf("\ndocker compose up -d --remove-orphans\n"))
    }
}
