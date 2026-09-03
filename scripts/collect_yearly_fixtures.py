#!/usr/bin/env python3
"""운영 티커 8종의 최근 1년(365봉) 일봉 fixture 수집 — `bot/src/test/resources/backtest/yearly/<market>.json`.

`collect_backtest_fixtures.py`(국면별 200봉·시점 중립 유니버스)와 달리 **마켓을 사용자가 지정**한다 —
이미 운용 중인 티커를 평가하는 것이라 선정 look-ahead 논점이 다르다(생존편향은 남는다, README 참조).

Upbit 는 요청당 200봉이 상한이고 `to` **이전** 봉을 주므로 두 번 받아 잇는다. 마지막 봉은 오늘 09:00 KST
경계 이전의 **완결봉**이어야 한다 — 형성 중인 봉을 넣으면 그 fixture 는 재현이 안 된다.

사용: python3 scripts/collect_yearly_fixtures.py            # 미리보기
      python3 scripts/collect_yearly_fixtures.py --write    # 기록
"""
import argparse
import json
import pathlib
import sys
import time
from datetime import date, datetime, timedelta, timezone

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from collect_backtest_fixtures import THROTTLE_SEC, _get, candle_date, normalize  # noqa: E402

MARKETS = ["KRW-BTC", "KRW-ETH", "KRW-XRP", "KRW-SOL", "KRW-DOGE", "KRW-ADA", "KRW-AVAX", "KRW-LINK"]
BARS = 365
PAGE = 200
# 마지막 봉 날짜(KST). 실행일에서 계산하지 않고 상수로 고정한다 — 재현 가능해야 fixture 다(REGIMES 선례).
END_DATE = date(2026, 9, 2)
OUT_DIR = pathlib.Path(__file__).resolve().parent.parent / "bot/src/test/resources/backtest/yearly"


def to_boundary(end_date: date, now_utc: datetime) -> datetime:
    """END_DATE 봉은 다음날 00:00 UTC(=09:00 KST)에 완결된다. 그 시각을 `to` 로 넘기면 END_DATE 봉이 마지막이다.
    아직 그 시각이 안 됐으면 형성 중인 봉이 섞인다 — README 가 기록한 사고라 여기서 중단한다."""
    boundary = datetime(end_date.year, end_date.month, end_date.day, tzinfo=timezone.utc) + timedelta(days=1)
    if now_utc < boundary:
        raise SystemExit(f"END_DATE={end_date} 봉은 {boundary.isoformat()} 이후에야 완결된다 — 지금은 {now_utc.isoformat()}")
    return boundary


def fetch_year(market: str, to_utc: datetime) -> tuple[list[dict] | None, str]:
    """최신순 365봉. 두 페이지를 받아 잇고 날짜 중복·결측을 검사한다."""
    first, status = _get("candles/days", market=market, count=PAGE, to=to_utc.strftime("%Y-%m-%dT%H:%M:%SZ"))
    time.sleep(THROTTLE_SEC)
    if not first:
        return None, f"1페이지 응답 없음 (HTTP {status})"
    oldest = first[-1]["candle_date_time_utc"]
    second, status = _get("candles/days", market=market, count=PAGE, to=f"{oldest}Z")
    time.sleep(THROTTLE_SEC)
    if not second:
        return None, f"2페이지 응답 없음 (HTTP {status})"
    candles = (first + second)[:BARS]
    if len(candles) != BARS:
        return None, f"{len(candles)}봉 (기대 {BARS}) — 상장 1년 미만?"
    dates = [candle_date(c) for c in candles]
    if len(set(dates)) != BARS:
        return None, "날짜 중복"
    span = (dates[0] - dates[-1]).days + 1
    if span != BARS:
        return None, f"달력 {span}일에 {BARS}봉 — 거래 공백 {span - BARS}일"
    return [normalize(c) for c in candles], ""


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true", help="fixture 파일까지 기록")
    args = parser.parse_args()

    to_utc = to_boundary(END_DATE, datetime.now(timezone.utc))
    print(f"마지막 완결봉: {END_DATE} (to={to_utc.isoformat()}), 구간: {END_DATE - timedelta(days=BARS - 1)} ~ {END_DATE}\n")

    prepared: dict[str, list[dict]] = {}
    for market in MARKETS:
        candles, reason = fetch_year(market, to_utc)
        if candles is None:
            print(f"  ✗ {market}: {reason}")
            continue
        newest, oldest = candle_date(candles[0]), candle_date(candles[-1])
        if newest != END_DATE:
            print(f"  ✗ {market}: 마지막 봉 {newest} ≠ END_DATE {END_DATE}")
            continue
        print(f"  ✓ {market}: {oldest} ~ {newest} ({len(candles)}봉)")
        prepared[market] = candles
    # 하나라도 실패하면 아무것도 쓰지 않는다 — 부분 fixture 는 비교 표본을 조용히 바꾼다.
    if len(prepared) != len(MARKETS):
        print(f"\n{len(prepared)}/{len(MARKETS)} 수집 — 실패가 있어 기록하지 않음")
        sys.exit(1)
    if args.write:
        OUT_DIR.mkdir(parents=True, exist_ok=True)
        for old in OUT_DIR.glob("KRW-*.json"):
            old.unlink()  # MARKETS 가 줄면 옛 파일이 유령 fixture 로 남는다
        for market, candles in prepared.items():
            (OUT_DIR / f"{market}.json").write_text(json.dumps(candles, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(f"\n{len(prepared)}/{len(MARKETS)} 수집" + (" — 기록함" if args.write else " — 미리보기(--write 로 기록)"))


if __name__ == "__main__":
    main()
