# coin-trading-bot — Environment

이 파일은 **환경/빌드 정보**만 담는다. 개발 워크플로우 규칙은 `~/.claude/CLAUDE.md` 참고.
코드 스타일·보안·클린코드·테스트 체크는 `.git/hooks/pre-push`의 codex review가 게이트한다.

## Build & Test

- JDK 21 필수: `JAVA_HOME=/Users/jongyoonlee/Library/Java/JavaVirtualMachines/jbr-21.0.9/Contents/Home`
- 빌드: `JAVA_HOME=... ./gradlew build`
- 테스트: `JAVA_HOME=... ./gradlew test`
- 타입체크: `JAVA_HOME=... ./gradlew compileKotlin`
- 코드 수정 후 커밋 전 최소 `compileKotlin` 통과 확인.

## 모듈 구조

- `app` — Spring Boot 메인 애플리케이션 (실거래 봇)
- `common` — 공용 도메인 모델
- `research` — 백테스트/walk-forward/리서치 프레임워크

## 스펙 문서

- 아키텍처 변경은 `PROJECT_ANALYSIS.md`에 반영
- 설계 스펙은 `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`
