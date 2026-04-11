# Performance Testing

[k6](https://grafana.com/docs/k6/) 기반 부하 테스트입니다.

## 설치

```bash
brew install k6
```

## 실행

```bash
# 로컬 서버 대상 (기본)
k6 run perf/load-test.js

# EC2 서버 대상
k6 run -e BASE_URL=http://43.202.225.7:8080 perf/load-test.js

# smoke test만 실행
k6 run --scenario smoke perf/load-test.js
```

## 시나리오

| 시나리오 | VU | 시간 | 설명 |
|---------|-----|------|------|
| smoke | 1 | 30s | 공개 엔드포인트 기본 검증 |
| load | 0→50 | 8m | 점진적 부하 증가, 인증 포함 |

## Threshold

- P95 응답 시간 < 500ms
- P99 응답 시간 < 1500ms
- 에러율 < 5%
