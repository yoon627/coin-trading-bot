---
title: 빌드 환경 — JDK 21 고정, Gradle 8.12, JDK 25 비호환
category: entity
created: 2026-07-28
updated: 2026-07-28
claim_state: current
verified: 2026-07-28 — gradle-wrapper.properties(gradle-8.12-bin.zip), build.gradle.kts:22 JavaLanguageVersion.of(21)
sources:
  - gradle/wrapper/gradle-wrapper.properties
  - build.gradle.kts
  - CLAUDE.md
---

# 빌드 환경

| 항목 | 값 |
|---|---|
| JDK | **21** (`JavaLanguageVersion.of(21)` toolchain) |
| Gradle | **8.12** (wrapper) |
| Kotlin | 2.1 |

Gradle toolchain + Foojay resolver 가 설정돼 있어 **로컬에 JDK 21 이 없으면 첫 빌드 때 자동 다운로드**(`~/.gradle/jdks` 캐시)된다. 정상 상황에서는 `JAVA_HOME` 을 손댈 필요가 없다.

## 명령

```bash
./gradlew build          # 빌드
./gradlew test           # 테스트
./gradlew compileKotlin  # 타입체크 (커밋 전 최소 확인)
```

## 함정 — JDK 25 로컬 기본값

로컬 기본 JDK 가 **25** 이면 Gradle 8.12 의 Kotlin DSL 이 파싱 단계에서 깨진다:

```
IllegalArgumentException: 25.0.2
```

toolchain 은 *컴파일 대상* JDK 를 정할 뿐, **Gradle 자신이 도는 JVM** 은 로컬 기본값이다. 그래서 toolchain 설정이 있어도 이 오류가 난다.

우회: Gradle 을 JDK 21 로 실행한다.

```bash
JAVA_HOME=/path/to/jbr-21.0.9/Contents/Home ./gradlew test
```

## 왜 중요한가

빌드가 안 도는 상태에서는 push 게이트를 통과할 수 없다 — pre-push hook 이 fail-closed 라 검증 실패가 곧 push 차단이다([[prepush-codex-review]]). 모듈 구조와 산출물은 [[architecture-overview]] 참조.
