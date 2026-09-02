package com.trading.common.domain

/**
 * 가격이 법정화폐·금에 고정돼 변동성 전략에 무의미한 KRW 마켓. 거래대금 순위에서는 늘 상위라 자동 유니버스가
 * 명시적으로 걸러야 한다. 백테 fixture 선정(`PointInTimeUniverse`)은 수집 시점 재현성 때문에 자기 목록을 따로 든다.
 */
object PeggedAssets {
    val MARKETS: Set<String> = setOf(
        "KRW-USDT", "KRW-USDC", "KRW-USD1", "KRW-USDE", "KRW-USDG", "KRW-USDS", "KRW-RLUSD",
        "KRW-DAI", "KRW-TUSD", "KRW-BUSD", "KRW-PYUSD",
        "KRW-EURC",
        "KRW-XAUT",
    )
}
