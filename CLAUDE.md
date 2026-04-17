# Coin Trading Bot - Claude Code Rules

## 작업 방식 (필수)

### 코드 변경 워크플로우 (반드시 순서대로)
1. **TaskCreate로 태스크 생성** — 진행상황 기록 (3단계 미만이어도 권장)
2. **`/test-driven-development`로 테스트 먼저 작성** — 실패하는 테스트 → 구현 → 통과 순서
3. **구현** (테스트가 통과할 때까지)
4. **버그/테스트 실패 발생 시 `/systematic-debugging`** — 추측으로 고치지 말 것
5. **`/ast-code-analysis-superpower`** — 구조/보안 스캔 (커밋 전 필수)
6. **`/codex-review`** — 2차 리뷰 게이트 (커밋 전 필수)
7. 리뷰 피드백 반영 후 커밋

### 독립 태스크 병렬화
- 서로 의존 없는 태스크 2개 이상이면 **`/subagent-driven-development`**로 병렬 실행

- 세션을 넘기는 장기 작업은 `/task-plan`으로 `.claude/tasks/`에 기록

### 진행 상황 표시
- 작업이 3단계 이상이면 반드시 **TaskCreate로 태스크 목록 생성**
- 각 태스크 시작 시 `in_progress`로 변경 (스피너가 사용자에게 보임)
- 각 태스크 완료 시 즉시 `completed`로 변경
- **모든 태스크 완료 후** 최종 요약을 작성 — 중간에 끝내지 말 것

### 작업 완료 요약
- 작업을 완료한 후 반드시 사용자에게 **무엇을 했는지 요약**할 것
- 요약 형식:
  - **변경한 파일 목록** (파일명 + 한 줄 설��)
  - **핵심 변경 내용** (무엇을 왜 바꿨는지)
  - **주의사항** (있다면 ��� 추가 테스트 필요, 설정 변경 필요 등)
- 코드만 바꾸고 끝내지 �� 것 — 반드시 요약으로 마무리

## Build & Test
- JDK 21 필수: `JAVA_HOME=/Users/jongyoonlee/Library/Java/JavaVirtualMachines/jbr-21.0.9/Contents/Home`
- 빌드: `JAVA_HOME=... ./gradlew build`
- 테스트: `JAVA_HOME=... ./gradlew test`
- 타입체크: `JAVA_HOME=... ./gradlew compileKotlin`
- 코드 수정 후 반드시 `compileKotlin`으로 컴파일 에러 확인할 것

## Git & 커밋 규칙
- **커밋은 최대한 작게** — 하나의 커밋 = 하나의 논리적 변경
  - 기능 추가, 버그 수정, 리팩토링, 테스트 추가를 별도 커밋으로 분리
  - "A도 고치고 B도 추가" 같은 커밋 금지
- 커밋 메시지는 **변경의 이유(why)**를 담을 것 — "what"은 diff에서 보임
- 메시지 형식: `type: 한 줄 설명` (feat, fix, refactor, test, docs, chore)
- 작업 중간중간 자주 커밋 — 큰 덩어리를 한번에 커밋하지 말 것

## 문서 동기화 (필수)
- **모든 코드 변경은 `README.md`에 반영되어야 함** — 항상
  - 새 기능 추가 → 사용법/설정/API 섹션에 추가
  - 아키텍처·모듈 구조 변경 → README의 구조 설명·다이어그램 업데이트
  - 기능 제거 → 해당 섹션 삭제
- README 갱신은 관련 코드 변경과 **같은 PR/커밋 시점**에 반영 (별도 `docs:` 커밋으로 분리 가능, 단 너무 늦게 미루지 말 것)
- 전체 아키텍처 수준 변경은 `PROJECT_ANALYSIS.md`도 함께 갱신
- 설계 스펙은 `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md` 경로로 저장

## 클린코드 원칙 (반드시 준수)

### 함수
- 함수는 **한 가지 일만** 할 것 (Single Responsibility)
- 함수 길이는 **30줄 이내** 권장 — 길어지면 분리
- 함수명은 동사로 시작, 하는 일을 명확히 표현 (예: `calculatePnl`, `validateOrder`)
- 파라미터는 **3개 이하** 권장 — 많으면 data class로 묶을 것
- 부수효과(side effect)를 최소화하고, 있다면 함수명에 드러낼 것

### 네이밍
- 이름만 보고 역할을 알 수 있게 — 축약어 자제 (`amt` 대신 `amount`)
- Boolean 변수/함수: `is`, `has`, `should`, `can` 접두사 (예: `isRunning`, `hasPosition`)
- 컬렉션: 복수형 (예: `tickers`, `candles`, `tradeRecords`)

### 설계
- **DRY** — 동일 로직이 3번 이상 반복되면 함수로 추출
- **YAGNI** — 현재 필요 없는 기능을 미리 만들지 말 것
- **의존성 방향**: Controller → Service → Repository (역방향 의존 금지)
- 순환 의존(circular dependency) 금지
- 인터페이스는 구현체가 2개 이상이거나 테스트 격리가 필요할 때만 만들 것

### 코드 구조
- Early return으로 중첩(nesting) 줄이기 — if-else 3단 이상 중첩 금지
- 매직 넘버 금지 — 상수(`const val`)로 의미 부여 (예: `0.03` 대신 `STOP_LOSS_RATE`)
- 주석은 "왜(why)"만 — "무엇(what)"은 코드가 말하게 할 것
- Dead code(사용하지 않는 코드) 남기지 말 것 — git에 이력이 있으므로 삭제

## Code Rules (반드시 준수)

### Kotlin 스타일
- 클래스: PascalCase, 함수: camelCase, 상수: UPPER_SNAKE_CASE (companion object 내)
- DB 엔티티 클래스는 반드시 `Entity` 접미사 사용 (예: `UserEntity`, `TradeRecordEntity`)
- wildcard import 금지 — 모든 import는 명시적으로 작성
- 하드코딩 금지 — 설정값은 `@ConfigurationProperties` 또는 `companion object`의 `const val`로

### 비동기 패턴
- 컨트롤러, 서비스 함수는 `suspend` 함수로 작성
- Reactor `Mono`/`Flux`는 `awaitSingle()`, `awaitSingleOrNull()`로 코루틴 변환
- blocking 호출 (`Thread.sleep`, blocking I/O 등) 금지 — `delay()` 또는 `withContext(Dispatchers.IO)` 사용

### 에러 처리
- 컨트롤러에서 에러 응답: `ResponseStatusException(HttpStatus.XXX, "message")` 패턴 사용
- null 체크: Elvis 연산자 + 예외 (예: `?: throw ResponseStatusException(...)`)
- 외부 API 호출 실패는 graceful degradation — 로그 남기고 기본값 반환

### 로깅
- Logger 생성: `private val log = LoggerFactory.getLogger(javaClass)`
- 파라미터화된 메시지 사용: `log.warn("Failed: {}", e.message)` (문자열 연결 금지)

### 테스트
- 테스트 이름: 백틱 + 자연어 서술 (예: `` `should return empty list when no trades exist` ``)
- MockK 사용 (Mockito 아님)
- `@BeforeEach`로 테스트 초기화

### API 응답
- 요청/응답에 전용 data class DTO 사용 (예: `AuthRequest`, `StartBotRequest`)
- JSON 필드 매핑: `@JsonProperty`로 snake_case 변환

### 보안 (필수 — 금융 서비스이므로 엄격히 준수)

#### 민감 데이터 분류
- **절대 노출 금지**: API 키, Secret 키, JWT 시크릿, DB 비밀번호, 암호화 마스터키
- **암호화 저장 필수**: 업비트 API 키, Discord 웹훅 URL → `SecretsCrypto`(AES-GCM)로 암호화 후 DB 저장
- **로그 출력 금지**: 토큰, 비밀번호, API 키, 사용자 잔고 상세 — 마스킹 처리 (예: `key=ab***ef`)
- **응답 노출 금지**: 스택 트레이스, 내부 경로, DB 스키마를 API 응답에 포함하지 말 것

#### 코드 작성 규칙
- 시크릿은 반드시 환경변수(`UPBIT_ACCESS_KEY`)로 주입 — 코드/설정파일에 직접 기재 금지
- `.env`, `.pem`, `credentials.json`, `*-key.pem` 파일은 `.gitignore`에 포함되어야 함
- CORS: `allowedOriginPatterns("*")` + `allowCredentials(true)` 조합 금지 — 명시적 도메인만 허용
- 인증 엔드포인트(`/api/auth/**`)에도 Rate Limiting 적용 — 브루트포스 방지
- 쿼리 파라미터는 반드시 `URLEncoder.encode()` 적용 — 인젝션 방지
- 사용자 입력(ticker, market 코드 등)은 허용 목록(whitelist)으로 검증

#### JWT 보안
- JWT 시크릿은 최소 256-bit — 짧은 문자열 금지
- 토큰 만료 시간 설정 필수 — 무기한 토큰 금지
- 토큰에 민감 정보(API 키, 비밀번호) 포함 금지 — userId, username만 허용

#### 인프라
- DB 접속 정보는 환경변수로만 — `application.yml`에 실제 비밀번호 금지
- Docker 이미지에 시크릿 베이킹 금지 — 런타임 환경변수로 주입
- actuator 엔드포인트: health, info, prometheus만 공개 — 나머지 비공개

## 코드리뷰 체크리스트
코드를 수정하거나 새로 작성한 후, 커밋 전에 반드시 아래 항목을 자체 점검할 것:
1. [ ] 컴파일 에러 없음 (`compileKotlin` 통과)
2. [ ] 기존 테스트 깨지지 않음 (`./gradlew test` 통과)
3. [ ] suspend 함수에서 blocking 호출 없음
4. [ ] wildcard import 없음
5. [ ] 하드코딩된 설정값 없음
6. [ ] 에러 처리 패턴 준수 (ResponseStatusException)
7. [ ] 로거 패턴 준수 (파라미터화된 메시지)
8. [ ] 새 기능에 대한 테스트 작성됨
9. [ ] 보안 민감 정보 노출 없음
10. [ ] **`README.md` 동기화** — 코드 변경이 README에 반영됨 (기능/구조/API 변경 시)
