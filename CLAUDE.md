# coin-trading-bot — Environment

이 파일은 **환경/빌드 정보**만 담는다. 개발 워크플로우 규칙은 `~/.claude/CLAUDE.md` 참고.
코드 스타일·보안·클린코드·테스트 체크는 `.git/hooks/pre-push`의 codex review가 게이트한다.

## Build & Test

- JDK 21 필요. Gradle toolchain + Foojay resolver가 설정되어 있어, 로컬에 JDK 21이 없으면 첫 빌드 시 자동 다운로드됨 (`~/.gradle/jdks`에 캐시). `JAVA_HOME`을 수동으로 잡을 필요 없음.
- 빌드: `./gradlew build` (Windows cmd는 `gradlew.bat build`)
- 테스트: `./gradlew test`
- 타입체크: `./gradlew compileKotlin`
- 코드 수정 후 커밋 전 최소 `compileKotlin` 통과 확인.

## 모듈 구조

`settings.gradle.kts`: `include("common", "collector", "bot", "research")`

- `bot` — Spring Boot 메인 애플리케이션 (실거래 봇 + REST API + SPA, port 8080)
- `collector` — Spring Boot 데이터 수집 서비스 (Upbit/Binance/KIS → Kafka, port 8081)
- `common` — 공용 도메인 모델, Kafka 이벤트 스키마, 인디케이터, 레거시 스윙 전략
- `research` — 백테스트/walk-forward/리서치 프레임워크 (`:common`만 의존)

## 스펙 문서

- 아키텍처 변경은 `PROJECT_ANALYSIS.md`에 반영
- 설계 스펙은 `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`

## 문서 동기화 대상

글로벌 `~/.claude/CLAUDE.md`의 "문서 동기화(범위 한정)" 기준에 해당할 때 업데이트:

| 변경 종류 | 업데이트 대상 |
|-----------|---------------|
| 외부 visible behavior / Public API / CLI 변경 | `README.md` |
| 모듈 구조·의존성·아키텍처 변경 | `PROJECT_ANALYSIS.md` + `README.md` (해당 섹션) |
| 설계 결정·신규 서브시스템 | `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md` 신규 |
| 운영/배포 절차 변경 | `docs/runbook/` (존재 시) 또는 `README.md`의 운영 섹션 |
| 보안 정책 변경 | `SECURITY.md` (존재 시) |

내부 리팩터·테스트 전용·invisible 버그 수정은 문서 업데이트 불필요.
