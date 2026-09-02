"""`python3 -m unittest scripts/test_collect_backtest_fixtures.py` — 네트워크 없이 순수 판정 로직만 검증."""
import importlib.util
import pathlib
import unittest
from datetime import date, timedelta

_spec = importlib.util.spec_from_file_location(
    "collect_backtest_fixtures", pathlib.Path(__file__).with_name("collect_backtest_fixtures.py"))
collect = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(collect)

START = date(2024, 1, 1)


def bars(days: list[date]) -> list[dict]:
    return [{"market": "KRW-X", "candle_date_time_kst": f"{d.isoformat()}T09:00:00"} for d in days]


def full_window() -> list[date]:
    return [START + timedelta(days=i) for i in range(collect.BARS)]


class WindowGapTest(unittest.TestCase):
    def test_exact_window_passes(self):
        self.assertIsNone(collect.window_gap(bars(full_window()), START))

    def test_order_does_not_matter(self):
        self.assertIsNone(collect.window_gap(bars(list(reversed(full_window()))), START))

    def test_missing_day_filled_by_older_bar_is_rejected(self):
        days = full_window()
        days[10] = START - timedelta(days=1)  # 거래 없던 날을 API 가 더 오래된 봉으로 채운 모양
        gap = collect.window_gap(bars(days), START)
        self.assertIn("누락 1", gap)
        self.assertIn("잉여 1", gap)

    def test_duplicate_and_missing_reported_together(self):
        days = full_window()
        days[5] = days[6]
        gap = collect.window_gap(bars(days), START)
        self.assertIn("중복 1", gap)
        self.assertIn("누락 1", gap)


class WindowSpanTest(unittest.TestCase):
    def window(self, span_days: int) -> list[dict]:
        newest = START - timedelta(days=1)
        oldest = START - timedelta(days=span_days)
        days = [newest - timedelta(days=i) for i in range(collect.RANK_WINDOW - 1)] + [oldest]
        return bars(days)

    def test_contiguous_30_bars_span_30(self):
        self.assertEqual(collect.window_span_days(self.window(30), START), 30)

    def test_boundary_matches_kotlin_selector(self):
        self.assertLessEqual(collect.window_span_days(self.window(32), START), collect.MAX_WINDOW_SPAN_DAYS)
        self.assertGreater(collect.window_span_days(self.window(33), START), collect.MAX_WINDOW_SPAN_DAYS)


if __name__ == "__main__":
    unittest.main()
