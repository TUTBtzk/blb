package com.example.blb.data;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 账号页那一行余额的文案。
 *
 * <p>2026-08-23 用户报「所有账号都无法识别有多少代券」：代券那半边显示的是「?」，而火券
 * 那半边显示「0」—— 后者是编出来的，来自字段默认值 0，跟真的读到 0 分不开。
 * 两种券现在统一用 -1 表示「没读到过」，界面一律显示「?」。
 */
public class AccountBalanceTextTest {

    @Test
    public void freshAccountKnowsNeitherCurrency() {
        assertEquals("火券 ? / 代券 ?", new Account().balanceText());
    }

    @Test
    public void showsZeroOnlyWhenItWasActuallyRead() {
        Account a = new Account();
        a.lastKnownCoupons = 0;
        a.lastKnownVouchers = 10;
        assertEquals("火券 0 / 代券 10", a.balanceText());
    }

    @Test
    public void oneCurrencyReadDoesNotInventTheOther() {
        Account a = new Account();
        a.lastKnownVouchers = 3;
        assertEquals("火券 ? / 代券 3", a.balanceText());

        Account b = new Account();
        b.lastKnownCoupons = 7;
        assertEquals("火券 7 / 代券 ?", b.balanceText());
    }

    /** 没读到过按 0 券算，别让「不知道」变成负数预算。 */
    @Test
    public void usableCouponsTreatsUnknownAsZero() {
        assertEquals(0, new Account().usableCoupons());
        Account a = new Account();
        a.lastKnownVouchers = 10;
        assertEquals(10, a.usableCoupons());
    }
}
