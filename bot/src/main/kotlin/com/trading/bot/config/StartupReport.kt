package com.trading.bot.config

// 규칙이 받는 가장 긴 코드(31자)보다 넉넉하게. 값을 통째로 붙여 넣은 항목이 Discord 로 그대로 나가지 않게 한다.
private const val REPORTED_ENTRY_MAX_LENGTH = 40

/** 기동 보고 ERROR 에 싣는 설정 항목. ERROR 는 Discord 로 나간다. */
internal fun reportableEntries(entries: List<String>): List<String> = entries.map { it.take(REPORTED_ENTRY_MAX_LENGTH) }
