package com.trading.research.domain

sealed interface SizingRule {
    data class FixedFraction(val fractionOfEquity: Double) : SizingRule {
        init { require(fractionOfEquity in 0.0..1.0) { "fractionOfEquity must be [0,1]" } }
    }
    data class VolTarget(val annualVol: Double, val lookbackDays: Int = 20) : SizingRule {
        init {
            require(annualVol > 0) { "annualVol must be > 0" }
            require(lookbackDays > 0) { "lookbackDays must be > 0" }
        }
    }
    data class Notional(val amount: Double) : SizingRule {
        init { require(amount > 0) { "amount must be > 0" } }
    }
    object CloseAll : SizingRule
}
