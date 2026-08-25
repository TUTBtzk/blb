package com.example.blb.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「只花代券」这条硬约束的唯一判据：选择章节页底部那句「实付」。
 *
 * <p>读错的后果不对称：把「实付5火券+15代券」读成「只花代券」会真的扣掉用户不打算充值的火券；
 * 把能买的读成不能买只是少订一章。所以读不出来必须是「不知道」（-1），
 * 而 {@link Texts.Payment#vouchersOnly()} 必须把「不知道」当成不能买。
 */
public class TextsPaymentTest {

    @Test
    public void bothKindsAreSplit() {
        Texts.Payment p = Texts.parsePayment("实付5火券+15代券");
        assertEquals(5, p.fire);
        assertEquals(15, p.voucher);
        assertTrue(p.known());
        // 实付里有火券 —— 用户不充值火券，这一章必须放弃。
        assertFalse(p.vouchersOnly());
    }

    /** 只写了代券，就是「一张火券都不动」，这才是允许下手的唯一形状。 */
    @Test
    public void voucherOnlyWordingMeansNoFireSpent() {
        Texts.Payment p = Texts.parsePayment("实付15代券");
        assertEquals(0, p.fire);
        assertEquals(15, p.voucher);
        assertTrue(p.vouchersOnly());

        Texts.Payment zero = Texts.parsePayment("实付0火券+12代券");
        assertEquals(0, zero.fire);
        assertEquals(12, zero.voucher);
        assertTrue(zero.vouchersOnly());
    }

    /** 余额那一行不是实付，绝不能被当成实付读 —— 不然「0火券/15代券」会被当成「买得起」。 */
    @Test
    public void balanceLineIsNotAPaymentLine() {
        for (String text : new String[]{
                "账户余额：0火券/15代券", "帐户余额：0 火券/ 15 代券", "需 12 火券"}) {
            Texts.Payment p = Texts.parsePayment(text);
            assertFalse(text, p.known());
            assertFalse(text, p.vouchersOnly());
            assertEquals("实付读不到", p.describe());
        }
    }

    /** 「订阅全部 612 火券」那颗按钮的文案也没有「实付」二字，同样读不到。 */
    @Test
    public void subscribeAllButtonIsNotAPaymentLine() {
        assertFalse(Texts.parsePayment("订阅全部 612 火券").known());
        assertFalse(Texts.parsePayment("订阅本章 实付5火券+15代券").vouchersOnly());
    }

    @Test
    public void unreadableIsUnknownNotZero() {
        for (String text : new String[]{null, "", "立即下载", "实付"}) {
            Texts.Payment p = Texts.parsePayment(text);
            assertEquals(-1, p.fire);
            assertEquals(-1, p.voucher);
            assertFalse(p.known());
            assertFalse(p.vouchersOnly());
        }
    }

    /** 「帐户余额」的「帐」字也要认 —— 菠萝包两种写法都出现过。 */
    @Test
    public void balanceAcceptsBothSpellingsOfZhang() {
        Texts.Balance a = Texts.parseBalance("账户余额：0火券/15代券");
        Texts.Balance b = Texts.parseBalance("帐户余额：0 火券/ 15 代券");
        assertEquals(0, a.fire);
        assertEquals(15, a.voucher);
        assertEquals(0, b.fire);
        assertEquals(15, b.voucher);
        assertTrue(b.known());
    }
}
