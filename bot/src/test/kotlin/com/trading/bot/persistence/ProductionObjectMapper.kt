package com.trading.bot.persistence

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** 운영 ObjectMapper 와 같은 모양 — application.yml(SNAKE_CASE·모르는 필드 무시) + Spring Boot 기본(ISO 날짜). */
internal fun productionObjectMapper(): ObjectMapper = jacksonObjectMapper()
    .findAndRegisterModules()
    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
