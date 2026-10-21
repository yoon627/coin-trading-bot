package com.trading.bot.notification

/** ERROR 로그를 Discord 로 보내기 전에 토큰·키·비밀번호를 가린다. */
object LogMessageSanitizer {

    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("(?i)Bearer\\s+[A-Za-z0-9._\\-+/=]+") to "Bearer ***",
        Regex("eyJ[A-Za-z0-9._\\-+/=]{8,}") to "***JWT***",
        Regex("(?i)(access[_-]?key|secret[_-]?key|app[_-]?key|app[_-]?secret|password)([\"']?\\s*[:=\\s]\\s*[\"']?)([^\"',\\s}]+)") to "$1$2***",
        Regex("(https://(?:[\\w-]+\\.)?discord(?:app)?\\.com/api/webhooks/\\d+)/[A-Za-z0-9._\\-]+") to "$1/***",
        // KIS 토큰 발급 응답. 만료 시각 키(access_token_token_expired)는 값이 비밀이 아니라 건드리지 않는다.
        Regex("(?i)(access[_-]?token)([\"']?\\s*[:=]\\s*[\"']?)([^\"',\\s}&]+)") to "$1$2***",
        // KIS 계좌번호. 쿼리(CANO=…)·JSON("CANO":"…") 에는 앞 8자리만, 사람이 적은 곳에는 8-2 형태 전체가 온다.
        Regex("(?i)\\b(cano)([\"']?\\s*[:=]\\s*[\"']?)([^\"',\\s}&]+)") to "$1$2***",
        // 앞뒤가 단어 문자·'-' 면 UUID 일부(12345678-90ab-…)라 건드리지 않는다.
        Regex("(?<![\\w-])\\d{8}-\\d{2}(?![\\w-])") to "***",
    )

    fun sanitize(text: String): String {
        var result = text
        for ((regex, replacement) in rules) {
            result = regex.replace(result, replacement)
        }
        return result
    }
}
