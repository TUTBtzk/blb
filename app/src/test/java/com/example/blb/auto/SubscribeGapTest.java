package com.example.blb.auto;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「一章都不许跳过」这条规则本身（{@link SubscribeRun.Settled}）以及跑完的两句自检
 * （{@link SubscribeRun#stuckNote}、{@link SubscribeRun#gapNote}）。
 *
 * <p>用户 2026-09-03 把订阅流程说死了：「对比要订阅的章节能不能订阅，能就订阅，不能就判断
 * 下一个账号，<b>永远也不会出现跳过某一章的情况</b>」。在那之前，第83章在前面某个号身上
 * 没走通一次，当时的 {@code touched} 集合就把它对后面 6 个号全部关上了 —— 84、85、86、87 章
 * 照样买到，83 章空着；而弹窗上只有一句「没走通 1」：没有章号、没有后果，他是自己对着
 * STATUS 广播才发现缺口的。中间一版放宽成「最多让 2 个号试」，也还是会跳，所以现在
 * 失败一次都不许划掉：8 个号全试一遍，全都不行就整本停在那一章。
 *
 * <p>真机上这条路要跑一整趟 8 个号才走到一次，所以只能在这里钉。
 */
public class SubscribeGapTest {

    // ---------- 什么时候才允许把一章从这一趟里划掉 ----------

    /**
     * <b>这就是被修掉的那一条</b>：没走通不算定论，8 个号一个个都得试同一章。
     *
     * <p>找不到那一行、点了行「已选」还是 0、券不够 —— 在这里都一样：一律不许划掉。
     */
    @Test
    public void aFailedChapterIsNeverSettled() {
        SubscribeRun.Settled settled = new SubscribeRun.Settled();
        for (int i = 1; i <= 8; i++) {
            assertFalse("第 " + i + " 个号必须还能试第83章 —— 这正是 2026-09-03 缺掉它的那一下",
                    settled.has(83L));
        }
    }

    /** 买到了／免费章／本机已有买不了 —— 有定论才划掉，别让下一个号白翻一遍目录。 */
    @Test
    public void aSettledChapterIsClosedAtOnce() {
        SubscribeRun.Settled settled = new SubscribeRun.Settled();
        settled.mark(83L);
        assertTrue(settled.has(83L));
        assertFalse("各章分开算：第83章有定论不该影响第84章", settled.has(84L));
    }

    // ---------- 跑完回头看：整本停在哪一章 ----------

    /** 卡住必须<b>点名到章</b>，还要说出几个号试过、最后一个号为什么不行。 */
    @Test
    public void aStuckChapterIsNamedWithItsCount() {
        String note = SubscribeRun.stuckNote(83, 8, "好好上课是 只剩 2 代券，不够买第83章", 83);
        assertNotNull(note);
        assertTrue(note, note.contains("第83章"));
        assertTrue("要说出几个号试过", note.contains("8"));
        assertTrue("要说出最后一个号为什么不行", note.contains("只剩 2 代券"));
        assertTrue("要说清后面的章没有被跳过去买", note.contains("没往后买"));
    }

    /**
     * 前面的号没订下它、后面某个号订下了 —— 那不叫卡住，一个字都不该说。
     *
     * <p>判据是「它还是不是所有号合起来还没买过的最小章」：订下来之后最小未买章就往后走了。
     */
    @Test
    public void aChapterSomebodyLaterBoughtIsNotStuck() {
        assertNull(SubscribeRun.stuckNote(83, 3, "五杯半雪碧 代券不够", 84));
    }

    /** 没卡在任何一章上（整趟顺利，或者整本都有主了）就别报。 */
    @Test
    public void nothingStuckMeansNothingToSay() {
        assertNull(SubscribeRun.stuckNote(0, 0, null, 83));
        assertNull("整本都有主了", SubscribeRun.stuckNote(83, 8, "代券不够", 0));
    }

    /** 原因读不出来（结果不明那种）也照样要报出「停在第几章」。 */
    @Test
    public void aStuckChapterIsReportedEvenWithoutAReason() {
        String note = SubscribeRun.stuckNote(83, 8, null, 83);
        assertNotNull(note);
        assertTrue(note, note.contains("第83章"));
    }

    // ---------- 跑完回头看：有没有真的越过一章 ----------

    /**
     * 没人报告过也要看得出来：买到了第87章，可最小的未买章还是第83章 —— 中间空了。
     *
     * <p>队列绝不越过一章，所以这句话正常永远不该出现；留着它当不变量断言 ——
     * 本机已下载但谁都买不了的章、被 MIUI 杀掉之后重跑的那一趟，都可能让缺口悄悄出现。
     * 用户就是这么发现 2026-09-03 那次漏订的。
     */
    @Test
    public void holeIsDetectedEvenWhenNobodyReportedIt() {
        String note = SubscribeRun.gapNote(87, 83);
        assertNotNull(note);
        assertTrue(note, note.contains("第87章") && note.contains("第83章"));
    }

    /** 正常往前推不是缺口，别报假警。 */
    @Test
    public void movingForwardIsNotAGap() {
        assertNull("买到87、下一章是88，正是该有的样子", SubscribeRun.gapNote(87, 88));
        assertNull("整本都有主了", SubscribeRun.gapNote(87, 0));
        assertNull("一章都没买到，谈不上越过（原因另有说法）", SubscribeRun.gapNote(0, 83));
    }
}
