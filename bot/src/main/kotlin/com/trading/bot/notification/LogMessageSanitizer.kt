package com.trading.bot.notification

/** ERROR 로그를 Discord 로 보내기 전에 토큰·키·비밀번호를 가린다. */
object LogMessageSanitizer {

    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("(?i)Bearer\\s+[A-Za-z0-9._\\-+/=]+") to "Bearer ***",
        Regex("eyJ[A-Za-z0-9._\\-+/=]{8,}") to "***JWT***",
        Regex("(?i)(access[_-]?key|secret[_-]?key|app[_-]?key|app[_-]?secret|password)([\"']?\\s*[:=\\s]\\s*[\"']?)([^\"',\\s}]+)") to "$1$2***",
        Regex("(https://(?:[\\w-]+\\.)?discord(?:app)?\\.com/api/webhooks/\\d+)/[A-Za-z0-9._\\-]+") to "$1/***",
    )

    fun sanitize(text: String): String {
        var result = text
        for ((regex, replacement) in rules) {
            result = regex.replace(result, replacement)
        }
        return result
    }
}
