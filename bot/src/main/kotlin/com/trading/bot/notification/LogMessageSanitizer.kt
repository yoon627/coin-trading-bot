package com.trading.bot.notification

/**
 * ERROR 로그를 Discord 로 보내기 전 민감정보를 마스킹한다.
 * (예: UpbitClientImpl 이 외부 API error body 를 그대로 log.error 로 남기는 경로 차단.)
 */
object LogMessageSanitizer {

    private val rules: List<Pair<Regex, String>> = listOf(
        // Authorization: Bearer <token> (base64 의 +/= 포함)
        Regex("(?i)Bearer\\s+[A-Za-z0-9._\\-+/=]+") to "Bearer ***",
        // JWT (eyJ...)
        Regex("eyJ[A-Za-z0-9._\\-+/=]{8,}") to "***JWT***",
        // access_key / secret_key / password = <value> (콜론·등호·공백 구분자, JSON·kv 형태)
        Regex("(?i)(access[_-]?key|secret[_-]?key|password)([\"']?\\s*[:=\\s]\\s*[\"']?)([^\"',\\s}]+)") to "$1$2***",
        // Discord webhook URL 토큰 부분 (host 변형 ptb./canary. 포함)
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
