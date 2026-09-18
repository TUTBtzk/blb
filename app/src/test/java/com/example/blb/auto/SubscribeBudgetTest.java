package com.example.blb.auto;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.blb.data.Novel;

import org.junit.Test;

/**
 * 「一个号连着往下订，订到页面说余额不足再换下一个号」这条判据本身。
 *
 * <p>为什么单独钉住：它在真机上要跑一整趟 8 个号才看得到一次，而算错的后果是
 * 拿火券去付，或者一个号把后面几个号该拿的章占掉。
 *
 * <p><b>2026-09-15 第五次反馈之后，这条判据里只剩「今天已花多少」这一件确定的事实。</b>
 * 以前它会拿上一章的实付价当这一章的估价（`estimate`），余额一低于估价就换号；而每章
 * 单价 10~14 代券不等，于是真机上出现「订 1~2 章、余额还剩着就换号」。现在不猜价：
 * 进页面让菠萝包自己说，出现「余额不足，快去充值吧」才算这个号没钱了
 * （见 {@link SubscribeTask} 的 {@code INSUFFICIENT}）。
 */
public class SubscribeBudgetTest {

    /** 今天没花过、也没设上限 → 永远不拦，价格多少都进页面读实付。 */
    private static String stop() {
        return SubscribeRun.stopBecauseBroke("甲", 0, 0);
    }

    private static String stop2(int spentToday, int cap) {
        return SubscribeRun.stopBecauseBroke("甲", spentToday, cap);
    }

    /** 不猜价格：无论余额和单价看起来多悬殊，都要进页面让菠萝包自己说。 */
    @Test
    public void thePageDecidesWhetherThisAccountIsBroke() {
        assertNull("53 券买 20 券的一章，当然接着订", stop());
        assertNull("余额 0 也要进页面 —— 免费章会直接走「已拥有」那条路", stop());
        assertNull("没设上限、今天也没花过，就不许因为估价拦人", stop());
    }

    /** 每日上限是账本里的确定事实：已经花到上限就换号，不再进页面。 */
    @Test
    public void anExhaustedDailyCapStillStopsAccount() {
        String note = stop2(20, 20);
        assertNotNull(note);
        assertTrue(note, note.contains("已达到每日上限 20"));
        assertTrue(note.contains("换下一个号"));
        assertNull("差一券没到上限就不拦 —— 真实单价由页面回答", stop2(19, 20));
        assertNull("上限没设（0）时永远不按上限拦", stop2(999, 0));
    }

    @Test
    public void remainingDailyAllowanceIsIndependentOfThePriceEstimate() {
        assertEquals(0, SubscribeRun.remainingDailyVouchers(20, 20));
        assertEquals(0, SubscribeRun.remainingDailyVouchers(20, 25));
        assertEquals(5, SubscribeRun.remainingDailyVouchers(20, 15));
        assertEquals(-1, SubscribeRun.remainingDailyVouchers(0, 100));
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
