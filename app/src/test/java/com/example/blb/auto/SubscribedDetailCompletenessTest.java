package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.example.blb.data.Chapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 只测试整数覆盖和已读事实，不用屏幕替身把一个失败手势伪装成到达列表末尾。 */
public class SubscribedDetailCompletenessTest {
    private static SubscribedDetail.Entry entry(String amount, String currency, String date) {
        return SubscribedDetail.parseRow("卷一 第1章 甲", amount, currency, date);
    }

    private static SubscribedDetail.Entry full() {
        return entry("10", "代券", "2026-09-01");
    }

    private static SubscribedDetail.ReadResult result(boolean opened, boolean top, boolean end,
                                                     boolean truncated, int unreadable,
                                                     int total, int covered, String reason) {
        return new SubscribedDetail.ReadResult(Collections.singletonList(full()), opened,
                top, end, truncated, unreadable, total, covered, reason);
    }

    @Test public void completeNonemptyEvidenceCanBeUsed() {
        assertTrue(result(true, true, true, false, 0, 3, 3, "末项已确认").complete());
    }

    @Test public void missingAnyCompletenessFlagCannotBeUpgradedByMatchingCounts() {
        assertFalse(result(false, true, true, false, 0, 1, 1, "没打开").complete());
        assertFalse(result(true, false, true, false, 0, 1, 1, "从中间开始").complete());
        assertFalse(result(true, true, false, false, 0, 1, 1, "请求被接受但没有推进").complete());
        assertFalse(result(true, true, true, true, 0, 1, 1, "达到扫描上限").complete());
    }

    @Test public void failedScrollAndStationaryTreeAreBothIncomplete() {
        for (String reason : Arrays.asList("滚动请求失败", "旧树一直不变", "手势被取消")) {
            assertFalse(result(true, true, false, false, 0, 3, 3, reason).complete());
        }
    }

    @Test public void unknownOrUnreadableRowsAreNotZero() {
        assertFalse(result(true, true, true, false, -1, 1, 1, "读不到").complete());
        assertFalse(result(true, true, true, false, 1, 1, 1, "一条未解析").complete());
    }

    @Test public void missingOrChangedCollectionCountsNeverProveCompleteCoverage() {
        assertFalse(result(true, true, true, false, 0, -1, 1, "元数据缺失").complete());
        assertFalse(result(true, true, true, false, 0, 3, 2, "中间漏项").complete());
        assertFalse(result(true, true, true, false, 0, 2, 3, "总数改变").complete());
        assertFalse(result(true, true, true, false, 0, 1, -1, "覆盖数未知").complete());
    }

    @Test public void anEmptyTreeCannotBeCertifiedByZeroEqualsZero() {
        SubscribedDetail.ReadResult empty = new SubscribedDetail.ReadResult(
                Collections.<SubscribedDetail.Entry>emptyList(), true, true, true,
                false, 0, 0, 0, "空树保持不变");
        assertFalse(empty.complete());
        assertFalse(SubscribedDetail.completeCoverage(0, Collections.<Integer>emptyList()));
    }

    @Test public void anUnrecognisedEntryCannotHideBehindAZeroUnreadableCounter() {
        SubscribedDetail.ReadResult result = new SubscribedDetail.ReadResult(
                Collections.singletonList(SubscribedDetail.parseRow("作品相关", "10", "代券", "2026-09-01")),
                true, true, true, false, 0, 1, 1, "错误地声称没有解析失败");
        assertFalse(result.complete());
    }

    @Test public void aUniquelyMappedExtraKeepsAllPhysicalCompletenessRequirements() {
        String title = "番外 藏在地下室的恶鬼（上）";
        Chapter chapter = new Chapter();
        chapter.id = 6120;
        chapter.novelId = 1;
        chapter.chapterNo = 612;
        chapter.title = title;
        chapter.volumeTitle = "番外";
        SubscribedDetail.Entry raw = SubscribedDetail.parseRow("番外 " + title,
                "12", "代券", "2026-09-08");
        assertFalse(raw.known());
        assertFalse(SubscribedDetail.completeTransaction(raw));
        SubscribedDetail.Entry mapped = SubscribedDetail.identifyUnnumbered(raw,
                Collections.singletonList(chapter));
        assertTrue(mapped != null && mapped.hasChapterIdentity());
        assertEquals(-1, mapped.chapterNo);
        assertTrue(SubscribedDetail.completeTransaction(mapped));
        assertTrue(new SubscribedDetail.ReadResult(Collections.singletonList(mapped),
                true, true, true, false, 0, 2, 2, "卷名与标题唯一，连续覆盖所有项").complete());
        assertFalse(new SubscribedDetail.ReadResult(Collections.singletonList(mapped),
                true, true, false, false, 0, 2, 2, "还没到底").complete());
        assertFalse(new SubscribedDetail.ReadResult(Collections.singletonList(mapped),
                true, true, true, true, 0, 2, 2, "读取截断").complete());
        assertFalse(new SubscribedDetail.ReadResult(Collections.singletonList(mapped),
                true, true, true, false, 1, 2, 2, "另一行读不全").complete());
        assertFalse(new SubscribedDetail.ReadResult(Collections.singletonList(raw),
                true, true, true, false, 0, 2, 2, "仍未确定卷名和标题").complete());
    }

    @Test public void contiguousItemCoverageAllowsRealOverlapsAndDateHeaderItems() {
        assertTrue(SubscribedDetail.completeCoverage(5, Arrays.asList(0, 1, 2, 2, 3, 4)));
        assertTrue(SubscribedDetail.completeCoverage(1, Collections.singletonList(0)));
    }

    @Test public void equalNumberOfObservedIndicesCanStillContainAGap() {
        assertFalse(SubscribedDetail.completeCoverage(4, Arrays.asList(0, 1, 1, 3)));
        assertFalse(SubscribedDetail.completeCoverage(4, Arrays.asList(0, 1, 2, 4)));
        assertFalse(SubscribedDetail.completeCoverage(3, Arrays.asList(-1, 1, 2)));
        assertFalse(SubscribedDetail.completeCoverage(3, Arrays.asList(0, null, 2)));
        assertFalse(SubscribedDetail.completeCoverage(-1, Arrays.asList(0, 1, 2)));
    }

    @Test public void resultCopiesTheInputListAndReportsTheExactFailureReason() {
        List<SubscribedDetail.Entry> input = new ArrayList<>(Collections.singletonList(full()));
        SubscribedDetail.ReadResult result = new SubscribedDetail.ReadResult(input, true,
                true, false, false, 0, 3, 2, "手势失败不等于到底");
        input.clear();
        assertEquals(1, result.entries.size());
        assertTrue(result.describe().contains("2/3"));
        assertTrue(result.describe().contains("手势失败不等于到底"));
        assertTrue(result.describe().contains("未完整"));
    }

    @Test public void onlyOneCompleteObservationCanSupplyTransactionFacts() {
        SubscribedDetail.Entry partial = entry(null, null, "2026-09-01");
        SubscribedDetail.Entry full = full();
        assertSame(full, SubscribedDetail.preferCompleteObservation(partial, full));
        assertSame(full, SubscribedDetail.preferCompleteObservation(full, partial));
        assertTrue(SubscribedDetail.completeTransaction(full));
    }

    @Test public void complementaryIncompleteObservationsAreNeverStitchedIntoAFullFact() {
        SubscribedDetail.Entry amountOnly = entry("10", "代券", null);
        SubscribedDetail.Entry dateOnly = entry(null, null, "2026-09-01");
        SubscribedDetail.Entry kept = SubscribedDetail.preferCompleteObservation(amountOnly, dateOnly);
        assertSame(amountOnly, kept);
        assertFalse(SubscribedDetail.completeTransaction(kept));
    }

    @Test public void conflictingMoneyDatesOrIdentityInvalidateTheItem() {
        assertNull(SubscribedDetail.preferCompleteObservation(full(), entry("12", "代券", "2026-09-01")));
        assertNull(SubscribedDetail.preferCompleteObservation(full(), entry("10", "代券", "2026-09-02")));
        assertNull(SubscribedDetail.preferCompleteObservation(full(), entry("10", "火券", "2026-09-01")));
        assertNull(SubscribedDetail.preferCompleteObservation(full(), SubscribedDetail.parseRow(
                "卷一 第2章 乙", "10", "代券", "2026-09-01")));
    }

    @Test public void unknownMoneyCurrencyAndDateStayIncomplete() {
        assertFalse(SubscribedDetail.completeTransaction(entry(null, "代券", "2026-09-01")));
        assertFalse(SubscribedDetail.completeTransaction(entry("10", null, "2026-09-01")));
        assertFalse(SubscribedDetail.completeTransaction(entry("10", "代券", null)));
        assertTrue("币种读到火券是真实的错误证据，不能伪装成没读到",
                SubscribedDetail.completeTransaction(entry("10", "火券", "2026-09-01")));
    }
}
