package com.trading.bot.ci

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * `deploy.yml` 의 배포 무관 경로 목록은 트리거 `on.push.paths-ignore` 와 가드 `Check deployment commit is current main` 의
 * `:(exclude)` 두 곳에 있다. 트리거에만 경로를 더하면 그 경로만 바꾼 push 는 실행이 생기지 않고, 앞서 돌던 실행은
 * 가드가 stale 로 판정해 배포를 건너뛰어 옛 이미지가 남는다.
 */
class DeployWorkflowPathListTest {

    private val workflow: Map<*, *> = Yaml().load(workflowFile().readText())

    /** IDE 에서 이 테스트만 돌리면 `repo.root` 가 없을 수 있어 위로 올라가며 찾는다. */
    private fun workflowFile(): File {
        val relative = ".github/workflows/deploy.yml"
        System.getProperty("repo.root")?.let { root ->
            val fromProperty = File(root, relative)
            if (fromProperty.isFile) return fromProperty
        }
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative 을 찾지 못했다 (repo.root=${System.getProperty("repo.root")}, cwd=${File(".").absolutePath})")
    }

    /** YAML 1.1 에서 `on:` 은 boolean `true` 로 파싱되므로 두 키를 모두 시도한다. */
    private fun triggerPathsIgnore(): List<String> {
        val on = workflow[true] ?: workflow["on"] ?: error("deploy.yml 에 on: 트리거가 없다")
        val push = (on as Map<*, *>)["push"] ?: error("on.push 가 없다")
        @Suppress("UNCHECKED_CAST")
        return ((push as Map<*, *>)["paths-ignore"] as? List<String>)
            ?: error("on.push.paths-ignore 가 없다")
    }

    private fun guardExcludes(): List<String> {
        val jobs = workflow["jobs"] as Map<*, *>
        val deploy = jobs["deploy-vultr"] as? Map<*, *> ?: error("deploy-vultr job 이 없다")
        val steps = deploy["steps"] as List<*>
        val guard = steps.filterIsInstance<Map<*, *>>().firstOrNull { it["id"] == "main-head" }
            ?: error("id=main-head 인 가드 스텝이 없다 — 이름이 바뀌었으면 이 테스트도 함께 고친다")
        val diff = diffCommand(guard["run"] as String)

        // 작은따옴표·큰따옴표 모두 유효한 pathspec 이라 한쪽만 읽으면 어긋난 목록이 비교를 통과한다.
        val parsed = EXCLUDE_PATHSPEC.findAll(diff).map { it.groupValues[1] }.toList()
        val occurrences = EXCLUDE_MARKER.findAll(diff).count()
        check(parsed.size == occurrences) {
            "가드의 :(exclude) $occurrences 개 중 ${parsed.size} 개만 파싱했다 — " +
                "인용 형식이 바뀌었으면 이 테스트의 정규식도 함께 고친다"
        }
        return parsed
    }

    /** 주석에 남은 옛 pathspec 을 가드 목록으로 오인하지 않게 실제 `git diff` 명령의 인자만 본다. */
    private fun diffCommand(run: String): String {
        val start = run.indexOf(DIFF_COMMAND)
        check(start >= 0) {
            "가드에서 '$DIFF_COMMAND' 를 찾지 못했다 — 명령이 바뀌었으면 이 테스트도 함께 고친다"
        }
        // 줄 끝 백슬래시로 이어지는 논리적 한 줄을 모은다.
        return buildString {
            for (line in run.substring(start).lines()) {
                append(line).append('\n')
                if (!line.trimEnd().endsWith("\\")) break
            }
        }
    }

    @Test
    fun `트리거와 가드의 배포 무관 경로 목록이 정확히 같다`() {
        assertThat(guardExcludes())
            .describedAs(
                "deploy.yml 의 두 목록이 어긋났다. 트리거에만 경로를 추가하면 그 경로만 바꾼 push 가 " +
                    "조용히 미배포된다 — 양쪽을 함께 고쳐야 한다.",
            )
            .isEqualTo(triggerPathsIgnore())
    }

    /**
     * 제외 목록에는 바뀌어도 배포가 필요 없는 것만 있어야 한다. 이미지에 들어가는 입력을 실제로 열거해 그중 하나라도
     * 제외 패턴에 걸리면 실패한다. `**` 같은 패턴은 리터럴 비교로 못 잡아 glob 으로 매칭한다.
     */
    @Test
    fun `배포 입력 중 어느 것도 제외 패턴에 삼켜지지 않는다`() {
        val patterns = (triggerPathsIgnore() + guardExcludes()).distinct()
        val inputs = deployInputs()

        check(inputs.size > 50) { "배포 입력을 ${inputs.size} 개밖에 못 찾았다 — 열거가 깨졌다" }

        val swallowed = inputs.filter { path -> patterns.any { matchesGlob(it, path) } }

        assertThat(swallowed)
            .describedAs(
                "배포 입력이 제외 패턴 $patterns 에 매칭된다 — 그 파일만 바꾼 push 가 조용히 미배포된다. " +
                    "삼켜진 예: ${swallowed.take(5)}",
            )
            .isEmpty()
    }

    /** Dockerfile 이 이미지에 복사하는 것과 배포 스크립트·워크플로. 소스 트리만 훑어 build 산출물은 뺀다. */
    private fun deployInputs(): List<String> {
        val root = workflowFile().parentFile.parentFile.parentFile
        val fromTrees = listOf("bot/src", "common/src", "gradle", "deploy").flatMap { rel ->
            File(root, rel).walkTopDown()
                .filter { it.isFile }
                .map { it.toRelativeString(root).replace(File.separatorChar, '/') }
                // 배포 스크립트·테스트 트리의 `.md` 는 문서라 배포 무관이지만,
                // 앱 리소스의 `.md` 는 jar 에 들어가므로 검사 대상으로 남긴다.
                .filterNot { rel -> rel.endsWith(".md") && !rel.startsWith("bot/src/main/") && !rel.startsWith("common/src/main/") }
                .toList()
        }
        val singles = listOf(
            "Dockerfile", ".dockerignore", "build.gradle.kts", "settings.gradle.kts",
            "bot/build.gradle.kts", "common/build.gradle.kts", ".github/workflows/deploy.yml",
        ).filter { File(root, it).isFile }
        return fromTrees + singles
    }

    /** GitHub 의 paths-ignore 와 git pathspec 은 둘 다 glob 이라 근사로 충분하다. */
    private fun matchesGlob(pattern: String, path: String): Boolean =
        runCatching {
            FileSystems.getDefault().getPathMatcher("glob:$pattern").matches(Path.of(path))
        }.getOrElse { error("제외 패턴 '$pattern' 을 glob 으로 해석할 수 없다") }

    private companion object {
        val EXCLUDE_PATHSPEC = Regex("['\"]:\\(exclude\\)([^'\"]+)['\"]")
        val EXCLUDE_MARKER = Regex(":\\(exclude\\)")
        const val DIFF_COMMAND = "git diff --name-only"
    }
}
