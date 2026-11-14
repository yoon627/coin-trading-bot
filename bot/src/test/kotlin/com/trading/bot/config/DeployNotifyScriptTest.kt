package com.trading.bot.config

import com.trading.bot.ci.RepoFiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/**
 * 배포 결과를 Discord 로 알린다. 배포 로그는 공개 repo 의 CI 로그라 webhook URL 이 인자·출력에 나오면 안 되고,
 * 알림이 실패해도 배포 종료 코드(0 성공, 2 자동 롤백, 1 그 밖)는 그대로여야 한다.
 * 가짜 curl 은 받은 인자와 표준입력을 기록할 뿐이라 진짜 curl 의 오류 문구가 URL 을 담는 경우는 재현하지 않는다
 * (스크립트가 curl 의 stderr 를 버리는 것으로 막는다).
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "bash 스크립트 — 배포는 CI(Linux)·macOS 에서 돈다")
class DeployNotifyScriptTest {

    @TempDir
    lateinit var dir: File

    private val bin by lazy { File(dir, "bin").apply { mkdirs() } }
    private val curlArgs by lazy { File(dir, "curl.args") }
    private val curlStdin by lazy { File(dir, "curl.stdin") }
    private val sshRemote by lazy { File(dir, "ssh.remote") }
    private val sshArgs by lazy { File(dir, "ssh.args") }
    private val scpCalls by lazy { File(dir, "scp.calls") }
    private val scpArgs by lazy { File(dir, "scp.args") }

    private class Run(val exit: Int, val output: String)

    @Test
    fun `배포 결과는 배포 webhook 으로 보내고 URL 은 표준입력으로만 넘긴다`() {
        for (level in listOf("success", "rollback", "failure")) {
            val result = notify(level, "배포 $level", general = GENERAL, error = ERROR, deploy = DEPLOY)

            assertThat(result.exit).isEqualTo(0)
            assertThat(curlStdin.readText()).describedAs(level).isEqualTo("url = \"$DEPLOY\"\n")
            assertThat(curlArgs.readText()).doesNotContain("example.test")
            assertThat(result.output).contains("HTTP 204").doesNotContain("example.test")
        }
    }

    // 거래 webhook(채널)에는 체결 알림만 남긴다.
    @Test
    fun `배포 webhook 이 없으면 롤백과 실패만 에러 webhook 으로 가고 거래 webhook 으로는 가지 않는다`() {
        notify("failure", "배포 실패", general = GENERAL, error = ERROR, deploy = "")
        assertThat(curlStdin.readText()).isEqualTo("url = \"$ERROR\"\n")

        for ((level, error) in listOf("success" to ERROR, "rollback" to "", "failure" to "")) {
            val result = notify(level, "배포", general = GENERAL, error = error, deploy = "")
            assertThat(curlArgs).describedAs(level).doesNotExist()
            assertThat(result.output).describedAs(level).contains("건너뜀")
        }
    }

    @Test
    fun `curl 이 실패해도 0 으로 끝나고 실패를 남긴다`() {
        val result = notify("success", "배포 성공", general = GENERAL, error = "", deploy = DEPLOY, curlExit = 7)

        assertThat(result.exit).isEqualTo(0)
        assertThat(result.output).contains("실패").contains("7")
    }

    @Test
    fun `메시지의 따옴표와 줄바꿈은 JSON 으로 이스케이프하고 멘션은 막는다`() {
        notify("success", "a \"b\"\nc\\d", general = GENERAL, error = "", deploy = DEPLOY)

        assertThat(curlArgs.readLines()).contains("""{"content":"a \"b\"\nc\\d","allowed_mentions":{"parse":[]}}""")
    }

    @Test
    fun `deploy 는 원격 결과마다 알맞은 webhook 으로 알리고 종료 코드를 지킨다`() {
        val repo = deployRepo()
        val cases = mapOf(
            0 to Triple(0, DEPLOY, listOf("성공")),
            2 to Triple(2, DEPLOY, listOf("자동 롤백")),
            1 to Triple(1, DEPLOY, listOf("수동 개입")),
            4 to Triple(1, DEPLOY, listOf("Caddyfile")),
            3 to Triple(1, DEPLOY, listOf("앱 중단")),
            // ssh 자체의 실패(연결 끊김). 원격 배포는 서버에서 계속 돌 수 있어 결과는 서버 로그로 본다.
            255 to Triple(1, DEPLOY, listOf("결과 불명", REMOTE_LOG)),
        )

        for ((remoteRc, expected) in cases) {
            curlStdin.delete()
            val result = deploy(repo, remoteRc)

            assertThat(result.exit).describedAs("원격 rc=$remoteRc\n${result.output}").isEqualTo(expected.first)
            assertThat(curlStdin.readText()).describedAs("원격 rc=$remoteRc").contains(expected.second)
            assertThat(curlArgs.readText()).describedAs("원격 rc=$remoteRc").contains(*expected.third.toTypedArray())
            assertThat(result.output).describedAs("원격 rc=$remoteRc").doesNotContain("example.test")
        }
    }

    /** 원격 4 는 앱이 새 SHA 로 정상이라는 뜻이라 롤백 기준은 그 SHA 로 옮기고, 배포는 실패로 남긴다. */
    @Test
    fun `원격 4 는 롤백 기준을 대상 커밋으로 갱신하고 실패로 끝난다`() {
        val repo = deployRepo()
        val target = git(repo, "rev-parse", "HEAD").trim()

        val result = deploy(repo, remoteRc = 4)

        assertThat(result.exit).describedAs(result.output).isEqualTo(1)
        assertThat(File(repo, "deploy/vultr/.state").readText()).contains("LAST_GOOD_SHA=$target")
    }

    /** 업로드가 중간에 끊겨도 빈 ./caddy 를 가리키는 compose 가 남지 않게 Caddyfile 을 먼저 올린다. */
    @Test
    fun `Caddyfile 은 compose 가 마운트하는 디렉터리로 compose 파일보다 먼저 올린다`() {
        val repo = deployRepo()
        val compose: Map<*, *> = Yaml().load(RepoFiles.file("deploy/vultr/docker-compose.prod.yml").readText())
        val caddyVolumes = ((compose["services"] as Map<*, *>)["caddy"] as Map<*, *>)["volumes"] as List<*>

        deploy(repo, remoteRc = 0)

        // 파일 하나를 마운트하면 올린 Caddyfile 이 컨테이너에 닿지 않거나 바꿔치기한 뒤 옛 inode 를 읽는다.
        assertThat(caddyVolumes).contains("./caddy:/etc/caddy:ro")
        val uploads = scpCalls.readLines().map { it.substringAfterLast(':') }
        assertThat(uploads).containsSubsequence("/opt/app/caddy/Caddyfile", "/opt/app/docker-compose.yml")
        assertThat(uploads).containsSubsequence("/opt/app/apply_caddyfile.sh", "/opt/app/docker-compose.yml")
        assertThat(sshRemote.readText()).contains("mkdir -p /opt/app/caddy")
    }

    /** 접속 시한만으로는 조용히 끊긴 연결에서 job 시한(20분)까지 알림 없이 기다린다. 긴 원격 배포(`bash -s`)가 빠지면 안 된다. */
    @Test
    fun `배포의 모든 ssh 와 scp 는 접속·무응답 시한을 건다`() {
        val repo = deployRepo()

        val result = deploy(repo, remoteRc = 0)

        assertThat(result.exit).describedAs(result.output).isEqualTo(0)
        val ssh = sshArgs.readLines()
        val scp = scpArgs.readLines()
        assertThat(ssh).anyMatch { it.contains("bash -s") }
        assertThat(scp).hasSize(5)
        assertThat(ssh + scp).describedAs((ssh + scp).joinToString("\n")).allSatisfy {
            assertThat(it).contains("-o ConnectTimeout=10", "-o ServerAliveInterval=15", "-o ServerAliveCountMax=8")
        }
    }

    /** 원격 배포 스크립트라 실행하지 않고 본문을 본다. exec 가 표준입력을 붙잡으면 bash -s 로 들어온 남은 스크립트를 먹는다. */
    @Test
    fun `원격 스크립트는 앱이 healthy 이고 롤백 기준을 쓴 뒤에만 Caddyfile 을 적용하고 표준입력을 넘기지 않는다`() {
        val remote = remoteScript()
        val healthy = remote.substringAfter("if health_ok; then").substringBefore("echo \"ERROR: 180s")

        val apply = healthy.indexOf("bash ./apply_caddyfile.sh </dev/null || caddy_rc=\$?")
        val verdict = healthy.indexOf("caddy_verdict \"\$caddy_rc\" \"\$tls_ok\" || verdict=\$?")
        assertThat(healthy.indexOf(".last-good-sha")).isGreaterThanOrEqualTo(0).isLessThan(apply)
        // 판정이 HTTPS 확인보다 앞서면 바뀐 Caddyfile 을 적용한 배포가 모두 4 가 된다.
        assertThat(verdict).isGreaterThan(healthy.indexOf("tls_ok=true"))
        assertThat(healthy).contains("exit \"\$verdict\"")
        assertThat(remote.substringAfter("echo \"ERROR: 180s")).doesNotContain("apply_caddyfile.sh")
    }

    /**
     * 성공 배포의 종료 코드: 바뀐 Caddyfile 을 적용했으면 HTTPS e2e 까지 확인해야 0 이고, 적용 실패는 앱과 별개로 4 다.
     * 적용했는데 HTTPS 를 확인하지 못하면 적용 기록을 지워, 다시 돌린 배포가 같은 Caddyfile 을 다시 적용하고 다시 확인한다.
     */
    @Test
    fun `Caddyfile 적용 결과와 HTTPS 확인으로 성공 배포의 종료 코드와 적용 기록을 정한다`() {
        val verdict = "caddy_verdict() {" + remoteScript().substringAfter("caddy_verdict() {").substringBefore("\n}") + "\n}\n"
        val marker = File(dir, ".caddyfile-applied")
        // 입력 → 종료 코드, 판정 뒤 적용 기록이 남는지
        val cases = mapOf(
            ("0" to "false") to (0 to true), ("0" to "true") to (0 to true), ("10" to "true") to (0 to true),
            ("10" to "false") to (4 to false), ("1" to "true") to (4 to true), ("1" to "false") to (4 to true),
        )

        for ((input, expected) in cases) {
            marker.writeText("hash\n")
            val result = exec(
                listOf("bash", "-c", verdict + "caddy_verdict \"\$1\" \"\$2\"", "verdict", input.first, input.second),
                mapOf("TARGET_SHA" to "abc"),
            )
            val label = "caddy_rc=${input.first} tls_ok=${input.second}\n${result.output}"
            assertThat(result.exit).describedAs(label).isEqualTo(expected.first)
            assertThat(marker.exists()).describedAs(label).isEqualTo(expected.second)
        }
    }

    @Test
    fun `알림 스크립트가 없어도 deploy 의 종료 코드는 그대로다`() {
        val repo = deployRepo()
        File(repo, "deploy/vultr/notify_deploy.sh").delete()

        val success = deploy(repo, remoteRc = 0)
        val rollback = deploy(repo, remoteRc = 2)

        assertThat(success.exit).describedAs(success.output).isEqualTo(0)
        assertThat(rollback.exit).describedAs(rollback.output).isEqualTo(2)
    }

    /** 원격 배포 스크립트라 실행하지 않고 본문을 본다. 시도 횟수만 세면 exec·curl 이 걸릴 때 job 시한까지 간다. */
    @Test
    fun `health 대기는 180초 마감으로 묶이고 exec 와 curl 에 시한이 있다`() {
        val healthOk = remoteScript().substringAfter("health_ok() {").substringBefore("\n}")

        assertThat(healthOk).contains("deadline=\$((SECONDS + 180))", "timeout -k ", "curl -fsS --max-time ")
    }

    /** 원격 스크립트는 서버에서만 돌아 문법 오류가 실제 배포에서야 드러난다. */
    @Test
    fun `원격 배포 스크립트는 bash 문법으로 읽힌다`() {
        val remote = File(dir, "remote.sh").apply { writeText(remoteScript()) }

        val result = exec(listOf("bash", "-n", remote.path), emptyMap())

        assertThat(result.exit).describedAs(result.output).isEqualTo(0)
    }

    /**
     * pty 없는 원격 `bash -s` 는 SSH 가 끊긴 뒤(runner 가 떠남·job 시한·keepalive) 첫 출력 쓰기에서 SIGPIPE 로 죽어 `up` 도중이나
     * `.last-good-sha` 쓰기 전에 멈출 수 있다. 원격 배포 머리의 세 줄만 떼어 SSH 출력 끊김을 흉내 낸다 — GNU `tee -p` 동작이라 Linux 만.
     * 출력 사이 sleep 은 tee 가 끊김을 먼저 겪게 해 `-p` 가 빠지면 bash 가 확실히 죽게 한다.
     */
    @Test
    @EnabledOnOs(OS.LINUX, disabledReason = "GNU tee -p — 원격 배포는 Linux 서버에서 돈다")
    fun `원격 배포는 SSH 출력이 끊겨도 끝까지 돌고 출력과 종료 코드는 서버 로그에 남는다`() {
        val log = File(dir, "deploy-last.log")
        val done = File(dir, "done")
        val head = remoteLogLines().replace(REMOTE_LOG, log.path)

        val process = ProcessBuilder("bash", "-s").directory(dir)
            .apply { environment().keys.retainAll(setOf("PATH")) }
            .start()
        process.outputStream.bufferedWriter().use {
            it.write(head + "echo before; sleep 0.5; echo after; sleep 0.5; echo after2 >&2; touch '${done.path}'\n")
        }
        val first = process.inputStream.bufferedReader().readLine()
        process.inputStream.close()
        process.errorStream.close()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("출력이 끊긴 원격 배포가 10초 안에 끝나지 않았다")
        }

        assertThat(first).isEqualTo("before")
        assertThat(process.exitValue()).describedAs(log.readText()).isEqualTo(0)
        assertThat(done).describedAs("끊긴 뒤의 명령까지 돌았다").exists()
        assertThat(awaitEndLine(log)).containsSubsequence("before", "after", "after2").endsWith(endLine(0))
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(log.toPath()))).isEqualTo("rw-------")

        // 끝 줄 trap 이 원격 종료 코드(2 롤백·3 롤백 실패·4 Caddy)를 바꾸면 로컬 결과 처리가 어긋난다.
        val failed = exec(listOf("bash", "-c", head + "exit 3"), emptyMap())
        assertThat(failed.exit).isEqualTo(3)
        assertThat(awaitEndLine(log)).endsWith(endLine(3))
    }

    /** 원격 배포 스크립트라 실행하지 않고 본문을 본다. 갈라짐이 pull 뒤면 pull 이 걸린 동안 끊긴 원격 배포가 죽는다. */
    @Test
    fun `원격 배포 출력은 pull 전에 서버 로그로 갈라진다`() {
        val remote = remoteScript()
        val pull = remote.indexOf("docker compose pull")
        val install = remote.indexOf("install -m 600 /dev/null $REMOTE_LOG\n")
        val tee = remote.indexOf("exec > >(tee -p -a $REMOTE_LOG) 2>&1\n")
        val trap = remote.indexOf("trap 'echo \"${endLine("\$?")}\"' EXIT\n")

        // 로그를 비우고 600 으로 만든 뒤에 tee 가 연다 — 거꾸로면 tee 가 umask 권한으로 만든 파일을 install 이 뒤늦게 비운다.
        assertThat(install).isGreaterThanOrEqualTo(0).isLessThan(tee)
        assertThat(tee).isLessThan(pull)
        assertThat(trap).isGreaterThanOrEqualTo(0).isLessThan(pull)
    }

    @Test
    fun `Actions 에서 돌면 알림에 실행 링크를 붙인다`() {
        val repo = deployRepo()

        deploy(repo, remoteRc = 0, mapOf("GITHUB_RUN_ID" to "42", "GITHUB_REPOSITORY" to "owner/app"))

        assertThat(curlArgs.readText()).contains("https://github.com/owner/app/actions/runs/42")
    }

    /** 덤프는 스키마가 바뀔 수 있는 배포에서만 받는다. 매번 받으면 보존 5개가 마이그레이션 직전 덤프를 밀어낸다. */
    @Test
    fun `마이그레이션이 바뀐 배포에만 마이그레이션 전 덤프를 넘긴다`() {
        deploy(deployRepo(migration = true), remoteRc = 0)
        assertThat(sshRemote.readText()).contains("PRE_MIGRATION_DUMP='1'")

        sshRemote.delete()
        File(dir, "repo").deleteRecursively()
        deploy(deployRepo(), remoteRc = 0)
        assertThat(sshRemote.readText()).contains("PRE_MIGRATION_DUMP='0'")

        // 직전 정상 배포를 모르면 마이그레이션이 바뀌었는지 알 수 없어 덤프한다.
        sshRemote.delete()
        File(dir, "repo").deleteRecursively()
        val unknown = deployRepo()
        File(unknown, "deploy/vultr/.state").writeText("PUBLIC_IP=203.0.113.10\n")
        deploy(unknown, remoteRc = 0)
        assertThat(sshRemote.readText()).contains("PRE_MIGRATION_DUMP='1'")
    }

    /** 같은 커밋이면 compose 가 컨테이너를 다시 만들지 않아, 롤백 제외 사유를 마이그레이션과 따로 알린다. */
    @Test
    fun `마지막 정상 배포와 같은 커밋을 배포하면 same-sha 로 넘긴다`() {
        val repo = deployRepo(sameAsLastGood = true)

        deploy(repo, remoteRc = 1)

        assertThat(sshRemote.readText()).contains("MIGRATION_GATE='same-sha'")
    }

    /** `ssh_inst "…" <<'REMOTE' || deploy_rc=$?` 다음 줄부터 단독 `REMOTE` 줄 앞까지. */
    private fun remoteScript(): String {
        val text = RepoFiles.file("deploy/vultr/deploy.sh").readText()
        val start = text.indexOf("<<'REMOTE'")
        check(start >= 0) { "deploy.sh 에서 <<'REMOTE' 를 찾지 못했다" }
        val bodyStart = text.indexOf('\n', start) + 1
        val end = text.indexOf("\nREMOTE\n", bodyStart)
        check(end > bodyStart) { "deploy.sh 에서 원격 스크립트의 끝(REMOTE 줄)을 찾지 못했다" }
        return text.substring(bodyStart, end + 1)
    }

    /** 원격 배포 머리에서 출력을 서버 로그로 가르는 줄들 — 로그 새로 만들기, tee, 끝 줄 trap. */
    private fun remoteLogLines(): String {
        val lines = remoteScript().lines()
        val picked = listOf(
            lines.find { it.startsWith("install ") && it.contains(REMOTE_LOG) },
            lines.find { it.startsWith("exec > >(tee ") },
            lines.find { it.startsWith("trap ") && it.endsWith(" EXIT") },
        )
        check(null !in picked) { "원격 배포 스크립트에 서버 로그 줄이 없다: $picked" }
        return picked.joinToString("\n", postfix = "\n")
    }

    private fun endLine(rc: Any) = "=== 원격 배포 끝 (rc=$rc) ==="

    /** bash 는 프로세스 치환(tee)을 기다리지 않고 끝나므로 tee 가 끝 줄을 쓸 때까지 잠시 기다린다. */
    private fun awaitEndLine(log: File): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!log.readText().contains("원격 배포 끝 (rc=") && System.nanoTime() < deadline) Thread.sleep(20)
        return log.readText().trimEnd()
    }

    private fun notify(level: String, message: String, general: String, error: String, deploy: String, curlExit: Int = 0): Run {
        fakeCurl(curlExit)
        return exec(
            listOf("bash", RepoFiles.file(NOTIFY).path, level, message),
            mapOf("DISCORD_WEBHOOK_URL" to general, "DISCORD_ERROR_WEBHOOK_URL" to error, "DISCORD_DEPLOY_WEBHOOK_URL" to deploy),
        )
    }

    /** 가짜 curl: 인자는 한 줄에 하나씩, 표준입력은 그대로 기록하고 HTTP 코드 자리에 204 를 낸다. */
    private fun fakeCurl(exit: Int = 0) {
        curlArgs.delete()
        File(bin, "curl").apply {
            writeText(
                """
                |#!/usr/bin/env bash
                |printf '%s\n' "${'$'}@" > "${curlArgs.path}"
                |cat > "${curlStdin.path}"
                |printf '204'
                |exit $exit
                """.trimMargin() + "\n",
            )
            setExecutable(true)
        }
    }

    /**
     * deploy.sh 가 `git rev-parse --show-toplevel` 로 저장소를 찾으므로 배포 파일을 임시 git 저장소에 둔다. 서버는 가짜 ssh 가
     * 대신한다 — 원격 배포 스크립트(`bash -s`)는 받은 결과 코드로 끝나고 나머지 명령은 성공한다.
     */
    private fun deployRepo(sameAsLastGood: Boolean = false, migration: Boolean = false): File {
        val repo = File(dir, "repo").apply { mkdirs() }
        val vultr = File(repo, "deploy/vultr").apply { mkdirs() }
        for (name in listOf("deploy.sh", NOTIFY.substringAfterLast('/'), "preflight_exit_params.sh", "docker-compose.prod.yml", "Caddyfile", "apply_caddyfile.sh", "backup.sh")) {
            RepoFiles.file("deploy/vultr/$name").copyTo(File(vultr, name))
        }
        git(repo, "init", "-q")
        git(repo, "add", "deploy")
        commit(repo, "last good")
        val lastGood = git(repo, "rev-parse", "HEAD").trim()
        File(repo, "README").writeText("target\n")
        git(repo, "add", "README")
        if (migration) {
            File(repo, "bot/src/main/resources/db/migration").apply { mkdirs() }.resolve("V99__new.sql").writeText("SELECT 1;\n")
            git(repo, "add", "bot")
        }
        commit(repo, "target")
        val target = if (sameAsLastGood) lastGood else git(repo, "rev-parse", "HEAD").trim()
        File(vultr, ".env").writeText(
            """
            |DB_PASSWORD=db
            |JWT_SECRET=jwt
            |APP_ENCRYPTION_SECRET=enc
            |TRADING_AUTO_START=false
            |APP_DOMAIN=bot.invalid
            |APP_VERSION=$target
            |DISCORD_WEBHOOK_URL=$GENERAL
            |DISCORD_ERROR_WEBHOOK_URL=$ERROR
            |DISCORD_DEPLOY_WEBHOOK_URL=$DEPLOY
            """.trimMargin() + "\n",
        )
        File(vultr, ".state").writeText("PUBLIC_IP=203.0.113.10\nLAST_GOOD_SHA=$lastGood\n")
        script(
            "ssh",
            "printf '%s\\n' \"${'$'}*\" >> \"${sshArgs.path}\"\n" +
                "remote=\"${'$'}{@: -1}\"\nprintf '%s\\n' \"${'$'}remote\" >> \"${sshRemote.path}\"\n" +
                "case \"${'$'}remote\" in *'bash -s'*) cat > /dev/null; exit \"${'$'}FAKE_REMOTE_RC\" ;; esac\nexit 0",
        )
        script(
            "scp",
            "printf '%s\\n' \"${'$'}*\" >> \"${scpArgs.path}\"\nprintf '%s\\n' \"${'$'}{@: -1}\" >> \"${scpCalls.path}\"\nexit 0",
        )
        script("dig", "echo 203.0.113.10")
        fakeCurl()
        return repo
    }

    private fun deploy(repo: File, remoteRc: Int, env: Map<String, String> = emptyMap()): Run =
        exec(listOf("bash", File(repo, "deploy/vultr/deploy.sh").path, "deploy"), mapOf("FAKE_REMOTE_RC" to "$remoteRc") + env)

    private fun script(name: String, body: String) {
        File(bin, name).apply {
            writeText("#!/usr/bin/env bash\n$body\n")
            setExecutable(true)
        }
    }

    private fun commit(repo: File, message: String) {
        git(repo, "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "commit.gpgsign=false", "commit", "-q", "-m", message)
    }

    private fun git(repo: File, vararg args: String): String {
        val result = exec(listOf("git", "-C", repo.path, *args), emptyMap())
        check(result.exit == 0) { "git ${args.joinToString(" ")} 실패:\n${result.output}" }
        return result.output
    }

    private fun exec(command: List<String>, env: Map<String, String>): Run {
        val out = File(dir, "out.txt")
        val process = ProcessBuilder(command)
            .directory(dir)
            .redirectErrorStream(true)
            .redirectOutput(out)
            .apply {
                // 개발자의 전역 git 설정과 CI 의 GITHUB_* 값이 끼지 않게 한다.
                environment().keys.retainAll(setOf("PATH"))
                environment()["PATH"] = "${bin.path}:${environment()["PATH"]}"
                environment()["HOME"] = dir.path
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                environment().putAll(env)
            }
            .start()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("${command.take(3)} 가 60초 안에 끝나지 않았다:\n${out.readText()}")
        }
        return Run(process.exitValue(), out.readText())
    }

    private companion object {
        const val NOTIFY = "deploy/vultr/notify_deploy.sh"
        const val REMOTE_LOG = "/opt/app/.deploy-last.log"
        const val GENERAL = "https://discord.example.test/api/webhooks/1/general"
        const val ERROR = "https://discord.example.test/api/webhooks/2/error"
        const val DEPLOY = "https://discord.example.test/api/webhooks/3/deploy"
    }
}
