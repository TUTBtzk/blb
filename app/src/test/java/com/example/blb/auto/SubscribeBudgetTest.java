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
 * 拿火券去付，或者一个号把后面几个号该拿的章占掉。干跑已经整套删除，
 * 所以现在没有「先模拟一遍」这层缓冲 —— 这个判据错了就是真花错钱。
 */
public class SubscribeBudgetTest {

    private static String stop(int budget, int estimate) {
        return SubscribeRun.stopBecauseBroke("甲", budget, estimate, 0, 0, 50);
    }

    private static String stop2(int budget, int estimate, int spentToday, int cap) {
        return SubscribeRun.stopBecauseBroke("甲", budget, estimate, spentToday, cap, 50);
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

    /** 每日上限：够买但会超上限，同样换号。 */
    @Test
    public void dailyCapStopsBeforeItIsExceeded() {
        assertNull("40 + 20 = 60，正好到上限，还能买", stop2(100, 20, 40, 60));
        String note = stop2(100, 20, 41, 60);
        assertNotNull(note);
        assertTrue(note.contains("每日上限 60"));
    }

    // ---------- 一趟的说明里不许再出现章数上限 ----------

    /**
     * 用户的原话是「不要限制多少章节…一个号订阅到余额不足再换下一个号」。
     * 这句话要在日志的开场就说清楚，否则看日志的人会以为还有个隐形上限兜着。
     */
    @Test
    public void planSaysThereIsNoChapterLimit() {
        Novel n = new Novel();
        n.title = "测试书";
        n.startChapterNo = 1;
        SubscribeRun.Plan p = new SubscribeRun.Plan(n, 0, 0L);
        String text = p.describe();
        assertTrue("要说明是真实购买", text.contains("真实购买"));
        assertTrue("要说明不限章数", text.contains("不限章数"));
        assertTrue("要说明代券不够就换号", text.contains("换下一个号"));
        assertFalse("不许再提干跑", text.contains("干跑"));
        assertFalse("没有上限就别提上限", text.contains("上限"));

        SubscribeRun.Plan capped = new SubscribeRun.Plan(n, 60, 0L);
        assertTrue("配了每日上限就要写出来", capped.describe().contains("每日上限 60 代券"));
    }

    // ---------- 结果不明＝整趟收工 ----------

    /**
     * 「点了『立即下载』但看不出买成没买成」必须让<b>两个</b>队列都整趟停下。
     *
     * <p>2026-08-24 那次事故就差这一下：失败不算全局失败，于是队列接着换号，
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
