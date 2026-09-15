package com.example.blb.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 余额的解析。
 *
 * <p>余额读大了会让队列去点真实的确认键，所以「读不到」必须是 -1，绝不能退化成 0。
 */
public class TextsBalanceTest {

    @Test
    public void parseBalanceSplitsFireAndVoucher() {
        Texts.Balance b = Texts.parseBalance("账户余额：12火券/34代券");
        assertEquals(12, b.fire);
        assertEquals(34, b.voucher);
        assertTrue(b.known());
        assertEquals(46, b.usable());
    }

    @Test
    public void parseBalanceHandlesZeroAndSeparators() {
        Texts.Balance zero = Texts.parseBalance("账户余额：0火券/0代券");
        assertEquals(0, zero.fire);
        assertEquals(0, zero.voucher);
        assertTrue(zero.known());
        assertEquals(0, zero.usable());

        Texts.Balance big = Texts.parseBalance("账户余额：1,234 火券 / 5，678 代券");
        assertEquals(1234, big.fire);
        assertEquals(5678, big.voucher);
        assertEquals(6912, big.usable());
    }

    @Test
    public void parseBalanceFallsBackToTheFirstNumberWhenOnlyOneKindIsNamed() {
        Texts.Balance onlyFire = Texts.parseBalance("火券 20");
        // 「火券 20」里数字在前缀之后，命名模式匹配不上，退化成第一个数字当火券。
        assertEquals(20, onlyFire.fire);
        assertEquals(-1, onlyFire.voucher);
        assertEquals(20, onlyFire.usable());

        Texts.Balance labelled = Texts.parseBalance("20火券");
        assertEquals(20, labelled.fire);
        assertEquals(-1, labelled.voucher);

        Texts.Balance voucherOnly = Texts.parseBalance("7代券");
        assertEquals(-1, voucherOnly.fire);
        assertEquals(7, voucherOnly.voucher);
        assertEquals(7, voucherOnly.usable());
    }

    @Test
    public void parseBalanceReportsUnknownInsteadOfZero() {
        for (String text : new String[]{null, "", "账户余额：--", "余额加载中"}) {
            Texts.Balance b = Texts.parseBalance(text);
            assertEquals(-1, b.fire);
            assertEquals(-1, b.voucher);
            assertFalse(b.known());
            assertEquals(-1, b.usable());
            assertEquals("余额读不到", b.describe());
        }
    }

    @Test
    public void balanceFactoryKeepsMinusOneAsUnknown() {
        Texts.Balance both = Texts.balance(3, 4);
        assertEquals(7, both.usable());
        assertEquals("火券 3 / 代券 4", both.describe());

        Texts.Balance half = Texts.balance(-1, 4);
        assertTrue(half.known());
        assertEquals(4, half.usable());
        // 只读到代券时，火券写「?」——写 0 会被当成「真的读到 0」。
        assertEquals("火券 ? / 代券 4", half.describe());

        assertFalse(Texts.balance(-1, -1).known());
    }

    /**
     * 「我的」页那一行余额的数字节点没有 id、没有标签文字，只能靠「整段就是个数字」把它
     * 认出来。所以掺了任何别的字都必须读不到 —— 不然「连签0天」「签到获得8代券」
     * 「7天连签」都会被当成余额。
     */
    @Test
    public void parseWholeCountOnlyAcceptsAPureNumber() {
        assertEquals(0, Texts.parseWholeCount("0"));
        assertEquals(10, Texts.parseWholeCount("10"));
        assertEquals(152, Texts.parseWholeCount(" 152 "));
        assertEquals(1234, Texts.parseWholeCount("1,234"));
        assertEquals(-1, Texts.parseWholeCount("签到获得8代券"));
        assertEquals(-1, Texts.parseWholeCount("连签0天"));
        assertEquals(-1, Texts.parseWholeCount("火券"));
        assertEquals(-1, Texts.parseWholeCount(""));
        assertEquals(-1, Texts.parseWholeCount(null));
    }

    /** 数目大了菠萝包会写成「1.2万」，别读成 1。 */
    @Test
    public void parseWholeCountUnderstandsTenThousands() {
        assertEquals(30_000, Texts.parseWholeCount("3万"));
        assertEquals(12_000, Texts.parseWholeCount("1.2万"));
        assertEquals(12_340, Texts.parseWholeCount("1.234万"));
    }
}
