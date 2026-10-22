package com.trading.bot.persistence

import com.trading.bot.persistence.entity.UserBrokerKeyEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UserBrokerKeyEntityTest {

    @Test
    fun `toString leaves out every key column`() {
        val entity = UserBrokerKeyEntity(
            id = 7, userId = 3, exchange = "KIS_PAPER",
            appKey = "enc:fake-key", appSecret = "enc:fake-app", accountNo = "enc:fake-acct",
        )

        val text = entity.toString()

        assertThat(text).contains("userId=3", "exchange=KIS_PAPER")
        assertThat(text).doesNotContain("enc:", "fake-key", "fake-app", "fake-acct")
    }
}
