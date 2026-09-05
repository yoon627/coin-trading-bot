#!/usr/bin/env python3
"""일중(240분) 캔들 fixture 수집 — `bot/src/test/resources/backtest/intraday240/<regime>/<market>.json`.

**왜 필요한가**: 이 봇은 익일 09:00 KST 에 전량 매도한다. 그런데 Upbit 일봉 경계가 곧 09:00 이라
일봉 종가 ≡ 익일 일봉 시가이고(KRW-BTC 364쌍 평균 |차| 0.012%), 따라서 **일봉 데이터는 09:00 이외의
어떤 시각에 대한 정보도 담고 있지 않다**. "왜 하필 09:00 인가" 는 일중봉 없이는 원리적으로 측정 불가다.

**왜 240분인가**: 청산 시각 H 의 체결가는 그 시각 봉의 `open` 이고, 이 값은 240분봉이든 1분봉이든
동일하다. granularity 가 바꾸는 것은 후보 시각의 개수(240m → KST 01/05/09/13/17/21 6개)와
경계 **사이** 가격게이트 판정 횟수뿐이다. 1분봉은 8마켓 1년에 2.5GB·67,104요청이면서 시각 축에
추가 정보를 주지 않는다.

마켓·구간은 기존 fixture 를 그대로 따른다(`BacktestFixtures.MARKETS_BY_REGIME` · `YearlyFixtures.MARKETS`) —
일봉과 다른 유니버스를 쓰면 일중 결과를 일봉 측정과 나란히 놓을 수 없다.

사용: python3 scripts/collect_intraday_fixtures.py            # 미리보기
      python3 scripts/collect_intraday_fixtures.py --write    # 기록
"""
import argparse
import json
import pathlib
import sys
import time
from datetime import date, datetime, timedelta, timezone

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from collect_backtest_fixtures import THROTTLE_SEC, _get  # noqa: E402
from collect_yearly_fixtures import MARKETS as YEARLY_MARKETS  # noqa: E402

UNIT = 240  # 분. KST 격자 = 09/13/17/21/01/05
PAGE = 200
BARS_PER_DAY = 24 * 60 // UNIT
# 거래소 측 공백은 실재한다 — 2023-12-04 01:00 KST 봉은 8마켓 전부에 없다(bull 구간, 실측).
# 결측을 실패로 두면 fixture 를 아예 못 만들고, 무시하면 청산 경계가 조용히 다음 봉으로 밀린다.
# 그래서 **허용하되 목록으로 남기고**, 소비자가 그 시각을 경계로 쓰는 거래를 제외하게 한다.
MAX_MISSING_RATIO = 0.005

# 일봉 fixture 와 같은 구간·같은 로스터. 값은 `BacktestFixtures.MARKETS_BY_REGIME` 과 `README.md` 의 산출물이다.
WINDOWS: dict[str, tuple[date, date, list[str]]] = {
    "yearly": (date(2025, 9, 3), date(2026, 9, 2), YEARLY_MARKETS),
    "bear": (date(2026, 1, 31), date(2026, 8, 18),
             ["KRW-XRP", "KRW-BTC", "KRW-ETH", "KRW-AXS", "KRW-DATA", "KRW-ENSO", "KRW-SOL", "KRW-BERA"]),
    "bull": (date(2023, 11, 23), date(2024, 6, 9),
             ["KRW-GAS", "KRW-XRP", "KRW-BTC", "KRW-SOL", "KRW-ARK", "KRW-MINA", "KRW-BLUR", "KRW-POLYX"]),
    "p2024h2": (date(2024, 6, 10), date(2024, 12, 26), None),
    "p2025h1": (date(2025, 1, 1), date(2025, 7, 19), None),
    # 비중첩 7창(2020-01~2023-11). 일봉 fixture 와 같은 구간·같은 로스터를 쓴다 —
    # 시각·경로 의존 게이트를 일봉으로 판정하면 안 되므로 신규 국면도 일중봉이 함께 있어야 한다.
    "p2020h1": (date(2020, 1, 23), date(2020, 8, 9), None),
    "p2020h2": (date(2020, 8, 10), date(2021, 2, 25), None),
    "p2021h1": (date(2021, 2, 26), date(2021, 9, 13), None),
    "p2021h2": (date(2021, 9, 14), date(2022, 4, 1), None),
    "p2022h1": (date(2022, 4, 2), date(2022, 10, 18), None),
    "p2022h2": (date(2022, 10, 19), date(2023, 5, 6), None),
    "p2023h1": (date(2023, 5, 7), date(2023, 11, 22), None),
}
OUT_ROOT = pathlib.Path(__file__).resolve().parent.parent / "bot/src/test/resources/backtest/intraday240"
FIXTURE_ROOT = OUT_ROOT.parent


def rosters_from_daily(regime: str) -> list[str]:
    """일봉 fixture 디렉토리에서 로스터를 읽는다 — 목록을 두 벌 적으면 반드시 어긋난다."""
    d = FIXTURE_ROOT / regime
    return sorted(p.stem for p in d.glob("KRW-*.json"))


def normalize(c: dict) -> dict:
    """일봉 `normalize` 와 같은 규약이되 **`candle_date_time_utc` 를 유지**한다 —
    `M1ReplayEngine` 이 `LocalDateTime.parse(candleDateTimeUtc)` 를 부르므로 빼면 전건 예외가 난다."""
    def compact(v):
        return int(v) if float(v).is_integer() else v
    return {
        "market": c["market"],
        "candle_date_time_utc": c["candle_date_time_utc"],
        "candle_date_time_kst": c["candle_date_time_kst"],
        "opening_price": compact(c["opening_price"]),
        "high_price": compact(c["high_price"]),
        "low_price": compact(c["low_price"]),
        "trade_price": compact(c["trade_price"]),
        "candle_acc_trade_volume": compact(round(c["candle_acc_trade_volume"], 4)),
    }


def fetch_window(market: str, start: date, end: date) -> tuple[list[dict] | None, str, list[str]]:
    """[start 09:00 KST, end+1 09:00 KST) 의 240분봉을 최신순으로. 중복은 실패, 결측은 목록으로 돌려준다."""
    # 구간 끝 다음날 00:00 UTC = 09:00 KST — 그 시각 **이전** 봉을 받으면 end 일의 마지막 봉까지다.
    to_utc = datetime(end.year, end.month, end.day, tzinfo=timezone.utc) + timedelta(days=1)
    begin_utc = datetime(start.year, start.month, start.day, tzinfo=timezone.utc)
    expected = int((to_utc - begin_utc).total_seconds() // 60 // UNIT)

    out: list[dict] = []
    cursor = to_utc
    while len(out) < expected:
        page, status = _get(f"candles/minutes/{UNIT}", market=market, count=PAGE,
                            to=cursor.strftime("%Y-%m-%dT%H:%M:%SZ"))
        time.sleep(THROTTLE_SEC)
        if not page:
            return None, f"응답 없음 (HTTP {status}, {len(out)}/{expected}봉)", []
        out.extend(page)
        # `page.size < PAGE` 로 끊으면 거래 공백이 데이터 끝으로 오인돼 조용히 표본이 잘린다 —
        # 커서를 마지막 봉 시각으로 강제 전진시키고 구간 시작에 닿을 때만 멈춘다.
        oldest = datetime.strptime(page[-1]["candle_date_time_utc"], "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc)
        if oldest <= begin_utc:
            break
        if cursor == oldest:
            return None, f"커서 정체 {oldest.isoformat()} ({len(out)}/{expected}봉)", []
        cursor = oldest

    kept = [c for c in out
            if begin_utc <= datetime.strptime(c["candle_date_time_utc"], "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc) < to_utc]
    kept.sort(key=lambda c: c["candle_date_time_utc"], reverse=True)
    stamps = [c["candle_date_time_utc"] for c in kept]
    if len(set(stamps)) != len(stamps):
        return None, "봉 시각 중복", []
    grid = []
    t = begin_utc
    while t < to_utc:
        grid.append(t.strftime("%Y-%m-%dT%H:%M:%S"))
        t += timedelta(minutes=UNIT)
    missing = [g for g in grid if g not in set(stamps)]
    if len(missing) > expected * MAX_MISSING_RATIO:
        return None, f"결측 {len(missing)}봉 / {expected} (허용 {MAX_MISSING_RATIO:.1%} 초과)", missing
    return [normalize(c) for c in kept], "", missing


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true", help="fixture 파일까지 기록")
    parser.add_argument("--only", help="이 구간만 (쉼표 구분). 신규 구간 추가 시 지정해 기존 fixture 재수집을 피한다")
    args = parser.parse_args()

    now = datetime.now(timezone.utc)
    prepared: dict[str, dict[str, list[dict]]] = {}
    gaps: dict[str, dict[str, list[str]]] = {}
    failures: list[str] = []
    for regime, (start, end, markets) in WINDOWS.items():
        if args.only and regime not in set(args.only.split(",")):
            continue
        boundary = datetime(end.year, end.month, end.day, tzinfo=timezone.utc) + timedelta(days=1)
        if now < boundary:
            raise SystemExit(f"{regime}: 마지막 봉은 {boundary.isoformat()} 이후에야 완결된다")
        roster = markets or rosters_from_daily(regime)
        print(f"[{regime}] {start} ~ {end}, 마켓 {len(roster)}, 기대 {(boundary - datetime(start.year, start.month, start.day, tzinfo=timezone.utc)).days * BARS_PER_DAY}봉/마켓")
        got: dict[str, list[dict]] = {}
        for market in roster:
            candles, reason, missing = fetch_window(market, start, end)
            if candles is None:
                print(f"  ✗ {market}: {reason}")
                failures.append(f"{regime}/{market}: {reason}")
                continue
            gap = f" 결측 {len(missing)}봉" if missing else ""
            print(f"  ✓ {market}: {len(candles)}봉{gap} ({candles[-1]['candle_date_time_kst']} ~ {candles[0]['candle_date_time_kst']})")
            got[market] = candles
            gaps.setdefault(regime, {})[market] = missing
        prepared[regime] = got

    # 하나라도 실패하면 아무것도 쓰지 않는다 — 부분 fixture 는 비교 표본을 조용히 바꾼다(일봉 수집기와 같은 규약).
    if failures:
        print(f"\n실패 {len(failures)}건 — 기록하지 않음")
        for f in failures:
            print(f"  {f}")
        sys.exit(1)
    total = sum(len(c) for g in prepared.values() for c in g.values())
    if args.write:
        for regime, got in prepared.items():
            d = OUT_ROOT / regime
            d.mkdir(parents=True, exist_ok=True)
            for old in d.glob("KRW-*.json"):
                old.unlink()
            for market, candles in got.items():
                (d / f"{market}.json").write_text(json.dumps(candles, ensure_ascii=False, separators=(",", ":")) + "\n")
        # 결측 시각은 fixture 옆에 함께 커밋한다 — 청산 경계가 그 시각이면 그 거래는 모든 팔에서 제외해야 한다.
        (OUT_ROOT / "gaps.json").write_text(json.dumps(gaps, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    print(f"\n총 {total:,}봉" + (" — 기록함" if args.write else " — 미리보기(--write 로 기록)"))


if __name__ == "__main__":
    main()
