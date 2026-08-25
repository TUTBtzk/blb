package com.example.blb.auto;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.blb.data.Novel;

import org.junit.Test;

/**
 * 「一个号连着往下订，订到代券不够再换下一个号」这条判据本身。
 *
 * <p>为什么单独钉住：它在真机上要跑一整趟 8 个号才看得到一次，而算错的后果是
 * 一个号把后面几个号该拿的章占掉（干跑），或者拿火券去付（真买）。
 */
public class SubscribeBudgetTest {

    private static String stop(int budget, int estimate) {
        return SubscribeRun.stopBecauseBroke("甲", false, budget, estimate, 0, 0, 50);
    }

    /** 还够就接着往下订 —— 这才是「一个号订到余额不足」而不是「一个号只订一章」。 */
    @Test
    public void enoughVouchersKeepsGoing() {
        assertNull("53 券买 20 券的一章，当然接着订", stop(53, 20));
        assertNull("刚好够也算够", stop(20, 20));
    }

    /** 差一券就该换号，绝不进页面赌一把。 */
    @Test
    public void oneVoucherShortSwitchesAccount() {
        String note = stop(19, 20);
        assertNotNull(note);
        assertTrue("要说清剩多少、要多少", note.contains("只剩 19") && note.contains("约 20"));
        assertTrue(note.contains("换下一个号"));
    }

    /** 估不出价（章节表没登记单价、这个号还没买过一章）就别拦，让菠萝包自己在页面上说。 */
    @Test
    public void unknownPriceIsNotAStopReason() {
        assertNull(stop(0, 0));
        assertNull(stop(15, 0));
    }

    /** 余额读不到（-1）也不拦：页面上的「余额不足」才是硬判据。 */
    @Test
    public void unknownBalanceIsNotAStopReason() {
        assertNull(stop(-1, 20));
    }

    /** 干跑那句话要说明是「模拟扣券」之后的数，否则日志会被当成真的花了券。 */
    @Test
    public void dryRunSaysItIsSimulated() {
        String note = SubscribeRun.stopBecauseBroke("甲", true, 13, 20, 40, 0, 50);
        assertNotNull(note);
        assertTrue(note.contains("模拟扣券后只剩 13"));
    }

    /** 每日上限：够买但会超上限，同样换号。 */
    @Test
    public void dailyCapStopsBeforeItIsExceeded() {
        assertNull("40 + 20 = 60，正好到上限，还能买", stop2(100, 20, 40, 60));
        String note = stop2(100, 20, 41, 60);
        assertNotNull(note);
        assertTrue(note.contains("每日上限 60"));
    }

    private static String stop2(int budget, int estimate, int spentToday, int cap) {
        return SubscribeRun.stopBecauseBroke("甲", false, budget, estimate, spentToday, cap, 50);
    }

    // ---------- 真买保险丝：一趟只准真买这么多章 ----------

    private static SubscribeRun.Plan plan(boolean dryRun, int buyLimit) {
        Novel n = new Novel();
        n.title = "测试书";
        n.startChapterNo = 1;
        return new SubscribeRun.Plan(n, dryRun, 50, 0, 0L, buyLimit);
    }

    /**
     * 真买一章就收工。这是第一次真花券那趟的唯一硬保险：判据万一错位，最多错一章。
     */
    @Test
    public void oneRealBuyReachesTheFuse() {
        SubscribeRun.Plan p = plan(false, 1);
        assertTrue("保险丝装着", p.hasBuyLimit());
        assertFalse("还没买，接着走", p.reachedBuyLimit(0));
        assertTrue("买到一章就够了", p.reachedBuyLimit(1));
        assertTrue("多买了也算到（不该发生，但别漏判）", p.reachedBuyLimit(2));
        assertTrue("这句话要让人看得见上限", p.describe().contains("只准真买 1 章"));
    }

    /** 干跑一分券都不花，不该占用真买额度 —— 否则干跑会在第 1 章就收工，什么都验不出来。 */
    @Test
    public void dryRunIsNeverLimitedByTheFuse() {
        SubscribeRun.Plan p = plan(true, 1);
        assertFalse("干跑那趟没有保险丝", p.hasBuyLimit());
        assertFalse(p.reachedBuyLimit(1));
        assertFalse(p.reachedBuyLimit(999));
        assertFalse("干跑的说明里不该出现保险丝", p.describe().contains("保险丝"));
    }

    /** 0＝不限：确认判定没错、放开真买之后走的就是这条路。 */
    @Test
    public void zeroMeansNoFuse() {
        SubscribeRun.Plan p = plan(false, 0);
        assertFalse(p.hasBuyLimit());
        assertFalse(p.reachedBuyLimit(0));
        assertFalse(p.reachedBuyLimit(100));
    }

    // ---------- 结果不明＝整趟收工 ----------

    /**
     * 「点了『立即下载』但看不出买成没买成」必须让<b>两个</b>队列都整趟停下。
     *
     * <p>2026-08-24 那次事故就差这一下：失败不占真买保险丝的额度，于是队列接着换号，
     * 三个号各点了一次「立即下载」，两个真扣了券（共 40 代券）。券已经可能动了的时候，
     * 唯一安全的动作是停下让人核对余额。
     */
    @Test
    public void unclearMoneyStopsBothQueues() {
        assertTrue("订阅队列要认它", SubscribeQueue.isGlobal(StepRunner.Kind.MONEY_UNCLEAR));
        assertTrue("签到队列的第 3 步也要认它",
                CheckInQueue.isGlobal(StepRunner.Kind.MONEY_UNCLEAR));
        // 对照：单个号超时只是它自己的事，队列该继续。
        assertFalse(SubscribeQueue.isGlobal(StepRunner.Kind.TIMEOUT));
        assertFalse(CheckInQueue.isGlobal(StepRunner.Kind.TIMEOUT));
    }
}
