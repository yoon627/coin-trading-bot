package com.trading.bot.persistence

import com.trading.bot.persistence.entity.ManualInterventionEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository

interface ManualInterventionRepository : R2dbcRepository<ManualInterventionEntity, Long>
