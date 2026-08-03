---
title: discord-error-alert — ERROR 로그 Discord 알림
status: done
started: 2026-05-31
updated: 2026-05-31
---

# Goal
서버 ERROR 로그 발생 시 Discord webhook 으로 알림. 앱 JVM 내부 Logback appender (RAM 부담 0), rate limit + 민감정보 마스킹 + opt-in 으로 안전하게.

# Progress
- 2026-05-31: 설계 사용자 승인. 브랜치 feat/discord-error-log-alert. Explore 완료.
- 2026-05-31: plan-reviewer(+codex) CONDITIONAL → 4개 강한 우려 반영. webhook 분리(별도 opt-in) 사용자 확정. 규모 structural.
- 2026-05-31: 구현 완료(RateLimiter/Sanitizer/Appender/sendErrorAlert/ErrorAlertProperties/discordWebClient timeout/yml). TDD Green.
- 2026-05-31: code-reviewer(+codex) Major 6건 → fix loop 1회 반영. :bot:test 전체 통과, ./gradlew build 통과.

# Next
commit → push(사용자 결정). 운영 활성화: `deploy/aws/.env` 에 `DISCORD_ERROR_ALERT_ENABLED=true` + `DISCORD_ERROR_WEBHOOK_URL=<에러전용 webhook>` 설정 후 `deploy.sh deploy`.

# Decisions
- Logback `AppenderBase<ILoggingEvent>` 를 Spring bean 으로 root logger 프로그래밍 attach. 기존 DiscordNotifier 재사용 + 새 컨테이너 없음.
- webhook 분리 + opt-in: `discord.error-alert.{enabled(기본 false), webhook-url}`. 미설정/disabled 시 미등록 = kill switch.
- 민감정보 마스킹(LogMessageSanitizer): Bearer(+base64)/JWT/access·secret·password(콜론·등호·공백 구분)/discord webhook(host 변형).
- 무한루프 방어: DENY_PREFIXES(notification/reactor/netty) 제외 + ThreadLocal reentrancy(remove) + 내부 실패 logback addError.
- timeout: discordWebClient connect 3s/response 5s. rate limit nowMs = System.currentTimeMillis()(단조 보장).
- rate limit: 동일 fingerprint 5분 쿨다운 + 전역 분당 5건 + FIFO size cap(500). 쿨다운 후 "지난 5분 K회 추가" 요약.
- embed: Message 1000자/Stack 950자 truncate (Discord field value 1024 한도).
- 한계: ApplicationReadyEvent 이후 캡처(기동 에러 미포함).

# Key Files
- bot/.../notification/ErrorAlertRateLimiter.kt (신규) + Test
- bot/.../notification/LogMessageSanitizer.kt (신규) + Test
- bot/.../notification/DiscordErrorLogAppender.kt (신규)
- bot/.../notification/DiscordNotifier.kt — sendErrorAlert + Test
- bot/.../config/AppConfig.kt — ErrorAlertProperties
- bot/.../config/WebClientConfig.kt — discordWebClient timeout
- bot/src/main/resources/application.yml — discord.error-alert

# Review Disposition
- embed 1024 초과 알림유실 → fix (Message take1000 / Stack take950)
- sanitizer 우회(+/=, 공백 구분자, host 변형) → fix
- appender detach 누수 → fix (@PreDestroy detach+stop)
- reentrancy async 누수 → fix (reactor/netty denylist)
- timeStamp 단조증가 가정 → fix (System.currentTimeMillis)
- ThreadLocal → fix (remove)
- accessOrder LRU → FIFO size cap 전환 (Kotlin LinkedHashMap accessOrder 미동작 확인, 메모리 bound 목적 동일 달성)
- fingerprint 과병합 → defer (의도된 dedup, suppressedCount 로 완화)
- raw key(키워드 없는) 마스킹 → defer (원리적 한계)
- handle() 단위테스트 → defer (appender private visibility, 추후 티켓)

# Blockers
(없음)
