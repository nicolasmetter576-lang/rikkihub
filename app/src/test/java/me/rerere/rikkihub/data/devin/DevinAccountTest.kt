package me.rerere.rikkihub.data.devin

import me.rerere.ai.provider.providers.devin.DevinApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevinAccountTest {
    @Test
    fun `disabled and invalid accounts are not usable`() {
        assertFalse(account(enabled = false).isUsable())
        assertFalse(account(status = DevinAccountStatus.INVALID).isUsable())
        assertTrue(account().isUsable())
    }

    @Test
    fun `exhausted quota does not make an account unusable`() {
        // 免费模型不计配额: 配额只做展示, 是否拒绝由上游决定
        assertTrue(account(daily = 0, dailyResetAt = 2_000_000_000).isUsable())
        assertTrue(account(weekly = 0, weeklyResetAt = 0).isUsable())
        assertTrue(account(daily = 0, weekly = 0).isUsable())
    }

    @Test
    fun `round robin skips disabled and invalid accounts but not exhausted ones`() {
        val accounts = listOf(
            account(id = "disabled", enabled = false),
            account(id = "invalid", status = DevinAccountStatus.INVALID),
            account(id = "exhausted", daily = 0, dailyResetAt = 2_000_000_000),
            account(id = "available"),
        )

        assertEquals(2, selectDevinAccountIndex(accounts, startIndex = 0))
        assertEquals(3, selectDevinAccountIndex(accounts, startIndex = 3))
        assertNull(selectDevinAccountIndex(accounts.take(2), startIndex = 0))
        assertNull(selectDevinAccountIndex(emptyList(), startIndex = 0))
    }

    @Test
    fun `round robin wraps around`() {
        val accounts = listOf(account(id = "a"), account(id = "b"))
        assertEquals(0, selectDevinAccountIndex(accounts, startIndex = 0))
        assertEquals(1, selectDevinAccountIndex(accounts, startIndex = 1))
        assertEquals(0, selectDevinAccountIndex(accounts, startIndex = 2))
    }

    @Test
    fun `cooling down accounts are skipped first and used only as a fallback`() {
        val accounts = listOf(account(id = "a"), account(id = "b"), account(id = "c"))
        val coolingA: (DevinAccount) -> Boolean = { it.id == "a" }
        assertEquals(1, selectDevinAccountIndex(accounts, startIndex = 0, isCoolingDown = coolingA))

        // 全部冷却: 默认仍返回轮询到的第一个可用账户 (交给上游判断)
        assertEquals(2, selectDevinAccountIndex(accounts, startIndex = 2, isCoolingDown = { true }))
        // 换号重试时不回退
        assertNull(
            selectDevinAccountIndex(
                accounts,
                startIndex = 0,
                isCoolingDown = { true },
                fallbackToCoolingDown = false,
            )
        )
        // 停用账户即使不在冷却也不会被选中
        val withDisabled = listOf(account(id = "a"), account(id = "off", enabled = false))
        assertEquals(
            0,
            selectDevinAccountIndex(withDisabled, startIndex = 1, isCoolingDown = coolingA)
        )
    }

    @Test
    fun `quota exhaustion is detected only for explicit daily or weekly 429s`() {
        fun period(code: Int, message: String) =
            devinQuotaExhaustionPeriod(DevinApiException(code, message))

        assertEquals(
            DevinQuotaPeriod.DAILY,
            period(429, "devin upstream error (failed_precondition): Your daily usage quota has been exhausted."),
        )
        assertEquals(DevinQuotaPeriod.WEEKLY, period(429, "YOUR WEEKLY USAGE QUOTA HAS BEEN EXHAUSTED."))
        assertEquals(
            DevinQuotaPeriod.WEEKLY,
            period(429, "Your daily and weekly usage quota has been exhausted."),
        )
        assertNull(period(429, "User monthly ACU quota exhausted (trace ID: 12345)"))
        assertNull(period(429, "Rate limit exceeded. Please slow down."))
        assertNull(period(429, "Your weekly usage quota is nearly used up."))
        assertNull(period(429, "Usage quota exhausted."))
        assertNull(period(400, "Your daily usage quota has been exhausted."))
        assertNull(devinQuotaExhaustionPeriod(IllegalStateException("Your daily usage quota has been exhausted.")))
    }

    @Test
    fun `cooldown lasts until a plausible reset time or falls back to an hour`() {
        val now = 1_500_000_000_000L
        val inThreeHours = now / 1000 + 3 * 3600
        val inThirtyDays = now / 1000 + 30L * 24 * 3600
        val past = now / 1000 - 60

        assertEquals(
            inThreeHours * 1000,
            account(dailyResetAt = inThreeHours).quotaCooldownUntil(DevinQuotaPeriod.DAILY, now),
        )
        assertEquals(
            inThreeHours * 1000,
            account(weeklyResetAt = inThreeHours).quotaCooldownUntil(DevinQuotaPeriod.WEEKLY, now),
        )
        // 周期对不上 / 已过 / 太远 / 未知 → 一小时
        val fallback = now + DEVIN_QUOTA_FALLBACK_COOLDOWN_MILLIS
        assertEquals(fallback, account(weeklyResetAt = inThreeHours).quotaCooldownUntil(DevinQuotaPeriod.DAILY, now))
        assertEquals(fallback, account(dailyResetAt = past).quotaCooldownUntil(DevinQuotaPeriod.DAILY, now))
        assertEquals(fallback, account(dailyResetAt = inThirtyDays).quotaCooldownUntil(DevinQuotaPeriod.DAILY, now))
        assertEquals(fallback, account().quotaCooldownUntil(DevinQuotaPeriod.DAILY, now))
    }

    @Test
    fun `cooldown key ignores the devin prefix and case`() {
        assertEquals("claude-opus-5-5", devinCooldownKey(" Devin/Claude-Opus-5-5 "))
        assertEquals("model_private_11", devinCooldownKey("MODEL_PRIVATE_11"))
    }

    private fun account(
        id: String = "account",
        enabled: Boolean = true,
        status: DevinAccountStatus = DevinAccountStatus.ACTIVE,
        daily: Long = 100,
        weekly: Long = 100,
        dailyResetAt: Long = 0,
        weeklyResetAt: Long = 0,
    ) = DevinAccount(
        id = id,
        sessionToken = "devin-session-token\$token",
        enabled = enabled,
        status = status,
        dailyQuotaRemainingPercent = daily,
        weeklyQuotaRemainingPercent = weekly,
        dailyQuotaResetAt = dailyResetAt,
        weeklyQuotaResetAt = weeklyResetAt,
    )
}
