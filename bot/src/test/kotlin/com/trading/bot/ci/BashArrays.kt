package com.trading.bot.ci

/**
 * bash 스크립트에서 `NAME=(` 로 시작하는 배열의 본문. 없거나 닫히지 않으면 null.
 * 줄마다 `#` 뒤를 버려 주석 속 키 이름이나 `)` 를 원소로 읽지 않는다. 따옴표 안이나 단어 중간의 `#` 는 bash 에서는
 * 주석이 아니지만 여기서는 잘라 내므로 배열 원소에 `#` 를 쓰지 않는다.
 */
internal fun bashArrayBody(lines: List<String>, name: String): String? {
    val opener = "$name=("
    val start = lines.indexOfFirst { it.trimStart().startsWith(opener) }
    if (start < 0) return null
    val code = (listOf(lines[start].substringAfter(opener)) + lines.drop(start + 1)).map { it.substringBefore('#') }
    val end = code.indexOfFirst { ')' in it }
    if (end < 0) return null
    return code.take(end + 1).joinToString("\n").substringBefore(')')
}
