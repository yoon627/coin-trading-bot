package com.trading.bot.engine

/**
 * Upbit KRW 마켓 호가단위 — **2026-09-05 전 KRW 마켓 287개 호가창에서 실측**한 밴드다.
 *
 * 백테는 청산 체결가를 임계선의 실수값 그대로 쓴다(`IntrabarExitModel` 의 무슬리피지 가정). 실거래는 호가 격자
 * 위에서만 체결되므로, **틱이 굵은 저가 코인에서는 그 반올림만으로 트레일링 이익이 사라질 수 있다** —
 * 100~147원 구간의 틱 1원은 가격의 **최대 1.0%** 이고, 트레일링 청산의 이익 중앙값이 +0.99%p 다.
 *
 * ⚠️ 이 표는 **현행**이다. Upbit 은 호가단위를 바꿔 왔고(1,000~10,000원 구간이 예전 5원 → 실측 1원),
 * fixture 구간(2023~2026)에 다른 표가 적용됐을 수 있다. 과거 표가 더 굵었다면 실제 영향은 여기 계산보다 **크다**.
 * 338,000원 초과 구간은 그 사이 상장 마켓이 없어 관측되지 않았고, 운영 8종에는 해당이 없다(BTC·ETH 는 1,000원 밴드 실측).
 */
internal object UpbitTickSize {

    fun of(price: Double): Double = when {
        price < 0.01 -> 0.00001
        price < 1.0 -> 0.001
        price < 10.0 -> 0.01
        price < 100.0 -> 0.1
        price < 5_000.0 -> 1.0
        price < 10_000.0 -> 5.0
        price < 100_000.0 -> 10.0
        price < 500_000.0 -> 100.0
        price < 1_000_000.0 -> 500.0
        else -> 1000.0
    }

    /** 매도 체결가는 격자 아래로 내려간다 — 임계선에 걸린 매도는 그 아래 호가에서만 체결된다(우리에게 불리한 쪽). */
    fun roundSellDown(price: Double): Double {
        val tick = of(price)
        return Math.floor(price / tick) * tick
    }
}
