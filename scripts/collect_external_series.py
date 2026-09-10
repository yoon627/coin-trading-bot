#!/usr/bin/env python3
"""외부 레짐 시계열 fixture 수집 — plan `2026-09-10-external-regime-gate` Decisions 1·Acceptance 7 의 단일 소스.

다섯 일별 스칼라를 `bot/src/test/resources/backtest/external/<series>.json` 에 쓴다. 전부 무인증 공개 API.

| series           | 값                                                                    | 출처                                  |
|------------------|-----------------------------------------------------------------------|---------------------------------------|
| kimp_btc         | Upbit KRW-BTC 종가 ÷ (Binance BTCUSDT 종가 × USD/KRW) − 1, %          | Upbit · Binance spot · Frankfurter    |
| funding_btcusdt  | 그 UTC 날짜에 정산된 BTCUSDT 펀딩레이트 평균, %                        | Binance USDS-M futures                |
| fng              | Fear & Greed 지수(0~100)                                               | alternative.me                        |
| ethbtc           | Binance ETHBTC 일봉 종가                                               | Binance spot                          |
| taker_btcusdt    | BTCUSDT 일봉 테이커 매수 기준통화량 ÷ 총 기준통화량                    | Binance spot                          |

날짜 키는 **UTC 날짜 = 그 값을 만든 데이터의 날**이다. 소비자(`ExternalSeries.kt`)는 거래일 D 의 게이트에 D−1 값을 쓴다 —
Upbit 일봉의 kst 날짜 D 는 KST 09:00 D ~ 09:00 D+1 = UTC 날짜 D 와 같은 구간이므로 Binance 일봉(UTC openTime D)과 같은 날의 값이다.

환율은 ECB 영업일에만 있어 주말·휴일은 직전 값을 이월한다(김프 계산 안에서). 다른 시계열은 결측 날을 채우지 않는다 —
결측 규약(이월 ≤ 3일, 초과면 실패)은 소비자 쪽 배관 단정이 강제한다.

실행: `python3 scripts/collect_external_series.py --write [--end YYYY-MM-DD]` / 검증: `python3 -m unittest scripts/test_collect_external_series.py`
"""
from __future__ import annotations

import argparse
import json
import pathlib
import statistics
import sys
import time
import urllib.error
import urllib.request
from datetime import date, datetime, timedelta, timezone

OUT_DIR = pathlib.Path(__file__).resolve().parent.parent / "bot/src/test/resources/backtest/external"
START = date(2019, 10, 1)          # 롤링 90일 워밍업을 위해 첫 창(p2020h1 2020-01-23)보다 앞서 시작한다.
FX_LOOKBACK_DAYS = 7               # START 직전 영업일 환율을 이월하기 위한 조회 여유
UPBIT = "https://api.upbit.com/v1"
BINANCE_SPOT = "https://api.binance.com/api/v3"
BINANCE_FUT = "https://fapi.binance.com/fapi/v1"
FRANKFURTER = "https://api.frankfurter.dev/v1"
FNG = "https://api.alternative.me/fng/"
THROTTLE_SEC = 0.13


def _get(url: str, **params):
    last: Exception | None = None
    if params:
        url += "?" + "&".join(f"{k}={v}" for k, v in params.items())
    for attempt in range(4):
        try:
            # Frankfurter 가 Python 기본 UA 를 403 으로 막는다 — 식별 가능한 UA 를 붙인다.
            req = urllib.request.Request(url, headers={"User-Agent": "coin-trading-bot-fixtures/1.0"})
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code in (418, 429):
                time.sleep(2.0 + attempt * 2)
                continue
            sys.exit(f"{url}: HTTP {e.code}")
        except Exception as e:  # noqa: BLE001 — 재시도 후 종료
            time.sleep(1.0)
            last = e
    sys.exit(f"{url}: 실패 ({last})")


def _ms(d: date) -> int:
    return int(datetime(d.year, d.month, d.day, tzinfo=timezone.utc).timestamp() * 1000)


def _utc_date(ms: int) -> date:
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).date()


# ── 순수 계산 (단위테스트 대상) ──────────────────────────────────────────────

def kimp_pct(upbit_krw: float, binance_usdt: float, usd_krw: float) -> float:
    """김치프리미엄 % — 양수면 Upbit 가 비싸다."""
    return (upbit_krw / (binance_usdt * usd_krw) - 1.0) * 100.0


def carry_forward(values: dict[date, float], days: list[date]) -> dict[date, float]:
    """`days` 의 각 날짜에 그날 값이 없으면 직전 값을 준다. 첫 날 이전에 값이 없으면 그 날은 빠진다."""
    out: dict[date, float] = {}
    last: float | None = None
    keyed = sorted(values)
    i = 0
    for d in days:
        while i < len(keyed) and keyed[i] <= d:
            last = values[keyed[i]]
            i += 1
        if last is not None:
            out[d] = last
    return out


def funding_daily(records: list[dict]) -> dict[date, float]:
    """`fundingTime` 의 UTC 날짜별 평균(%). 하루 3건이 정상이지만 건수는 강제하지 않는다 — 소비자가 결측만 본다."""
    by_day: dict[date, list[float]] = {}
    for r in records:
        by_day.setdefault(_utc_date(int(r["fundingTime"])), []).append(float(r["fundingRate"]) * 100.0)
    return {d: statistics.fmean(v) for d, v in by_day.items()}


def taker_ratio(kline: list) -> float:
    """klines 필드 9(테이커 매수 기준통화량) ÷ 필드 5(총 기준통화량). 거래가 없는 날은 0.5(정보 없음)."""
    total = float(kline[5])
    return float(kline[9]) / total if total > 0 else 0.5


def klines_by_day(klines: list[list]) -> dict[date, list]:
    return {_utc_date(int(k[0])): k for k in klines}


def day_range(start: date, end: date) -> list[date]:
    return [start + timedelta(days=i) for i in range((end - start).days + 1)]


def to_records(series: dict[date, float]) -> list[dict]:
    return [{"date": d.isoformat(), "value": round(series[d], 6)} for d in sorted(series)]


# ── 수집 ────────────────────────────────────────────────────────────────────

def fetch_upbit_daily(market: str, start: date, end: date) -> dict[date, float]:
    """`candles/days` 를 `to` 로 과거로 넘기며 종가를 kst 날짜별로 모은다."""
    out: dict[date, float] = {}
    to = datetime(end.year, end.month, end.day, tzinfo=timezone.utc) + timedelta(days=1)
    while True:
        page = _get(f"{UPBIT}/candles/days", market=market, count=200, to=to.strftime("%Y-%m-%dT%H:%M:%SZ"))
        time.sleep(THROTTLE_SEC)
        if not page:
            break
        for c in page:
            d = date.fromisoformat(c["candle_date_time_kst"][:10])
            if start <= d <= end:
                out[d] = float(c["trade_price"])
        oldest = date.fromisoformat(page[-1]["candle_date_time_kst"][:10])
        if oldest <= start:
            break
        to = datetime(oldest.year, oldest.month, oldest.day, tzinfo=timezone.utc)
    return out


def fetch_binance_klines(symbol: str, start: date, end: date) -> list[list]:
    out: list[list] = []
    cursor = _ms(start)
    end_ms = _ms(end + timedelta(days=1)) - 1
    while cursor <= end_ms:
        page = _get(f"{BINANCE_SPOT}/klines", symbol=symbol, interval="1d", startTime=cursor, endTime=end_ms, limit=1000)
        time.sleep(THROTTLE_SEC)
        if not page:
            break
        out.extend(page)
        cursor = int(page[-1][6]) + 1
    return out


def fetch_funding(symbol: str, start: date, end: date) -> list[dict]:
    out: list[dict] = []
    cursor = _ms(start)
    end_ms = _ms(end + timedelta(days=1)) - 1
    while cursor <= end_ms:
        page = _get(f"{BINANCE_FUT}/fundingRate", symbol=symbol, startTime=cursor, endTime=end_ms, limit=1000)
        time.sleep(THROTTLE_SEC)
        if not page:
            break
        out.extend(page)
        cursor = int(page[-1]["fundingTime"]) + 1
    return out


def fetch_fx(start: date, end: date) -> dict[date, float]:
    payload = _get(f"{FRANKFURTER}/{start.isoformat()}..{end.isoformat()}", base="USD", symbols="KRW")
    return {date.fromisoformat(d): float(v["KRW"]) for d, v in payload["rates"].items()}


def fetch_fng() -> dict[date, float]:
    payload = _get(FNG, limit=0, format="json")
    return {_utc_date(int(e["timestamp"]) * 1000): float(e["value"]) for e in payload["data"]}


def build(end: date) -> dict[str, dict]:
    days = day_range(START, end)
    upbit = fetch_upbit_daily("KRW-BTC", START, end)
    btc = klines_by_day(fetch_binance_klines("BTCUSDT", START, end))
    eth = klines_by_day(fetch_binance_klines("ETHBTC", START, end))
    fx = carry_forward(fetch_fx(START - timedelta(days=FX_LOOKBACK_DAYS), end), days)
    funding = funding_daily(fetch_funding("BTCUSDT", START, end))
    fng = {d: v for d, v in fetch_fng().items() if START <= d <= end}

    kimp = {d: kimp_pct(upbit[d], float(btc[d][4]), fx[d]) for d in days if d in upbit and d in btc and d in fx}
    stamp = date.today().isoformat()
    return {
        "kimp_btc": {"unit": "%", "rule": "Upbit KRW-BTC close(kst day) / (Binance BTCUSDT close(utc day) × USD/KRW ECB, weekends carried) − 1",
                     "sources": ["api.upbit.com/v1/candles/days", "api.binance.com/api/v3/klines", "api.frankfurter.dev"], "collected": stamp, "data": to_records(kimp)},
        "funding_btcusdt": {"unit": "%", "rule": "mean of BTCUSDT funding rates settled within the utc day (normally 3)",
                            "sources": ["fapi.binance.com/fapi/v1/fundingRate"], "collected": stamp, "data": to_records(funding)},
        "fng": {"unit": "index", "rule": "alternative.me Fear & Greed daily value", "sources": ["api.alternative.me/fng/"], "collected": stamp, "data": to_records(fng)},
        "ethbtc": {"unit": "BTC", "rule": "Binance ETHBTC 1d close(utc day)", "sources": ["api.binance.com/api/v3/klines"], "collected": stamp,
                   "data": to_records({d: float(k[4]) for d, k in eth.items()})},
        "taker_btcusdt": {"unit": "ratio", "rule": "Binance BTCUSDT 1d takerBuyBaseVolume / volume", "sources": ["api.binance.com/api/v3/klines"], "collected": stamp,
                          "data": to_records({d: taker_ratio(k) for d, k in btc.items()})},
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--end", type=date.fromisoformat, default=date.today() - timedelta(days=1))
    args = ap.parse_args()
    series = build(args.end)
    for name, s in series.items():
        d = s["data"]
        print(f"{name:16s} {len(d):5d} rows  {d[0]['date']} .. {d[-1]['date']}")
    if not args.write:
        print("--write 없이 종료(파일 미저장)")
        return
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for name, s in series.items():
        (OUT_DIR / f"{name}.json").write_text(json.dumps(s, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"저장: {OUT_DIR}")


if __name__ == "__main__":
    main()
