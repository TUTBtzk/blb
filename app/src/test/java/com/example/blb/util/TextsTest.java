package com.example.blb.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;

/** 火券文本解析与 CSV 转义：这两处出错会直接把账本记歪。 */
public class TextsTest {

    @Test
    public void parseCountPullsTheFirstNumberOut() {
        assertEquals(1234, Texts.parseCount("火券：1,234"));
        assertEquals(1234, Texts.parseCount("余额 1234 券"));
        assertEquals(1234, Texts.parseCount("1，234"));
        assertEquals(0, Texts.parseCount("0"));
        assertEquals(12, Texts.parseCount("12张券 34积分"));
    }

    @Test
    public void parseCountReturnsMinusOneWhenThereIsNoNumber() {
        assertEquals(-1, Texts.parseCount(null));
        assertEquals(-1, Texts.parseCount(""));
        assertEquals(-1, Texts.parseCount("火券：--"));
    }

    @Test
    public void parseCountClampsInsteadOfOverflowing() {
        assertEquals(Integer.MAX_VALUE, Texts.parseCount("99999999999"));
    }

    @Test
    public void isBlankTreatsWhitespaceAsEmpty() {
        assertTrue(Texts.isBlank(null));
        assertTrue(Texts.isBlank("   "));
        assertFalse(Texts.isBlank(" x "));
    }

    @Test
    public void csvCellQuotesOnlyWhenNeeded() {
        assertEquals("", Texts.csvCell(null));
        assertEquals("普通", Texts.csvCell("普通"));
        assertEquals("\"a,b\"", Texts.csvCell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", Texts.csvCell("say \"hi\""));
        assertEquals("\"两\n行\"", Texts.csvCell("两\n行"));
    }

    @Test
    public void ymdIsLocalDateWithZeroPadding() {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.MARCH, 7, 1, 2, 3);
        assertEquals("2026-03-07", Texts.ymd(c.getTimeInMillis()));
        assertEquals(Texts.ymd(System.currentTimeMillis()), Texts.todayYmd());
    }
}
