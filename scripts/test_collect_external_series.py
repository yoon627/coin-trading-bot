"""`python3 -m unittest scripts/test_collect_external_series.py` — 네트워크 없이 순수 계산만 검증."""
import importlib.util
import pathlib
import unittest
from datetime import date

_spec = importlib.util.spec_from_file_location(
    "collect_external_series", pathlib.Path(__file__).with_name("collect_external_series.py"))
collect = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(collect)

UTC_2020_01_01_MS = 1577836800000
DAY_MS = 86_400_000


class KimpTest(unittest.TestCase):
    def test_premium_is_relative_to_fx_converted_binance_price(self):
        # Upbit 12,000,000 KRW vs Binance 10,000 USDT × 1,150 = 11,500,000 → +4.35%
        self.assertAlmostEqual(collect.kimp_pct(12_000_000, 10_000, 1_150), 4.347826, places=5)

    def test_zero_when_prices_agree(self):
        self.assertAlmostEqual(collect.kimp_pct(11_500_000, 10_000, 1_150), 0.0)


class CarryForwardTest(unittest.TestCase):
    def test_weekend_gets_friday_value_and_pre_start_gap_is_dropped(self):
        fri, sat, sun, mon = date(2020, 1, 3), date(2020, 1, 4), date(2020, 1, 5), date(2020, 1, 6)
        rates = {fri: 1160.0, mon: 1165.0}
        out = collect.carry_forward(rates, [date(2020, 1, 2), fri, sat, sun, mon])
        self.assertEqual(out, {fri: 1160.0, sat: 1160.0, sun: 1160.0, mon: 1165.0})


class FundingTest(unittest.TestCase):
    def test_groups_by_utc_day_and_averages_in_percent(self):
        records = [
            {"fundingTime": UTC_2020_01_01_MS, "fundingRate": "0.0001"},
            {"fundingTime": UTC_2020_01_01_MS + 8 * 3_600_000, "fundingRate": "0.0003"},
            {"fundingTime": UTC_2020_01_01_MS + 16 * 3_600_000, "fundingRate": "-0.0001"},
            {"fundingTime": UTC_2020_01_01_MS + DAY_MS, "fundingRate": "0.0002"},
        ]
        out = collect.funding_daily(records)
        self.assertAlmostEqual(out[date(2020, 1, 1)], 0.01)   # (0.01 + 0.03 − 0.01) / 3 %
        self.assertAlmostEqual(out[date(2020, 1, 2)], 0.02)


class KlineTest(unittest.TestCase):
    def kline(self, volume: str, taker: str) -> list:
        return [UTC_2020_01_01_MS, "7200", "7300", "7100", "7250", volume, UTC_2020_01_01_MS + DAY_MS - 1, "0", 10, taker, "0", "0"]

    def test_taker_ratio_uses_base_volumes(self):
        self.assertAlmostEqual(collect.taker_ratio(self.kline("1000", "600")), 0.6)

    def test_taker_ratio_is_half_when_no_volume(self):
        self.assertEqual(collect.taker_ratio(self.kline("0", "0")), 0.5)

    def test_klines_keyed_by_utc_open_date(self):
        by_day = collect.klines_by_day([self.kline("1", "1")])
        self.assertEqual(list(by_day), [date(2020, 1, 1)])


class RecordsTest(unittest.TestCase):
    def test_records_are_ascending_and_iso_dated(self):
        out = collect.to_records({date(2020, 1, 2): 2.0, date(2020, 1, 1): 1.0})
        self.assertEqual(out, [{"date": "2020-01-01", "value": 1.0}, {"date": "2020-01-02", "value": 2.0}])


if __name__ == "__main__":
    unittest.main()
