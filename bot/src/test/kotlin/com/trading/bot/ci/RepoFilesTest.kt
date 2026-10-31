package com.trading.bot.ci

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RepoFilesTest {

    @Test
    fun `repo root 속성이 없으면 Gradle 로 돌리라는 안내로 실패한다`() {
        assertThatThrownBy { RepoFiles.rootOf(null) }.hasMessageContaining("Gradle")
        assertThatThrownBy { RepoFiles.rootOf(" ") }.hasMessageContaining("Gradle")
    }

    @Test
    fun `루트 아래에 없는 파일은 위 디렉터리에서 찾지 않고 실패한다`(@TempDir tempDir: File) {
        // worktree 가 main checkout 안에 있을 때 위로 올라가면 main checkout 의 같은 경로를 읽는다.
        File(tempDir, "deploy/vultr").mkdirs()
        File(tempDir, "deploy/vultr/deploy.sh").writeText("echo")
        val nested = File(tempDir, "sub").apply { mkdirs() }

        assertThatThrownBy { RepoFiles.under(nested, "deploy/vultr/deploy.sh") }.hasMessageContaining("아래에 없다")
        assertThat(RepoFiles.under(tempDir, "deploy/vultr/deploy.sh").readText()).isEqualTo("echo")
    }
}
