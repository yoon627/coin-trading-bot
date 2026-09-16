#!/usr/bin/env python3
"""라이브 진입 대조(#190) fixture — 운영 봇이 실제로 돈 기간의 일봉·240/15/5분봉을 **저장소 밖 캐시**에 둔다.

    python3 scripts/collect_live_entry_fixtures.py                 # 미리보기(수집 대상·결측만)
    python3 scripts/collect_live_entry_fixtures.py --write         # 캔들 기록
    python3 scripts/collect_live_entry_fixtures.py --trades a.tsv  # 운영 DB 추출 TSV → trades.json (캔들 수집 없음)

출력: `$BACKTEST_CACHE_DIR/live-entry-2026/{daily,intraday240,intraday15,intraday5}/<market>.json.gz` + `gaps.json` + `trades.json`.
`Regime` 을 늘리지 않는다 — `Regime.entries` 를 순회하는 테스트의 결과가 바뀐다. 소비자는 `LiveEntryResolutionTest`.

라이브 거래(`trades.json`)는 public 저장소에 커밋하지 않는다. 추출 SQL 은 `scripts/sql/live_entry_trades.sql`:
    ./deploy/vultr/deploy.sh ssh  →  cd /opt/app && docker compose exec -T postgres psql -U trading -d trading -A -F $'\\t' < live_entry_trades.sql > live.tsv
"""
import argparse
import gzip
import json
import os
import pathlib
import sys
from datetime import date, timedelta

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from collect_backtest_fixtures import BARS, fetch_window as fetch_daily  # noqa: E402
from collect_intraday_fixtures import fetch_window as fetch_minutes  # noqa: E402

# 운영 `TRADING_TICKERS`(deploy/vultr/.env) — 대조 기간 내 유니버스 자동선정 off.
MARKETS = ["KRW-BTC", "KRW-ETH", "KRW-XRP", "KRW-SOL", "KRW-DOGE", "KRW-ADA", "KRW-AVAX", "KRW-LINK"]
# 라이브 combined 매수 첫 건 2026-07-14, 마지막 2026-09-14. 분봉은 포지션 상태 워밍업으로 2주 앞서 시작한다.
INTRADAY_START, INTRADAY_END = date(2026, 7, 1), date(2026, 9, 14)
# 일봉 200봉의 마지막이 INTRADAY_END — 워밍업(BacktestEngine.MIN_CANDLES=50) 은 2026-07-01 앞에 120봉 남는다.
DAILY_START = INTRADAY_END - timedelta(days=BARS - 1)
UNITS = [240, 15, 5]
DIR = "live-entry-2026"

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
CACHE_ROOT = pathlib.Path(os.environ.get("BACKTEST_CACHE_DIR") or (REPO_ROOT / "backtest-cache"))


def write_gz(path: pathlib.Path, payload) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(path, "wt", encoding="utf-8") as fh:
        fh.write(json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n")


def convert_trades(tsv: pathlib.Path, out: pathlib.Path) -> None:
    rows = []
    declared: int | None = None
    with tsv.open(encoding="utf-8") as fh:
        header = fh.readline().rstrip("\n").split("\t")
        for line in fh:
            line = line.rstrip("\n")
            if not line:
                continue
            if line.startswith("(") and (line.endswith("rows)") or line.endswith("row)")):  # psql 꼬리 "(N rows)"
                declared = int(line[1:].split()[0])
                continue
            fields = line.split("\t")
            # zip 은 필드 수 불일치를 조용히 흡수한다 — reason 에 탭이 섞이면 그 행만 어긋난 채 들어가고 건수 단언은 통과한다.
            if len(fields) != len(header):
                raise SystemExit(f"필드 수 불일치 {len(fields)} ≠ {len(header)}: {line[:60]}…")
            rec = dict(zip(header, fields))
            rows.append({
                "id": int(rec["id"]),
                "market": rec["ticker"],
                "side": rec["side"],
                "price": float(rec["price"]),
                "created_utc": rec["created_utc"],
                "reason": rec.get("reason", ""),
                "pnl_percent": float(rec["pnl_percent"]) if rec.get("pnl_percent") else None,
            })
    if declared is not None and declared != len(rows):
        raise SystemExit(f"행 수 불일치: psql {declared} ≠ 파싱 {len(rows)}")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(rows, ensure_ascii=False, indent=0) + "\n", encoding="utf-8")
    buys = sum(r["side"] == "BUY" for r in rows)
    print(f"trades.json: {len(rows)}행 (BUY {buys} / SELL {len(rows) - buys}) → {out}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true", help="캔들을 캐시에 기록")
    parser.add_argument("--trades", type=pathlib.Path, help="운영 DB 추출 TSV → trades.json 변환만")
    args = parser.parse_args()
    root = CACHE_ROOT / DIR

    if args.trades:
        convert_trades(args.trades, root / "trades.json")
        return

    print(f"캐시 루트: {root}")
    print(f"일봉 {DAILY_START}~{INTRADAY_END} ({BARS}봉) · 분봉 {UNITS} {INTRADAY_START}~{INTRADAY_END} · {len(MARKETS)}마켓")
    gaps: dict[str, dict[str, list[str]]] = {}
    failed: list[str] = []
    for market in MARKETS:
        daily, why = fetch_daily(market, DAILY_START)
        if daily is None:
            failed.append(f"daily {market}: {why}")
            continue
        if args.write:
            write_gz(root / "daily" / f"{market}.json.gz", daily)
        for unit in UNITS:
            candles, why, missing = fetch_minutes(market, INTRADAY_START, INTRADAY_END, unit)
            if candles is None:
                failed.append(f"{unit}m {market}: {why}")
                continue
            if missing:
                gaps.setdefault(f"intraday{unit}", {})[market] = missing
            print(f"  {market} {unit:>3}m {len(candles):>6}봉 결측 {len(missing)}")
            if args.write:
                write_gz(root / f"intraday{unit}" / f"{market}.json.gz", candles)
        print(f"  {market} daily {len(daily)}봉")
    if args.write:
        (root / "gaps.json").write_text(json.dumps(gaps, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    if failed:
        print("실패:\n  " + "\n  ".join(failed))
        raise SystemExit(1)


if __name__ == "__main__":
    main()
