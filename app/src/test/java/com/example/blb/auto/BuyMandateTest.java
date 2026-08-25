package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;

/**
 * 常驻真买授权的判定（{@link BuyMandate#decide}）。
 *
 * <p>为什么非要单测它：它是这个 App 里<b>唯一</b>能在没人按屏幕的情况下开始花钱的那道判据。
 * 用户手指动不了，判错了他既拦不住也关不掉 —— 「过期了还算有效」或者「今天买满了还说准买」
 * 的后果是无人值守地接着花他的代券。而真机上要验一次「到期」得等好几天。
 */
public class BuyMandateTest {

    private static final String TODAY = "2026-08-25";
    private static final long NOW = ymdNoon(2026, 8, 25);

    private static long ymdNoon(int y, int m, int d) {
        Calendar c = Calendar.getInstance();
        c.set(y, m - 1, d, 12, 0, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static BuyMandate decide(long until, int perDay, String noteYmd, int doneToday) {
        return BuyMandate.decide(until, perDay, noteYmd, doneToday, NOW, TODAY);
    }

    /** 默认状态（一次都没授权过）＝不准买，而且说得出为什么。 */
    @Test
    public void noMandateMeansDryRun() {
        BuyMandate m = decide(0L, 0, null, 0);
        assertFalse(m.active);
        assertFalse(m.canBuyNow());
        assertEquals(0, m.chaptersLeft);
        assertTrue("要说清「没授权」而不是只报个 false", m.reason.contains("没有常驻真买授权"));
    }

    /** 有到期时间但额度是 0，等于没授权 —— 不能因为 until 有值就当准买。 */
    @Test
    public void zeroQuotaMeansNoMandate() {
        BuyMandate m = decide(BuyMandate.endOfDay(NOW, 7), 0, TODAY, 0);
        assertFalse(m.canBuyNow());
        assertTrue(m.reason.contains("没有常驻真买授权"));
    }

    /** 过期了就自动失效，不需要任何人去按什么 —— 这是授权敢发出去的前提。 */
    @Test
    public void expiredMandateStopsBuying() {
        BuyMandate m = decide(ymdNoon(2026, 8, 24), 3, TODAY, 0);
        assertFalse("过了 until 一律不准买", m.active);
        assertFalse(m.canBuyNow());
        assertEquals(0, m.chaptersLeft);
        assertTrue(m.reason.contains("过期"));
        assertTrue("要念得出到期时间，好让人知道该不该再授权", m.reason.contains("2026-08-24"));
    }

    /** 到期那一刻之前还算有效 —— 授权按天给，就该到那天结束时才失效。 */
    @Test
    public void validUntilEndOfLastDay() {
        long until = BuyMandate.endOfDay(NOW, 0);
        BuyMandate m = decide(until, 2, TODAY, 0);
        assertTrue("授权最后一天的中午当然还有效", m.canBuyNow());
        assertEquals(2, m.chaptersLeft);
        assertFalse("过了那天 23:59:59 就没了",
                BuyMandate.decide(until, 2, TODAY, 0, until + 1000L, TODAY).canBuyNow());
    }

    /** 当日额度用满：授权还在（active），但今天不准再买了。 */
    @Test
    public void dailyQuotaExhausted() {
        BuyMandate m = decide(BuyMandate.endOfDay(NOW, 7), 2, TODAY, 2);
        assertTrue("授权本身没过期", m.active);
        assertFalse("但今天买满了", m.canBuyNow());
        assertEquals(0, m.chaptersLeft);
        assertEquals(2, m.doneToday);
        assertTrue(m.reason.contains("今天的真买额度用完了"));
    }

    /** 买多了（比如同一天里授权被改小）也不能变成负数额度。 */
    @Test
    public void overshootNeverGoesNegative() {
        BuyMandate m = decide(BuyMandate.endOfDay(NOW, 7), 1, TODAY, 5);
        assertEquals(0, m.chaptersLeft);
        assertFalse(m.canBuyNow());
    }

    /** 额度没用完就按「还剩几章」放行 —— 这个数会被拿去当整趟的真买上限。 */
    @Test
    public void partialQuotaLeavesRemainder() {
        BuyMandate m = decide(BuyMandate.endOfDay(NOW, 7), 3, TODAY, 1);
        assertTrue(m.canBuyNow());
        assertEquals(2, m.chaptersLeft);
        assertTrue(m.reason.contains("还准真买 2 章"));
    }

    /** 记的是昨天的账＝今天额度回满。不然授权发一次就只能用一天。 */
    @Test
    public void quotaResetsNextDay() {
        BuyMandate m = decide(BuyMandate.endOfDay(NOW, 7), 2, "2026-08-24", 2);
        assertTrue("昨天买满了，今天照样准买", m.canBuyNow());
        assertEquals(2, m.chaptersLeft);
        assertEquals("昨天的已买数不算在今天头上", 0, m.doneToday);
    }

    /** 存坏的负数不能变成「凭空多出来的额度」。 */
    @Test
    public void negativeDoneIsTreatedAsZero() {
        BuyMandate m = decide(BuyMandate.endOfDay(NOW, 7), 2, TODAY, -3);
        assertEquals(2, m.chaptersLeft);
        assertEquals(0, m.doneToday);
    }

    /** 授权到「第 N 天」的 23:59:59，而不是差几个小时。 */
    @Test
    public void endOfDayLandsOnLastSecond() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(BuyMandate.endOfDay(NOW, 2));
        assertEquals(2026, c.get(Calendar.YEAR));
        assertEquals(8, c.get(Calendar.MONTH) + 1);
        assertEquals("从 8-25 起 3 天（今天算第 1 天）＝到 8-27 结束", 27,
                c.get(Calendar.DAY_OF_MONTH));
        assertEquals(23, c.get(Calendar.HOUR_OF_DAY));
        assertEquals(59, c.get(Calendar.MINUTE));
        assertEquals(59, c.get(Calendar.SECOND));
        assertEquals(0, c.get(Calendar.MILLISECOND));
    }

    /** 负数天数不能倒着授权到过去（那会让 grant 出来的授权立刻就是过期的）。 */
    @Test
    public void endOfDayClampsNegativeDays() {
        assertEquals(BuyMandate.endOfDay(NOW, 0), BuyMandate.endOfDay(NOW, -5));
    }

    /** 时间戳念给人看，年月日时分都要在，不然「授权到什么时候」等于没说。 */
    @Test
    public void stampIsHumanReadable() {
        assertEquals("2026-08-25 12:00", BuyMandate.stamp(NOW));
    }

    /** 上限本身就是护栏：再长就等于「永久允许无人值守花钱」。 */
    @Test
    public void limitsStayConservative() {
        assertEquals(30, BuyMandate.MAX_DAYS);
        assertEquals(20, BuyMandate.MAX_PER_DAY);
    }
}
