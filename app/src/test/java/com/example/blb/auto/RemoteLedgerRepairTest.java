package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.blb.data.Chapter;
import com.example.blb.data.Purchase;
import com.example.blb.data.PurchaseRow;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 删除会把章节重新放回待购集合，七项证据缺一项都必须是零删除。 */
public class RemoteLedgerRepairTest {
    private static final long ACCOUNT = 5;
    private static final long NOVEL = 9;
    private static final String DAY = "2026-09-01";
    private static final long NOW = RemoteLedgerRecovery.date("2026-09-14") + 12 * 60 * 60_000L;

    private static Chapter chapter(int no, String title) {
        Chapter chapter = new Chapter();
        chapter.id = 100 + no;
        chapter.novelId = NOVEL;
        chapter.chapterNo = no;
        chapter.title = "第" + no + "章 " + title;
        return chapter;
    }

    private static PurchaseRow paid(long account, Chapter chapter, long id) {
        PurchaseRow row = new PurchaseRow();
        row.purchaseId = id;
        row.accountId = account;
        row.chapterId = chapter.id;
        row.chapterNo = chapter.chapterNo;
        row.chapterTitle = chapter.title;
        row.novelTitle = "测试书";
        row.accountNickname = "账号" + account;
        row.source = Purchase.SRC_AUTO;
        row.costVouchers = 10;
        row.purchasedAt = RemoteLedgerRecovery.date(DAY);
        return row;
    }

    private static SubscribedDetail.Entry entry(int no, String title) {
        return SubscribedDetail.parseRow("卷一 第" + no + "章 " + title, "10", "代券", DAY);
    }

    private static SubscribedDetail.ReadResult complete(List<SubscribedDetail.Entry> entries) {
        return new SubscribedDetail.ReadResult(entries, true, true, true, false, 0,
                entries.size(), entries.size(), "列表项从 0 完整覆盖到末项");
    }

    private static VoucherLedger.Reading aggregate(int chapters) {
        return VoucherLedger.parseSummary(chapters + "章节 - 0火券", null);
    }

    private static final class Fixture {
        final Chapter first = chapter(1, "甲");
        final Chapter extra = chapter(2, "乙");
        final Chapter third = chapter(3, "丙");
        final List<Chapter> chapters = new ArrayList<>(Arrays.asList(first, extra, third));
        final PurchaseRow firstPaid = paid(ACCOUNT, first, 201);
        final PurchaseRow extraPaid = paid(ACCOUNT, extra, 202);
        final List<PurchaseRow> rows = new ArrayList<>(Arrays.asList(firstPaid, extraPaid));
        VoucherLedger.Reading aggregate = aggregate(1);
        SubscribedDetail.ReadResult detail = complete(Collections.singletonList(entry(1, "甲")));

        RemoteLedgerRepair.Plan plan() {
            return planAt(NOW);
        }

        RemoteLedgerRepair.Plan planAt(long now) {
            return RemoteLedgerRepair.deletionPlan("甲号", "测试书", ACCOUNT, aggregate,
                    detail, chapters, rows, now);
        }
    }

    private static void suspect(int condition, RemoteLedgerRepair.Plan plan) {
        assertFalse(plan.message, plan.ok);
        assertTrue(plan.message, plan.message.contains("存疑"));
        assertTrue(plan.message, plan.deletions.isEmpty());
        assertEquals(plan.message, condition, plan.failedCondition);
    }

    @Test public void completeEvidenceOnlyDeletesTheOldExtraRecord() {
        Fixture f = new Fixture();
        RemoteLedgerRepair.Plan plan = f.plan();
        assertTrue(plan.message, plan.ok);
        assertEquals(1, plan.deletions.size());
        assertEquals(f.extraPaid.purchaseId, plan.deletions.get(0).purchaseId);
        assertEquals(NOVEL, plan.novelId);
        assertEquals(ACCOUNT, plan.accountId);
    }

    @Test public void fireFromADiscardedFrameBlocksDeletionEvenWithCompleteVoucherEntries() {
        Fixture f = new Fixture();
        assertTrue("只有新增火券证据阻断本用例，其余七项前置应全部通过", f.plan().ok);
        SubscribedDetail.ReadResult complete = f.detail;
        f.detail = new SubscribedDetail.ReadResult(complete.entries, complete.opened,
                complete.startedAtTop, complete.reachedEnd, complete.truncated,
                complete.unreadableRows, complete.collectionItems, complete.coveredItems,
                complete.stopReason, complete.entries.size(), 3, Collections.emptyList(), true);
        assertTrue(f.detail.complete());
        for (SubscribedDetail.Entry retained : f.detail.entries) assertFalse(retained.fireSpent());
        suspect(1, f.plan());
        assertTrue(f.plan().message.contains("火券"));
    }

    @Test public void conditionOneMissingAggregateNeverMeansZeroSubscriptions() {
        Fixture f = new Fixture();
        f.aggregate = VoucherLedger.Reading.missingRow();
        suspect(1, f.plan());
    }

    @Test public void conditionOneUnknownChapterCountIsNotZero() {
        Fixture f = new Fixture();
        f.aggregate = new VoucherLedger.Reading(true, -1, 0, -1, null, "读不到");
        suspect(1, f.plan());
    }

    @Test public void unreadableOrPositiveFireAmountCannotAuthorizeDeletion() {
        for (int fire : new int[]{-1, 1}) {
            Fixture f = new Fixture();
            f.aggregate = new VoucherLedger.Reading(true, 1, fire, -1, null, "火券未核清");
            suspect(1, f.plan());
        }
    }

    @Test public void conditionTwoStalledScreenDoesNotProveTheEnd() {
        Fixture f = new Fixture();
        f.detail = new SubscribedDetail.ReadResult(f.detail.entries, true, true, false,
                false, 0, 2, 1, "滚动后没有推进");
        suspect(2, f.plan());
    }

    @Test public void conditionTwoTruncatedOrUnparsableDetailsNeverDelete() {
        Fixture truncated = new Fixture();
        truncated.detail = new SubscribedDetail.ReadResult(truncated.detail.entries, true,
                true, true, true, 0, 1, 1, "达到上限");
        suspect(2, truncated.plan());
        Fixture unreadable = new Fixture();
        unreadable.detail = new SubscribedDetail.ReadResult(unreadable.detail.entries, true,
                true, true, false, 1, 1, 1, "有交易行不能解析");
        suspect(2, unreadable.plan());
    }

    @Test public void conditionThreeAggregateAndDetailCountsMustAgree() {
        Fixture f = new Fixture();
        f.aggregate = aggregate(2);
        suspect(3, f.plan());
    }

    @Test public void conditionFourOwnedIsProtectedEvenIfItsCostWasCorrupted() {
        Fixture f = new Fixture();
        f.extraPaid.source = Purchase.SRC_OWNED;
        suspect(4, f.plan());
    }

    @Test public void anUnknownSourceDoesNotProveTheRecordIsNotOwned() {
        Fixture f = new Fixture();
        f.extraPaid.source = null;
        suspect(4, f.plan());
    }

    @Test public void conditionFiveFreshBoundaryAndUnknownDatesNeverDelete() {
        for (long at : new long[]{NOW - SubscribedDetail.FRESH_MS + 1,
                NOW - SubscribedDetail.FRESH_MS, NOW, NOW + 1, 0, -1}) {
            Fixture f = new Fixture();
            f.extraPaid.purchasedAt = at;
            suspect(5, f.plan());
        }
    }

    @Test public void olderThanFifteenMinutesByOneMillisecondPassesTheAgeGuard() {
        Fixture f = new Fixture();
        f.extraPaid.purchasedAt = NOW - SubscribedDetail.FRESH_MS - 1;
        assertTrue(f.plan().message, f.plan().ok);
    }

    @Test public void conditionSixPositionOrTitleDriftNeverDeletes() {
        Fixture position = new Fixture();
        position.extraPaid.chapterNo = 3;
        suspect(6, position.plan());
        Fixture title = new Fixture();
        title.extraPaid.chapterTitle = "第2章 完全不同";
        suspect(6, title.plan());
        Fixture missing = new Fixture();
        missing.extraPaid.chapterTitle = null;
        suspect(6, missing.plan());
    }

    @Test public void conditionSixUnknownOriginalAmountsNeverDelete() {
        for (int[] costs : new int[][]{{-1, 10}, {10, -1}}) {
            Fixture f = new Fixture();
            f.extraPaid.costCoupons = costs[0];
            f.extraPaid.costVouchers = costs[1];
            RemoteLedgerRepair.Plan plan = f.plan();
            suspect(6, plan);
            assertTrue(plan.message, plan.message.contains("无法保证删除后可撤销"));
        }
    }

    @Test public void unknownOriginalAmountBlocksAllDeletionCandidates() {
        Fixture f = new Fixture();
        PurchaseRow unknown = paid(ACCOUNT, f.third, 203);
        unknown.costCoupons = -1;
        f.rows.add(unknown);
        suspect(6, f.plan());
    }

    @Test public void conditionSixUnknownRetainedAmountsNeverAuthorizeDeletion() {
        for (int[] costs : new int[][]{{-1, 10}, {10, -1}}) {
            Fixture f = new Fixture();
            f.firstPaid.costCoupons = costs[0];
            f.firstPaid.costVouchers = costs[1];
            suspect(6, f.plan());
        }
    }

    @Test public void negativeRetainedFireCannotCancelPositiveExtraFire() {
        Fixture f = new Fixture();
        f.firstPaid.costCoupons = -10;
        f.extraPaid.costCoupons = 10;
        assertEquals(0, f.firstPaid.costCoupons + f.extraPaid.costCoupons);
        assertTrue(f.firstPaid.costVouchers > 0 && f.extraPaid.costVouchers > 0);
        suspect(6, f.plan());
    }

    @Test public void duplicateCatalogPositionsFailClosed() {
        Fixture f = new Fixture();
        f.extra.chapterNo = 1;
        suspect(6, f.plan());
    }

    @Test public void aRemoteChapterThatCannotBeUniquelyMappedNeverDeletes() {
        Fixture f = new Fixture();
        f.third.title = f.first.title;
        suspect(6, f.plan());
    }

    @Test public void aRemoteEntryStillPresentDoesNotBecomeADeletionCandidate() {
        Fixture f = new Fixture();
        f.aggregate = aggregate(2);
        f.detail = complete(Arrays.asList(entry(1, "甲"), entry(2, "乙")));
        suspect(0, f.plan());
    }

    @Test public void conditionSevenAnotherPaidOwnerAlwaysBlocksDeletion() {
        Fixture f = new Fixture();
        f.rows.add(paid(ACCOUNT + 1, f.extra, 203));
        suspect(7, f.plan());
        assertTrue(f.plan().message.contains("这一章在别的号名下也有记录，不自动删"));
    }

    @Test public void conditionSevenAnotherAccountsFreeOwnedAlsoBlocksDeletion() {
        Fixture f = new Fixture();
        PurchaseRow other = paid(ACCOUNT + 1, f.extra, 203);
        other.costVouchers = 0;
        other.source = Purchase.SRC_OWNED;
        f.rows.add(other);
        suspect(7, f.plan());
    }

    @Test public void anotherAccountsFreeOwnedOnARetainedChapterIsNotAPaidConflict() {
        Fixture f = new Fixture();
        PurchaseRow other = paid(ACCOUNT + 1, f.first, 203);
        other.costVouchers = 0;
        other.source = Purchase.SRC_OWNED;
        f.rows.add(other);
        assertTrue(f.plan().message, f.plan().ok);
    }

    @Test public void anotherPaidAccountOnARetainedChapterDoesNotMakeThatFactAnError() {
        Fixture f = new Fixture();
        f.rows.add(paid(ACCOUNT + 1, f.first, 203));
        RemoteLedgerRepair.Plan plan = f.plan();
        assertTrue(plan.message, plan.ok);
        assertEquals(1, plan.deletions.size());
        assertEquals(f.extra.id, plan.deletions.get(0).chapterId);
    }

    @Test public void duplicatedFactsWithinOneAccountStillInvalidateDeletionEvidence() {
        Fixture f = new Fixture();
        f.rows.add(paid(ACCOUNT, f.first, 203));
        suspect(6, f.plan());
    }

    @Test public void equalCountsWithDifferentChaptersAreOnlySuspect() {
        Fixture f = new Fixture();
        f.aggregate = aggregate(2);
        f.detail = complete(Arrays.asList(entry(1, "甲"), entry(3, "丙")));
        suspect(0, f.plan());
        assertTrue(f.plan().message.contains("漏记 1"));
    }

    @Test public void fewerRemoteChaptersStillDoNotPermitMixedBackfillAndDelete() {
        Fixture f = new Fixture();
        Chapter fourth = chapter(4, "丁");
        f.chapters.add(fourth);
        f.rows.add(paid(ACCOUNT, fourth, 204));
        f.aggregate = aggregate(2);
        f.detail = complete(Arrays.asList(entry(1, "甲"), entry(3, "丙")));
        suspect(0, f.plan());
        assertTrue(f.plan().message.contains("远端缺席 2"));
    }

    @Test public void oneUnsafeCandidatePreventsEveryDeletionInTheBatch() {
        Fixture f = new Fixture();
        PurchaseRow fresh = paid(ACCOUNT, f.third, 203);
        fresh.purchasedAt = NOW - 1;
        f.rows.add(fresh);
        suspect(5, f.plan());
    }

    @Test public void retainedMoneyMismatchIsNotHiddenByRemovingExtraRows() {
        Fixture f = new Fixture();
        f.firstPaid.costVouchers = 12;
        suspect(0, f.plan());
    }

    @Test public void printedVolumeNumbersResolveToGlobalChapterIds() {
        Fixture f = new Fixture();
        f.first.chapterNo = 99;
        f.first.title = "第6章 薯片";
        f.firstPaid.chapterNo = 99;
        f.firstPaid.chapterTitle = f.first.title;
        f.detail = complete(Collections.singletonList(entry(6, "薯片")));
        assertTrue(f.plan().message, f.plan().ok);
        assertEquals(f.extra.id, f.plan().deletions.get(0).chapterId);
    }

    @Test public void aSecondCompleteReadCanBeLaterWithoutChangingItsEvidence() {
        Fixture f = new Fixture();
        RemoteLedgerRepair.Plan first = f.plan();
        RemoteLedgerRepair.Plan second = f.planAt(NOW + 1_000);
        assertTrue(first.message, first.ok);
        assertTrue(second.message, second.ok);
        assertTrue(RemoteLedgerRepair.sameDeletionPlan(first, second));
    }

    @Test public void secondReadWithChangedTransactionFactsCannotConfirmTheFirst() {
        Fixture f = new Fixture();
        RemoteLedgerRepair.Plan first = f.plan();
        f.firstPaid.costVouchers = 12;
        f.detail = complete(Collections.singletonList(SubscribedDetail.parseRow(
                "卷一 第1章 甲", "12", "代券", DAY)));
        RemoteLedgerRepair.Plan second = f.planAt(NOW + 1_000);
        assertTrue(second.message, second.ok);
        assertFalse(RemoteLedgerRepair.sameDeletionPlan(first, second));
    }

    @Test public void secondReadWithChangedOriginalPurchaseCannotConfirmTheFirst() {
        Fixture f = new Fixture();
        RemoteLedgerRepair.Plan first = f.plan();
        f.extraPaid.costVouchers = 11;
        RemoteLedgerRepair.Plan second = f.planAt(NOW + 1_000);
        assertTrue(second.message, second.ok);
        assertFalse(RemoteLedgerRepair.sameDeletionPlan(first, second));
    }

    @Test public void identicalRemoteTextMappingToADifferentLocalChapterIsNotTheSameEvidence() {
        Fixture f = new Fixture();
        RemoteLedgerRepair.Plan first = f.plan();
        f.first.id = 501;
        f.firstPaid.chapterId = 501;
        RemoteLedgerRepair.Plan second = f.planAt(NOW + 1_000);
        assertTrue(second.message, second.ok);
        assertFalse(RemoteLedgerRepair.sameDeletionPlan(first, second));
    }

    @Test public void secondIncompleteReadCannotConfirmTheFirst() {
        Fixture f = new Fixture();
        RemoteLedgerRepair.Plan first = f.plan();
        f.detail = new SubscribedDetail.ReadResult(f.detail.entries, true, true, false,
                false, 0, 1, 1, "滚动失败");
        assertFalse(RemoteLedgerRepair.sameDeletionPlan(first, f.planAt(NOW + 1_000)));
    }

    @Test public void plannedRowsAndChaptersDoNotAliasTheCallersMutableSnapshot() {
        Fixture f = new Fixture();
        RemoteLedgerRepair.Plan plan = f.plan();
        f.extraPaid.costVouchers = 999;
        f.extra.title = "被改过的标题";
        assertEquals(10, plan.deletions.get(0).costVouchers);
        assertEquals("第2章 乙", plan.chapters.get(1).title);
        assertEquals(10, plan.allRowsOfNovel.get(1).costVouchers);
    }

    @Test public void recoveryStillRejectsMixedDifferencesAndAllowsExistingFreeOwners() {
        Fixture f = new Fixture();
        RemoteLedgerRecovery.Plan mixed = RemoteLedgerRecovery.plan("甲号", "测试书", ACCOUNT,
                2, Arrays.asList(entry(1, "甲"), entry(3, "丙")), f.chapters, f.rows);
        assertFalse(mixed.message, mixed.ok);
        assertTrue(mixed.purchases.isEmpty());

        PurchaseRow free = paid(ACCOUNT + 1, f.first, 303);
        free.costVouchers = 0;
        free.source = Purchase.SRC_OWNED;
        RemoteLedgerRecovery.Plan restored = RemoteLedgerRecovery.plan("甲号", "测试书", ACCOUNT,
                1, Collections.singletonList(entry(1, "甲")), f.chapters,
                Collections.singletonList(free));
        assertTrue(restored.message, restored.ok);
        assertEquals(1, restored.purchases.size());
    }
}
