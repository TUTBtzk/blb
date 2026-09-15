package com.example.blb.auto;

import com.example.blb.data.Chapter;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** 合成多屏场景；15→20 是回归输入，不宣称是 w9899 未记录的新项数。 */
public class SubscribedDetailPipelineTest {
    private static final String DATE = "2026-09-08";

    static final class Reader implements SubscribedDetail.DetailReader {
        final List<FakeNode> pages;
        final Map<String, List<Selector>> selectors;
        final List<String> logs = new ArrayList<>();
        int page, forwardCalls, backwardCalls, backwardProbes, countDiagnostics;
        int delayedReads, delayOnNextForward;
        long now;
        FakeNode previousRoot;
        boolean noOp, failForward, cancelForward, allowEnd = true, allowTop = true;

        Reader(FakeNode... pages) throws Exception {
            this.pages = Arrays.asList(pages);
            selectors = SelectorSet.parse("{"
                    + "\"subscribed_detail_ready\":[{\"id\":\"title_tv\",\"text\":\"订阅明细\"}],"
                    + "\"subscribed_detail_desc\":[{\"id\":\"tvDesc\"}],"
                    + "\"subscribed_detail_time\":[{\"id\":\"tvTime\"}],"
                    + "\"subscribed_detail_amount\":[{\"textRegex\":\"^[0-9]+$\",\"requireArea\":true}],"
                    + "\"subscribed_detail_currency\":[{\"textRegex\":\"^(代券|火券)$\",\"requireArea\":true}]}" );
        }

        @Override public NodeView root() {
            if (delayedReads > 0) { delayedReads--; return previousRoot; }
            return pages.get(page);
        }
        @Override public List<NodeView> findAllIn(NodeView root, String key) {
            List<Selector> options = selectors.get(key);
            if (root == null || options == null) return Collections.emptyList();
            for (Selector option : options) {
                List<NodeView> found = NodeMatcher.findAll(root, option);
                if (!found.isEmpty()) return found;
            }
            return Collections.emptyList();
        }
        @Override public boolean scrollForward() {
            throw new AssertionError("证据链不得调用无法证明完成的全局滚动");
        }
        @Override public boolean scrollWithinList(boolean forward) throws StepRunner.StepFailure {
            if (!forward) { backwardCalls++; return true; }
            forwardCalls++;
            if (cancelForward) throw new StepRunner.StepFailure(StepRunner.Kind.CANCELLED, "已取消");
            if (failForward) return false;
            if (!noOp && page + 1 < pages.size()) {
                previousRoot = pages.get(page);
                page++;
                delayedReads = delayOnNextForward;
                delayOnNextForward = 0;
            }
            return true;
        }
        @Override public StepRunner.CatalogScroll probeList(boolean forward) {
            if (!forward) {
                backwardProbes++;
                return allowTop ? StepRunner.CatalogScroll.BLOCKED : StepRunner.CatalogScroll.ACCEPTED;
            }
            return allowEnd && page == pages.size() - 1
                    ? StepRunner.CatalogScroll.BLOCKED : StepRunner.CatalogScroll.ACCEPTED;
        }
        @Override public void waitMillis(long millis) { now += millis; }
        @Override public long now() { return now; }
        @Override public void log(String message) { logs.add(message); }
        @Override public void diagnostic(String stage, NodeView root) {
            if (stage.contains("集合项数")) countDiagnostics++;
        }
    }

    static FakeNode window(int total, int first, int last) {
        FakeNode[] rows = new FakeNode[last - first + 1];
        for (int i = first; i <= last; i++) rows[i - first] = transaction(i, i - first,
                "卷一 第" + (i + 1) + "章 标题" + (i + 1), "10", "代券",
                i % 6 == 0 ? DATE : null);
        return page(total, rows);
    }

    static FakeNode transaction(int index, int slot, String desc, String amount, String currency,
                                String date) {
        int top = 260 + slot * 250;
        FakeNode row = FakeNode.node().withClass("android.widget.LinearLayout").item(index)
                .withBounds(20, top, 1060, top + 230);
        if (date != null) row.add(FakeNode.text(date).withId("com.sfacg:id/tvTime")
                .withBounds(400, top + 5, 680, top + 40));
        return row.add(FakeNode.text(desc).withId("com.sfacg:id/tvDesc")
                        .withBounds(60, top + 70, 750, top + 160),
                FakeNode.text(amount).withBounds(810, top + 65, 950, top + 110),
                FakeNode.text(currency).withBounds(810, top + 115, 950, top + 160));
    }

    static FakeNode page(int total, FakeNode... rows) {
        return FakeNode.node().withBounds(0, 0, 1080, 2400).add(
                FakeNode.text("订阅明细").withId("com.sfacg:id/title_tv")
                        .withBounds(400, 100, 680, 180),
                FakeNode.node().withClass("androidx.recyclerview.widget.RecyclerView")
                        .withId("com.sfacg:id/baseListView").collection(total).scrollable(true)
                        .withBounds(0, 200, 1080, 2250).add(rows),
                FakeNode.text("清单约5分钟更新一次，可下拉刷新")
                        .withBounds(60, 2290, 1020, 2350));
    }

    static Reader twenty() throws Exception {
        return new Reader(window(15, 0, 5), window(20, 3, 8), window(20, 6, 11),
                window(20, 9, 14), window(20, 12, 17), window(20, 14, 19));
    }

    static SubscribedDetail.ReadResult read(Reader reader, int expected) throws Exception {
        return SubscribedDetail.collectWithEvidence(reader, Collections.<Chapter>emptyList(), expected);
    }

    @Test public void twentyTransactionsSurviveGrowingMetadataAndKeepTheirDates() throws Exception {
        Reader reader = twenty();
        SubscribedDetail.ReadResult result = read(reader, 20);
        assertTrue(result.describe(), result.complete());
        assertEquals(20, result.entries.size());
        assertEquals(20, result.collectionItems);
        assertEquals(20, result.coveredItems);
        assertEquals(1, reader.countDiagnostics);
        assertTrue(reader.backwardProbes > 0);
        assertEquals("验顶不能触发下拉刷新", 0, reader.backwardCalls);
        assertTrue(reader.forwardCalls >= 6);
        for (int i = 0; i < 20; i++) {
            assertEquals(i + 1, result.entries.get(i).chapterNo);
            assertEquals(DATE, result.entries.get(i).date);
            assertEquals(10, result.entries.get(i).amount);
        }
    }

    @Test public void aChangedIndexCannotHideBehindTheMetadataGrowth() throws Exception {
        FakeNode next = window(20, 3, 8);
        ((FakeNode) next.child(1).child(1).child(0)).withText("卷一 第99章 被替换的交易");
        SubscribedDetail.ReadResult result = read(new Reader(window(15, 0, 5), next), 20);
        assertFalse(result.complete());
        assertTrue(result.describe(), result.stopReason.contains("重排") || result.stopReason.contains("重叠"));
    }

    @Test public void shrinkingMetadataRequiresAnIndependentRead() throws Exception {
        SubscribedDetail.ReadResult result = read(new Reader(window(20, 0, 5), window(15, 3, 8)), 20);
        assertFalse(result.complete());
        assertTrue(result.stopReason.contains("缩小"));
        assertEquals(15, result.collectionItems);
    }

    @Test public void aMissingMiddleRangeCannotBeReplacedByTheExpectedCount() throws Exception {
        SubscribedDetail.ReadResult result = read(new Reader(window(12, 0, 3),
                window(12, 6, 11)), 10);
        assertEquals(10, result.entries.size());
        assertTrue(result.reachedEnd);
        assertFalse(result.complete());
        assertEquals(10, result.coveredItems);
    }

    @Test public void expectedCountAndVisibleFooterDoNotProveTheBoundary() throws Exception {
        Reader reader = new Reader(window(3, 0, 2));
        reader.allowEnd = false;
        SubscribedDetail.ReadResult result = read(reader, 3);
        assertEquals(3, result.entries.size());
        assertFalse(result.reachedEnd);
        assertFalse(result.complete());
    }

    @Test public void failedGestureNeverBecomesAnEndSignal() throws Exception {
        Reader reader = new Reader(window(3, 0, 2));
        reader.failForward = true;
        SubscribedDetail.ReadResult result = read(reader, 3);
        assertFalse(result.complete());
        assertFalse(result.reachedEnd);
        assertTrue(result.stopReason.contains("未完成"));
    }

    @Test public void cancelledGesturePropagatesWithoutCertifyingAnything() throws Exception {
        Reader reader = new Reader(window(3, 0, 2));
        reader.cancelForward = true;
        try { read(reader, 3); fail("应传播取消"); }
        catch (StepRunner.StepFailure failure) { assertEquals(StepRunner.Kind.CANCELLED, failure.kind); }
    }

    @Test public void acceptedNoOpInTheMiddleRemainsIncomplete() throws Exception {
        Reader reader = new Reader(window(8, 0, 5), window(8, 2, 7));
        reader.noOp = true;
        SubscribedDetail.ReadResult result = read(reader, 8);
        assertFalse(result.complete());
        assertFalse(result.reachedEnd);
        assertTrue(reader.forwardCalls <= 3);
    }

    @Test public void delayedOldSnapshotsDoNotEndTheReadEarly() throws Exception {
        Reader reader = twenty();
        reader.delayOnNextForward = 3;
        SubscribedDetail.ReadResult result = read(reader, 20);
        assertTrue(result.describe(), result.complete());
    }

    @Test public void topIndexAloneCannotProveTheBeginning() throws Exception {
        Reader reader = new Reader(window(3, 0, 2));
        reader.allowTop = false;
        SubscribedDetail.ReadResult result = read(reader, 3);
        assertFalse(result.startedAtTop);
        assertFalse(result.complete());
    }

    @Test public void startingInTheMiddleCannotBeCertified() throws Exception {
        SubscribedDetail.ReadResult result = read(new Reader(window(8, 2, 7)), 6);
        assertFalse(result.startedAtTop);
        assertFalse(result.complete());
    }

    @Test public void indexedDateHeadingAndFooterAreClassifiedWithoutBecomingTransactions() throws Exception {
        FakeNode date = FakeNode.text(DATE).withId("com.sfacg:id/tvTime").heading(true).item(0)
                .withBounds(400, 240, 700, 280);
        FakeNode footer = FakeNode.text("清单约5分钟更新一次，可下拉刷新").item(3)
                .withBounds(60, 1060, 1020, 1120);
        SubscribedDetail.ReadResult result = read(new Reader(page(4, date,
                transaction(1, 1, "卷一 第1章 甲", "10", "代券", null),
                transaction(2, 2, "卷一 第2章 乙", "12", "代券", null), footer)), 2);
        assertTrue(result.describe(), result.complete());
        assertEquals(2, result.entries.size());
        assertEquals(4, result.coveredItems);
    }

    @Test public void anUnknownVisibleItemIsNotDroppedAsDecoration() throws Exception {
        FakeNode unknown = FakeNode.text("加载中的未知项目").item(3).withBounds(40, 1030, 1040, 1200);
        FakeNode root = window(4, 0, 2);
        ((FakeNode) root.child(1)).add(unknown);
        SubscribedDetail.ReadResult result = read(new Reader(root), 3);
        assertFalse(result.complete());
        assertTrue(result.describe(), result.describe().contains("加载中的未知项目"));
    }

    @Test public void clippedMoneyCanOnlyRecoverFromACompleteLaterObservation() throws Exception {
        FakeNode first = window(8, 0, 5);
        FakeNode row = (FakeNode) first.child(1).child(5);
        ((FakeNode) row.child(1)).withText(null);
        SubscribedDetail.ReadResult result = read(new Reader(first, window(8, 3, 7)), 8);
        assertTrue(result.describe(), result.complete());
        assertEquals(10, result.entries.get(5).amount);
    }

    @Test public void moneyOutsideTheViewportIsUnknownDespiteNonzeroCoordinates() throws Exception {
        FakeNode first = window(3, 0, 2);
        ((FakeNode) first.child(1).child(2).child(1)).withBounds(810, 2300, 950, 2360);
        SubscribedDetail.ReadResult result = read(new Reader(first), 3);
        assertFalse(result.complete());
        assertEquals(-1, result.entries.get(2).amount);
        assertTrue(result.describe().contains("金额"));
    }

    @Test public void anUnreadableNewDateHeaderDoesNotBorrowTheOldGroupDate() throws Exception {
        FakeNode second = window(8, 3, 7);
        ((FakeNode) second.child(1).child(3).child(0)).withText("");
        SubscribedDetail.ReadResult result = read(new Reader(window(8, 0, 5), second), 8);
        assertFalse(result.complete());
        assertNull(result.entries.get(6).date);
        assertNull(result.entries.get(7).date);
    }

    private static FakeNode changingDateGroup(boolean unreadableHeader, boolean unreadableLastDesc) {
        FakeNode second = transaction(1, 1, "卷一 第2章 乙", "10", "代券", "2026-09-09");
        if (unreadableHeader) ((FakeNode) second.child(0)).withBounds(0, 0, 0, 0);
        FakeNode third = transaction(2, 2, "卷一 第3章 丙", "10", "代券", null);
        if (unreadableLastDesc) ((FakeNode) third.child(0)).withBounds(0, 0, 0, 0);
        return page(3, transaction(0, 0, "卷一 第1章 甲", "10", "代券", DATE), second, third);
    }

    @Test public void zeroAreaNewHeaderCannotPoisonTheFollowingRowAcrossFrames() throws Exception {
        SubscribedDetail.ReadResult result = read(new Reader(changingDateGroup(true, false),
                changingDateGroup(false, true)), 3);
        assertFalse(result.describe(), result.complete());
        assertEquals(3, result.entries.size());
        assertEquals(DATE, result.entries.get(0).date);
        assertEquals("2026-09-09", result.entries.get(1).date);
        assertNull("第三项从未在新组头可核实时完整露出，不能保留旧组日期", result.entries.get(2).date);
        assertEquals(2, result.coveredItems);
        assertTrue(result.describe().contains("列表第 3 项"));
    }

    @Test public void aLaterReadableRowRecoversTheNewDateWithoutRetainingOldProblems() throws Exception {
        SubscribedDetail.ReadResult result = read(new Reader(changingDateGroup(true, false),
                changingDateGroup(false, true), changingDateGroup(false, false)), 3);
        assertTrue(result.describe(), result.complete());
        assertEquals("2026-09-09", result.entries.get(1).date);
        assertEquals("2026-09-09", result.entries.get(2).date);
        assertTrue(result.rowProblems.toString(), result.rowProblems.isEmpty());
    }

    @Test public void aWholeRowsHiddenStaleDateIsIgnoredWithoutBecomingEvidence() throws Exception {
        FakeNode second = transaction(1, 1, "卷一 第2章 乙", "10", "代券", "1999-01-01");
        ((FakeNode) second.child(0)).visible(false);
        SubscribedDetail.ReadResult result = read(new Reader(page(2,
                transaction(0, 0, "卷一 第1章 甲", "10", "代券", DATE), second)), 2);
        assertTrue(result.describe(), result.complete());
        assertEquals(DATE, result.entries.get(1).date);
    }

    @Test public void hiddenDateWithoutAVisibleGroupHeaderRemainsUnknown() throws Exception {
        FakeNode first = transaction(0, 0, "卷一 第1章 甲", "10", "代券", "1999-01-01");
        ((FakeNode) first.child(0)).visible(false);
        SubscribedDetail.ReadResult result = read(new Reader(page(1, first)), 1);
        assertFalse(result.complete());
        assertNull(result.entries.get(0).date);
    }

    @Test public void clippedRowWithHiddenDateCannotBorrowTheEarlierGroup() throws Exception {
        FakeNode second = transaction(1, 1, "卷一 第2章 乙", "10", "代券", "1999-01-01")
                .withBounds(20, 510, 1060, 2300);
        ((FakeNode) second.child(0)).visible(false);
        SubscribedDetail.ReadResult result = read(new Reader(page(2,
                transaction(0, 0, "卷一 第1章 甲", "10", "代券", DATE), second)), 2);
        assertFalse(result.complete());
        assertNull(result.entries.get(1).date);
    }

    @Test public void aDateBelowItsDescriptionCannotServeAsThatRowsHeader() throws Exception {
        FakeNode second = transaction(1, 1, "卷一 第2章 乙", "10", "代券", "2026-09-09");
        ((FakeNode) second.child(0)).withBounds(400, 690, 680, 725);
        SubscribedDetail.ReadResult result = read(new Reader(page(3,
                transaction(0, 0, "卷一 第1章 甲", "10", "代券", DATE), second,
                transaction(2, 2, "卷一 第3章 丙", "10", "代券", null))), 3);
        assertFalse(result.complete());
        assertNull(result.entries.get(1).date);
        assertNull(result.entries.get(2).date);
    }

    @Test public void samePositionWithConflictingAmountIsRejected() throws Exception {
        FakeNode second = window(8, 3, 7);
        ((FakeNode) second.child(1).child(1).child(1)).withText("12");
        SubscribedDetail.ReadResult result = read(new Reader(window(8, 0, 5), second), 8);
        assertFalse(result.complete());
        assertTrue(result.describe().contains("冲突"));
    }

    @Test public void anIntermediateIncompleteAmountsKnownFactCannotBeForgotten() throws Exception {
        FakeNode first = transaction(0, 0, "卷一 第1章 甲", null, "代券", DATE);
        FakeNode intermediate = transaction(0, 0, "卷一 第1章 甲", "10", "代券", DATE);
        ((FakeNode) intermediate.child(0)).withBounds(0, 0, 0, 0);
        FakeNode last = transaction(0, 0, "卷一 第1章 甲", "20", "代券", DATE);
        SubscribedDetail.ReadResult result = read(new Reader(page(1, first),
                page(1, intermediate), page(1, last)), 1);
        assertFalse(result.describe(), result.complete());
        assertTrue(result.describe(), result.stopReason.contains("冲突"));
        assertEquals("不能把第二屏金额与第一屏日期拼完整", -1, result.entries.get(0).amount);
    }

    @Test public void anIntermediateIncompleteCurrencyFactAlsoSurvives() throws Exception {
        FakeNode first = transaction(0, 0, "卷一 第1章 甲", "10", "未知券", DATE);
        FakeNode intermediate = transaction(0, 0, "卷一 第1章 甲", "10", "代券", DATE);
        ((FakeNode) intermediate.child(0)).withBounds(0, 0, 0, 0);
        FakeNode last = transaction(0, 0, "卷一 第1章 甲", "10", "火券", DATE);
        SubscribedDetail.ReadResult result = read(new Reader(page(1, first),
                page(1, intermediate), page(1, last)), 1);
        assertFalse(result.describe(), result.complete());
        assertTrue(result.describe(), result.stopReason.contains("冲突"));
        assertTrue(result.fireObserved);
    }

    @Test public void fireFromARejectedFrameIsStillVisibleToTheAuditGuard() throws Exception {
        FakeNode second = window(20, 3, 8);
        ((FakeNode) second.child(1).child(1).child(2)).withText(" 火券 ");
        SubscribedDetail.ReadResult result = read(new Reader(window(15, 0, 5), second), 20);
        assertFalse(result.complete());
        assertTrue(result.fireObserved);
        for (SubscribedDetail.Entry retained : result.entries) assertFalse(retained.fireSpent());
        assertTrue(SubscriptionAuditPolicy.hasFireEvidence(1, null, result, Collections.emptyList()));
    }

    @Test public void fireWithMissingOrZeroAreaDescriptionIsStillIndependentEvidence() throws Exception {
        for (boolean missingId : new boolean[]{true, false}) {
            FakeNode root = window(3, 0, 2);
            FakeNode row = (FakeNode) root.child(1).child(1);
            if (missingId) ((FakeNode) row.child(0)).withId("unreadable_desc");
            else ((FakeNode) row.child(0)).withBounds(0, 0, 0, 0);
            ((FakeNode) row.child(2)).withText(" 火券 ");
            SubscribedDetail.ReadResult result = read(new Reader(root), 3);
            assertFalse(result.complete());
            assertTrue(result.describe(), result.fireObserved);
            for (SubscribedDetail.Entry retained : result.entries) assertFalse(retained.fireSpent());
            assertTrue(SubscriptionAuditPolicy.hasFireEvidence(1, null, result, Collections.emptyList()));
        }
    }

    @Test public void fireInAnUnstableSampleBlocksPolicyEvenWhenTheRetainedReadIsComplete() throws Exception {
        FakeNode fleeting = window(3, 0, 2);
        ((FakeNode) fleeting.child(1).child(1).child(0)).withBounds(0, 0, 0, 0);
        ((FakeNode) fleeting.child(1).child(1).child(2)).withText(" 火券 ");
        Reader reader = new Reader(window(3, 0, 2));
        reader.previousRoot = fleeting;
        reader.delayedReads = 1;
        SubscribedDetail.ReadResult result = read(reader, 3);
        assertTrue(result.describe(), result.complete());
        assertTrue(result.fireObserved);
        for (SubscribedDetail.Entry retained : result.entries) assertFalse(retained.fireSpent());
        VoucherLedger.Reading aggregate = VoucherLedger.parseSummary("3章节 - 0火券", null);
        String problem = SubscriptionAuditPolicy.detailProblem(aggregate, result);
        assertNotNull(problem);
        assertTrue(problem, problem.contains("火券"));
    }

    @Test public void duplicateTransactionsCannotHideBehindExpectedCount() throws Exception {
        FakeNode root = page(2,
                transaction(0, 0, "卷一 第1章 甲", "10", "代券", DATE),
                transaction(1, 1, "卷一 第1章 甲", "10", "代券", null));
        SubscribedDetail.ReadResult result = read(new Reader(root), 2);
        assertFalse(result.complete());
        assertEquals(2, result.entries.size());
        assertEquals(2, result.unreadableRows);
        assertTrue(result.describe().contains("重复"));
    }

    @Test public void recoveredRowsDoNotRemainInTheFinalUnreadableExamples() throws Exception {
        FakeNode[] firstRows = new FakeNode[6];
        for (int i = 0; i < 5; i++) firstRows[i] = transaction(i, i,
                "卷一 第" + (i + 1) + "章 标题" + (i + 1), "10", "代券", i == 0 ? DATE : null);
        firstRows[5] = FakeNode.text("暂未加载").item(5).withBounds(20, 1510, 1060, 1740);
        Reader reader = twenty();
        reader.pages.set(0, page(15, firstRows));
        SubscribedDetail.ReadResult result = read(reader, 20);
        assertTrue(result.describe(), result.complete());
        assertTrue(result.rowProblems.toString(), result.rowProblems.isEmpty());
        assertFalse(result.describe().contains("未读全示例"));
    }

    @Test public void aKnownItemThatBecomesWhollyVisibleButUnknownInvalidatesCoverage() throws Exception {
        FakeNode second = window(3, 0, 2);
        ((FakeNode) second.child(1).child(2).child(0)).withId("unreadable_desc");
        SubscribedDetail.ReadResult result = read(new Reader(window(3, 0, 2), second), 3);
        assertEquals(3, result.entries.size());
        assertFalse(result.describe(), result.complete());
        assertEquals(2, result.coveredItems);
        assertEquals(1, result.unreadableRows);
        assertTrue(result.describe().contains("列表第 3 项"));
    }

    @Test public void aWhollyUnknownItemCanRecoverWithoutLeavingAStaleProblem() throws Exception {
        FakeNode second = window(3, 0, 2);
        ((FakeNode) second.child(1).child(2).child(0)).withId("unreadable_desc");
        SubscribedDetail.ReadResult result = read(new Reader(window(3, 0, 2), second,
                window(3, 0, 2)), 3);
        assertTrue(result.describe(), result.complete());
        assertTrue(result.rowProblems.toString(), result.rowProblems.isEmpty());
    }

    @Test public void anUnknownInterludeCannotReclassifyAKnownTransactionAsDecoration() throws Exception {
        for (boolean dateHeader : new boolean[]{true, false}) {
            FakeNode unknown = window(3, 0, 2);
            ((FakeNode) unknown.child(1).child(2).child(0)).withId("unreadable_desc");
            FakeNode decoration = FakeNode.text(dateHeader ? DATE : "清单约5分钟更新一次，可下拉刷新")
                    .item(2).withBounds(40, 760, 1040, 840);
            if (dateHeader) decoration.withId("com.sfacg:id/tvTime").heading(true);
            FakeNode finalPage = page(3,
                    transaction(0, 0, "卷一 第1章 标题1", "10", "代券", DATE),
                    transaction(1, 1, "卷一 第2章 标题2", "10", "代券", null), decoration);
            SubscribedDetail.ReadResult result = read(new Reader(window(3, 0, 2), unknown, finalPage), 3);
            assertFalse(result.describe(), result.complete());
            assertTrue(result.describe(), result.stopReason.contains("重排"));
        }
    }

    @Test public void aClippedUnknownItemMayKeepItsEarlierCompleteObservation() throws Exception {
        FakeNode second = window(3, 0, 2);
        FakeNode row = (FakeNode) second.child(1).child(2);
        row.withBounds(20, 760, 1060, 2300);
        ((FakeNode) row.child(0)).withBounds(0, 0, 0, 0);
        SubscribedDetail.ReadResult result = read(new Reader(window(3, 0, 2), second), 3);
        assertTrue(result.describe(), result.complete());
        assertTrue(result.rowProblems.toString(), result.rowProblems.isEmpty());
    }

    @Test public void missingCollectionMetadataRemainsUnknown() throws Exception {
        FakeNode root = window(3, 0, 2);
        ((FakeNode) root.child(1)).collection(-1);
        SubscribedDetail.ReadResult result = read(new Reader(root), 3);
        assertFalse(result.complete());
        assertEquals(-1, result.collectionItems);
    }

    @Test public void scanLimitDoesNotCertifyAStillAdvancingList() throws Exception {
        FakeNode[] pages = new FakeNode[101];
        for (int i = 0; i < pages.length; i++) pages[i] = window(106, i, i + 5);
        SubscribedDetail.ReadResult result = read(new Reader(pages), 106);
        assertTrue(result.truncated);
        assertFalse(result.reachedEnd);
        assertFalse(result.complete());
    }

    @Test public void wrongPageOrTwoMatchingListsCannotSupplyEvidence() throws Exception {
        FakeNode wrong = window(3, 0, 2);
        ((FakeNode) wrong.child(0)).withText("订阅清单");
        assertFalse(read(new Reader(wrong), 3).complete());
        FakeNode duplicate = window(3, 0, 2);
        duplicate.add(FakeNode.node().withId("com.sfacg:id/baseListView")
                .withClass("androidx.recyclerview.widget.RecyclerView").collection(3)
                .withBounds(0, 200, 1080, 2250));
        assertFalse(read(new Reader(duplicate), 3).complete());
    }

    @Test public void hiddenPageMarkerOrHorizontalListCannotSupplyEvidence() throws Exception {
        FakeNode hidden = window(3, 0, 2);
        ((FakeNode) hidden.child(0)).visible(false);
        assertFalse(read(new Reader(hidden), 3).complete());
        FakeNode empty = window(3, 0, 2);
        ((FakeNode) empty.child(0)).withBounds(0, 0, 0, 0);
        assertFalse(read(new Reader(empty), 3).complete());
        FakeNode horizontal = window(3, 0, 2);
        ((FakeNode) horizontal.child(1)).withClass("custom.HorizontalListView");
        assertFalse(read(new Reader(horizontal), 3).complete());
    }
}
