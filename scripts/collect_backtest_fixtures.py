#!/usr/bin/env python3
"""백테 fixture 수집 — 시점 중립(point-in-time) 유니버스 선정 + 200봉 다운로드 + 정규화.

    python3 scripts/collect_backtest_fixtures.py            # 미리보기(유니버스만)
    python3 scripts/collect_backtest_fixtures.py --write     # fixture 파일까지 기록

이슈 #112. 이전 방식은 **수집 시점의 거래대금 상위**로 마켓을 골라 구간 *끝* 정보를 썼다(look-ahead).
여기서는 같은 "거래대금 상위" 규칙을 **구간 시작 시점**으로 옮겨 적용한다.

## 편향에 대해 정직하게

- **없앤 것**: 신규상장 배제 편향 + "오늘의 승자를 과거에 소급 적용" 하는 look-ahead.
  순위 창은 구간 시작 **이전** 30봉이라 구간 내부·이후 정보를 쓰지 않는다.
- **못 없앤 것 (생존편향)**: 그 사이 상장폐지된 종목은 Upbit 공개 API 가 404 를 낸다
  (`KRW-LUNA`·`BTC-LUNA`·`USDT-LUNA` 실측 — 존재하지 않는 코드와 동일 응답).
  데이터가 아예 없으므로 표본에 넣을 방법이 없고, **편향의 크기조차 측정할 수 없다**.
  이 스크립트가 세는 `delisted` 카운트는 "현재 목록에 없어서 후보에서 빠진 수"가 아니라
  0 이다 — 애초에 현재 목록을 순회하므로 폐지된 것은 보이지도 않는다. 그게 한계의 본질이다.

## 미상장 vs 폐지 (API 응답이 구별해 준다)

- 현재 상장 + 그 시점 미상장 → `200 OK` + **빈 배열** → 후보에서 정당하게 제외(존재하지 않았다).
- 폐지 → `404 Code not found` → 현재 목록에 없으므로 애초에 순회 대상이 아니다.

## 재현성의 조건

순위 후보는 **오늘의** KRW 목록이다. 새 상장이 늘면 "미상장" 카운트만 늘고 상위 N 은 그대로다
(신규 상장은 과거 데이터가 없으므로 과거 순위에 못 든다). 다만 **폐지됐다 재상장된 코드**가 있다면
과거 데이터를 들고 순위에 새로 들어올 수 있다 — 드물지만 출력이 달라질 수 있는 유일한 경로다.
"""
import argparse
import json
import pathlib
import sys
import time
import urllib.error
import urllib.request
from datetime import date, timedelta

API = "https://api.upbit.com/v1"
FIXTURE_DIR = pathlib.Path(__file__).resolve().parent.parent / "bot/src/test/resources/backtest"

BARS = 200
TOP_N = 8
RANK_WINDOW = 30
# 변동성이 없어 전략 비교에 무의미하다.
STABLECOINS = {"KRW-USDT", "KRW-USDC", "KRW-DAI", "KRW-BUSD", "KRW-TUSD"}

# 구간 = [시작, 시작+199]. 국면이 둘인 이유는 fixture README 참조.
REGIMES = {"bear": date(2026, 1, 31), "bull": date(2023, 11, 23)}

# rate limit: `remaining-req: group=candles; min=600; sec=9` (실측) → 초당 9 아래로 유지
THROTTLE_SEC = 0.13


def _get(path: str, **params):
    """(payload, http_status). 404 는 예외가 아니라 상태로 돌려준다 — 폐지 판정에 쓰인다."""
    url = f"{API}/{path}?" + "&".join(f"{k}={v}" for k, v in params.items())
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url, timeout=20) as r:
                return json.load(r), 200
        except urllib.error.HTTPError as e:
            if e.code == 429:  # rate limit — 백오프 후 재시도
                time.sleep(1.0 + attempt)
                continue
            return None, e.code
        except Exception:
            time.sleep(0.5)
    return None, 0


def krw_markets() -> list[str]:
    payload, status = _get("market/all", isDetails="true")
    if payload is None:
        sys.exit(f"market/all 조회 실패 (HTTP {status})")
    return [m["market"] for m in payload
            if m["market"].startswith("KRW-") and m["market"] not in STABLECOINS]


def select_universe(markets: list[str], start: date) -> tuple[list[tuple[str, float]], int, int]:
    """구간 시작 **이전** RANK_WINDOW 봉의 평균 거래대금으로 순위. 반환 (상위 N, 미상장 수, 이력부족 수)."""
    ranked, unlisted, too_new = [], 0, 0
    for market in markets:
        candles, status = _get("candles/days", market=market, count=RANK_WINDOW,
                               to=f"{start.isoformat()}T00:00:00Z")
        time.sleep(THROTTLE_SEC)
        if candles is None:
            # 조용히 건너뛰면 그 마켓이 원래 상위였을 때 유니버스가 말없이 달라진다 —
            # 재현 가능해야 하는 도구에서 가장 위험한 실패다.
            sys.exit(f"{market} 순위 조회 실패 (HTTP {status}) — 유니버스가 달라질 수 있어 중단한다.")
        if not candles:
            unlisted += 1  # 그 시점 미상장 — 정당한 제외
            continue
        if len(candles) < RANK_WINDOW:
            too_new += 1  # 상장 직후라 순위 근거가 얇다
            continue
        ranked.append((market, sum(c["candle_acc_trade_price"] for c in candles) / len(candles)))
    ranked.sort(key=lambda r: -r[1])
    return ranked[:TOP_N], unlisted, too_new


def normalize(candle: dict) -> dict:
    """`Candle` 이 쓰는 7키만 남긴다. 가격은 값 불변(수익률 직결) — 거래량만 4자리 반올림."""
    def compact(v: float):
        # 정수값은 정수로 — 90959000.0 대신 90959000. 값은 그대로고 표기만 줄인다.
        # (기존 fixture 가 jq 로 만들어져 이 표기다. 맞춰두지 않으면 재수집 diff 가 전 파일로 번져 리뷰가 불가능하다.)
        return int(v) if float(v).is_integer() else v

    return {
        "market": candle["market"],
        "candle_date_time_kst": candle["candle_date_time_kst"],
        "opening_price": compact(candle["opening_price"]),
        "high_price": compact(candle["high_price"]),
        "low_price": compact(candle["low_price"]),
        "trade_price": compact(candle["trade_price"]),
        "candle_acc_trade_volume": compact(round(candle["candle_acc_trade_volume"], 4)),
    }


def fetch_window(market: str, start: date) -> list[dict] | None:
    """[start, start+BARS-1] 200봉. API 는 `to` **이전**을 주므로 마지막 날 +1 을 넘긴다."""
    end_exclusive = start + timedelta(days=BARS)
    candles, _ = _get("candles/days", market=market, count=BARS,
                      to=f"{end_exclusive.isoformat()}T00:00:00Z")
    time.sleep(THROTTLE_SEC)
    if not candles or len(candles) < BARS:
        return None
    return [normalize(c) for c in candles]  # 최신순 유지 — BacktestEngine.run 이 뒤집는다


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true", help="fixture 파일까지 기록")
    args = parser.parse_args()

    markets = krw_markets()
    print(f"현재 상장 KRW 마켓 {len(markets)}개 (스테이블 {len(STABLECOINS)}종 제외)\n")

    for regime, start in REGIMES.items():
        top, unlisted, too_new = select_universe(markets, start)
        end = start + timedelta(days=BARS - 1)
        print(f"## {regime}  {start} ~ {end} ({BARS}봉)")
        print(f"   그 시점 미상장 {unlisted} / 상장 직후({RANK_WINDOW}봉 미만) {too_new} 제외")
        for i, (market, avg) in enumerate(top, 1):
            print(f"   {i}. {market:<12} {RANK_WINDOW}일 평균 거래대금 {avg / 1e8:>9,.0f} 억원")

        if not args.write:
            print()
            continue

        # 전부 받은 뒤에 쓴다. 지우고 받다가 실패하면 fixture 디렉토리가 반만 찬 채 남고,
        # 그 상태로 돌린 백테는 "유니버스가 줄었다"는 사실을 아무도 모르는 채 결과를 낸다.
        fetched = []
        for market, _ in top:
            candles = fetch_window(market, start)
            if candles is None:
                sys.exit(f"{market}: {BARS}봉 확보 실패 — 유니버스 선정과 모순이다. 아무것도 쓰지 않고 중단한다.")
            fetched.append((market, candles))

        out_dir = FIXTURE_DIR / regime
        out_dir.mkdir(parents=True, exist_ok=True)
        for old in out_dir.glob("KRW-*.json"):
            old.unlink()  # 유니버스가 바뀌므로 옛 마켓 파일이 남으면 안 된다
        for market, candles in fetched:
            (out_dir / f"{market}.json").write_text(json.dumps(candles, separators=(",", ":")) + "\n")
            print(f"   wrote {regime}/{market}.json")
        print()

    if not args.write:
        print("미리보기만 했다. 파일까지 쓰려면 --write.")


if __name__ == "__main__":
    main()
