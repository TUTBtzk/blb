package com.example.blb.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.blb.data.CheckInLog;
import com.example.blb.data.CheckInRow;
import com.example.blb.data.Purchase;
import com.example.blb.data.PurchaseRow;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 签到页最上面那一行「今天怎么样了」，以及它最后那一段「新订阅几章」。
 *
 * <p>为什么这一行要断死：它是签到页第一眼要读的东西，而使用者可能在跑完之后才回来看一眼 ——
 * 这一行说不清「成了几个、订了几章」，他就只能去翻运行日志。界面代码这个项目里测不了
 * （没有 Robolectric），所以合成规则写在 {@link TodayHeadline} 里，这里逐条断。
 */
public class TodayHeadlineTest {

    private static CheckInRow row(String name, String status) {
        CheckInRow r = new CheckInRow();
        r.label = name;
        r.status = status;
        return r;
    }

    @Test
    public void everythingDoneSaysSoInOneLine() {
        List<CheckInRow> rows = Arrays.asList(row("主号", CheckInLog.OK),
                row("备用1", CheckInLog.ALREADY));
        String line = TodayHeadline.line(2, rows, 0, false);
        assertTrue(line, line.contains("今天：2 个号"));
        assertTrue(line, line.contains("签到 2/2"));
        assertEquals(StatusPalette.OK, TodayHeadline.tone(rows));
    }

    @Test
    public void nothingRunYetDoesNotPretendToBeAFailure() {
        // 队列还没开始、或者今天整天没跑：这两种情况都不需要人去管，不能说成「签到 0/8」。
        List<CheckInRow> rows = Arrays.asList(row("主号", null), row("备用1", null));
        String line = TodayHeadline.line(2, rows, 0, false);
        assertTrue(line, line.contains("今天还没跑"));
        assertFalse(line, line.contains("0/"));
        assertEquals(StatusPalette.IDLE, TodayHeadline.tone(rows));
    }

    @Test
    public void skippedOnlyIsAlsoJustNotRunYet() {
        List<CheckInRow> rows = Collections.singletonList(row("主号", CheckInLog.SKIPPED));
        String line = TodayHeadline.line(1, rows, 0, false);
        assertTrue(line, line.contains("今天还没跑"));
        assertEquals(StatusPalette.IDLE, TodayHeadline.tone(rows));
    }

    @Test
    public void failedAccountsTurnTheLineAmber() {
        List<CheckInRow> rows = Arrays.asList(row("主号", CheckInLog.OK),
                row("备用1", CheckInLog.FAILED), row("备用2", null));
        String line = TodayHeadline.line(3, rows, 0, false);
        assertTrue(line, line.contains("签到 1/3"));
        assertTrue(line, line.contains("没成 1 个"));
        assertTrue(line, line.contains("还没跑 1 个"));
        // 一级页只用三档颜色：失败在这里是「要你注意」，红色留给二级页那一行。
        assertEquals(StatusPalette.WARN, TodayHeadline.tone(rows));
    }

    @Test
    public void newChaptersAppearOnlyWhenThereReallyAreSome() {
        List<CheckInRow> rows = Collections.singletonList(row("主号", CheckInLog.OK));
        assertTrue(TodayHeadline.line(1, rows, 3, false).contains("新订阅 3 章"));
        // 一单都没订就不提这一段：那句话是给「跑完这趟有收获」看的。
        assertFalse(TodayHeadline.line(1, rows, 0, false).contains("新订阅"));
    }

    @Test
    public void aTruncatedCountIsSaidAsAtLeast() {
        // 统计窗口被截断时不能报一个看着精确的假数（见 TodayPurchases.truncated）。
        List<CheckInRow> rows = Collections.singletonList(row("主号", CheckInLog.OK));
        assertTrue(TodayHeadline.line(1, rows, 500, true).contains("新订阅 ≥500 章"));
    }

    @Test
    public void noEnabledAccountSaysWhyItCannotRun() {
        String line = TodayHeadline.line(0, new ArrayList<CheckInRow>(), 0, false);
        assertTrue(line, line.contains("没有启用中的账号"));
        assertEquals(StatusPalette.IDLE, TodayHeadline.tone(new ArrayList<CheckInRow>()));
    }

    // ---------- 今天新订阅了几章 ----------

    private static PurchaseRow bought(long chapterId, long at, String source) {
        PurchaseRow r = new PurchaseRow();
        r.chapterId = chapterId;
        r.purchasedAt = at;
        r.source = source;
        return r;
    }

    @Test
    public void onlyTodaysRealPurchasesCountAndOneChapterCountsOnce() {
        long today = 1_000L;
        List<PurchaseRow> rows = Arrays.asList(
                bought(11, 1_500, Purchase.SRC_AUTO),
                // 同一章被另一个号也买过（2026-09-14 确认是真实历史）：仍然只算一章。
                bought(11, 1_400, Purchase.SRC_MANUAL),
                bought(12, 1_300, Purchase.SRC_REMOTE_DETAIL),
                // 免费章回填（source=OWNED）一分券都没花，它不是「新订阅」。
                bought(13, 1_200, Purchase.SRC_OWNED),
                // 昨天的记录：不进今天这一行。
                bought(14, 900, Purchase.SRC_AUTO));
        assertEquals(2, TodayPurchases.chapters(rows, today));
    }

    @Test
    public void anOldestRowStillFromTodayMeansTheWindowWasCut() {
        long today = 1_000L;
        assertTrue(TodayPurchases.truncated(Arrays.asList(
                bought(11, 1_500, Purchase.SRC_AUTO), bought(12, 1_100, Purchase.SRC_AUTO)), today));
        assertFalse(TodayPurchases.truncated(Arrays.asList(
                bought(11, 1_500, Purchase.SRC_AUTO), bought(12, 900, Purchase.SRC_AUTO)), today));
        assertFalse(TodayPurchases.truncated(Collections.<PurchaseRow>emptyList(), today));
    }
}
