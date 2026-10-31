package com.trading.bot.ci

import java.io.File

/**
 * 테스트가 읽는 저장소 파일. Gradle 이 넘긴 `repo.root` 아래에서만 찾는다. 작업 디렉터리에서 위로 올라가며 찾으면
 * main checkout 안에 둔 worktree 에서 돌 때 worktree 에서 지운 파일을 main checkout 의 것으로 읽어
 * 테스트가 조용히 통과한다.
 */
internal object RepoFiles {

    const val PROPERTY = "repo.root"

    // 즉시 초기화하면 속성이 없을 때 첫 접근 뒤로 NoClassDefFoundError 만 남아 아래 안내가 사라진다.
    val root: File by lazy { rootOf(System.getProperty(PROPERTY)) }

    fun rootOf(value: String?): File {
        check(!value.isNullOrBlank()) {
            "$PROPERTY 시스템 속성이 없다 — Gradle(`./gradlew :bot:test`)로 돌리거나 IDE 에서 -D$PROPERTY=<저장소 경로> 를 넘길 것"
        }
        val dir = File(value)
        check(dir.isDirectory) { "$PROPERTY=$value 가 디렉터리가 아니다" }
        return dir
    }

    fun file(relative: String): File = under(root, relative)

    fun under(root: File, relative: String): File {
        val found = File(root, relative)
        check(found.exists()) { "$relative 가 $PROPERTY($root) 아래에 없다" }
        return found
    }
}
