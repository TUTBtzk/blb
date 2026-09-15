package com.example.blb.auto;

import com.example.blb.data.AccountNovelAudit;
import com.example.blb.data.Chapter;
import com.example.blb.data.PurchaseRow;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class SubscriptionAuditPolicyTest {
    private static final long TODAY = 1_000_000_000L;
    private static final long NOW = TODAY + 3_600_000L;

    @Test public void absentBookIsNeverProofOfZeroSubscriptions() {
        assertEquals("清单里没有这本书那一行，这一次没能核对",
                SubscriptionAuditPolicy.readingProblem(VoucherLedger.Reading.missingRow()));
        assertNotNull(SubscriptionAuditPolicy.readingProblem(null));
    }

    @Test public void unknownFireOrCountCannotAuthorizePurchases() {
        assertNotNull(SubscriptionAuditPolicy.readingProblem(
                new VoucherLedger.Reading(true, -1, 0, -1, null, null)));
        assertNotNull(SubscriptionAuditPolicy.readingProblem(
                new VoucherLedger.Reading(true, 0, -1, -1, null, null)));
        assertNotNull(SubscriptionAuditPolicy.readingProblem(
                new VoucherLedger.Reading(true, 0, 2, -1, null, null)));
        assertNull(SubscriptionAuditPolicy.readingProblem(summary(0)));
    }

    @Test public void matchingCountsDoNotMakeATruncatedReadComplete() {
        SubscribedDetail.Entry entry = entry(1, "开端", 20, "代券", "2026-09-10");
        SubscribedDetail.ReadResult partial = new SubscribedDetail.ReadResult(
                Collections.singletonList(entry), true, true, false, true, 0, 2, 1, "达到滚动上限");
        assertNotNull(SubscriptionAuditPolicy.detailProblem(summary(1), partial));
        assertNotNull(SubscriptionAuditPolicy.detailProblem(summary(1), null));
    }

    @Test public void aggregateMustIndependentlyAgreeWithTheCompleteDetail() {
        SubscribedDetail.ReadResult detail = detail(entry(1, "开端", 20, "代券", "2026-09-10"));
        assertNull(SubscriptionAuditPolicy.detailProblem(summary(1), detail));
        assertNotNull(SubscriptionAuditPolicy.detailProblem(summary(2), detail));
    }

    @Test public void intactSameDayEvidenceCanOnlyBeReusedWhenNoMoneyWillBeSpent() {
        AccountNovelAudit previous = previous();
        assertFalse(required(false, 1, 1, previous, "1:9", false));
        assertTrue(required(false, 1, 1, previous, "1:9", true));
        assertTrue(required(true, 1, 1, previous, "1:9", false));
    }

    @Test public void eachReusePreconditionIsNecessary() {
        assertTrue(required(false, 2, 1, previous(), "1:9", false));
        assertTrue(required(false, -1, 1, previous(), "1:9", false));
        assertTrue(required(false, 1, 1, null, "1:9", false));
        assertTrue(required(false, 1, 1, previous(), "1:10", false));
        assertTrue(required(false, 1, 1, previous(), null, false));
        AccountNovelAudit previous = previous();
        previous.detailAt = TODAY - 1;
        assertTrue(required(false, 1, 1, previous, "1:9", false));
        previous.detailAt = NOW + 1;
        assertTrue(required(false, 1, 1, previous, "1:9", false));
        previous = previous();
        previous.detailChapters = -1;
        assertTrue(required(false, 1, 1, previous, "1:9", false));
        previous = previous();
        previous.ledgerMarker = null;
        assertTrue(required(false, 1, 1, previous, "1:9", false));
    }

    @Test public void aMatchingCountWithDifferentChaptersIsUnverified() {
        Chapter one = chapter(1, "1 开端");
        Chapter two = chapter(2, "2 重逢");
        VoucherLedger.Audit audit = SubscriptionAuditPolicy.reconcileComplete("甲", "书", 1,
                summary(1), detail(entry(1, "开端", 20, "代券", "2026-09-10")),
                Arrays.asList(one, two), Collections.singletonList(row(two, 1)), NOW);
        assertFalse(audit.ok && audit.checked);
    }

    @Test public void verifiedServerFactReceivesProofEvenWhenAnotherAccountOwnsTheChapter() {
        Chapter one = chapter(1, "1 开端");
        VoucherLedger.Audit audit = SubscriptionAuditPolicy.reconcileComplete("甲", "书", 1,
                summary(1), detail(entry(1, "开端", 20, "代券", "2026-09-10")),
                Collections.singletonList(one), Arrays.asList(row(one, 1), row(one, 2)), NOW);
        assertTrue(audit.message, audit.ok);
        assertTrue(audit.message, audit.checked);
    }

    @Test public void unknownTransactionFactsAreNotHiddenByMatchingChapterNames() {
        Chapter one = chapter(1, "1 开端");
        VoucherLedger.Audit audit = SubscriptionAuditPolicy.reconcileComplete("甲", "书", 1,
                summary(1), detail(entry(1, "开端", -1, null, null)),
                Collections.singletonList(one), Collections.singletonList(row(one, 1)), NOW);
        assertFalse(audit.ok && audit.checked);
    }

    @Test public void completeConsistentFactsReceiveAPositiveProof() {
        Chapter one = chapter(1, "1 开端");
        VoucherLedger.Audit audit = SubscriptionAuditPolicy.reconcileComplete("甲", "书", 1,
                summary(1), detail(entry(1, "开端", 20, "代券", "2026-09-10")),
                Collections.singletonList(one), Collections.singletonList(row(one, 1)),
                RemoteLedgerRecovery.date("2026-09-14"));
        assertTrue(audit.ok);
        assertTrue(audit.checked);
    }

    @Test public void aMappedExtraCanReceiveTheSameCompletePurchaseProofAsANumberedChapter() {
        Chapter extra = chapter(612, "番外 藏在地下室的恶鬼（上）");
        extra.volumeTitle = "番外";
        SubscribedDetail.Entry raw = SubscribedDetail.parseRow("番外 " + extra.title,
                "20", "代券", "2026-09-10");
        SubscribedDetail.Entry identified = SubscribedDetail.identifyUnnumbered(raw,
                Collections.singletonList(extra));
        assertNotNull(identified);
        assertEquals(-1, identified.chapterNo);
        VoucherLedger.Audit checked = SubscriptionAuditPolicy.reconcileComplete("甲", "书", 1,
                summary(1), detail(identified), Collections.singletonList(extra),
                Collections.singletonList(row(extra, 1)), RemoteLedgerRecovery.date("2026-09-14"));
        assertTrue(checked.message, checked.ok);
        assertTrue(checked.message, checked.checked);
        extra.volumeTitle = null;
        VoucherLedger.Audit unknownVolume = SubscriptionAuditPolicy.reconcileComplete("甲", "书", 1,
                summary(1), detail(identified), Collections.singletonList(extra),
                Collections.singletonList(row(extra, 1)), RemoteLedgerRecovery.date("2026-09-14"));
        assertTrue(unknownVolume.message, unknownVolume.ok);
        assertFalse(unknownVolume.message, unknownVolume.checked);
    }

    @Test public void unknownLocalFireIsNotZeroEvenWhenTheOtherFactsMatch() {
        Chapter one = chapter(1, "1 开端");
        PurchaseRow unknown = row(one, 1);
        unknown.costCoupons = -1;
        assertNotNull(SubscriptionAuditPolicy.localMoneyProblem(1, Collections.singletonList(unknown)));
        assertNull(SubscriptionAuditPolicy.localMoneyProblem(2, Collections.singletonList(unknown)));
        VoucherLedger.Audit audit = SubscriptionAuditPolicy.reconcileComplete("甲", "书", 1,
                summary(1), detail(entry(1, "开端", 20, "代券", "2026-09-10")),
                Collections.singletonList(one), Collections.singletonList(unknown),
                RemoteLedgerRecovery.date("2026-09-14"));
        assertFalse(audit.ok && audit.checked);
    }

    @Test public void oneNegativeRowCannotCancelARealFireExpenseInASum() {
        PurchaseRow paid = row(chapter(1, "1 开端"), 1);
        PurchaseRow unknown = row(chapter(2, "2 重逢"), 1);
        paid.costCoupons = 1;
        unknown.costCoupons = -1;
        assertTrue(SubscriptionAuditPolicy.hasFireEvidence(1, summary(2), null,
                Arrays.asList(paid, unknown)));
        assertFalse(SubscriptionAuditPolicy.hasFireEvidence(2, summary(2), null,
                Arrays.asList(paid, unknown)));
        assertTrue(SubscriptionAuditPolicy.hasFireEvidence(1, summary(1),
                detail(entry(1, "开端", -1, "火券", null)), Collections.emptyList()));
    }

    private static boolean required(boolean force, int aggregate, int paid,
                                    AccountNovelAudit previous, String marker, boolean spending) {
        return SubscriptionAuditPolicy.mustReadDetail(force, aggregate, paid, previous, marker,
                TODAY, NOW, spending);
    }

    private static AccountNovelAudit previous() {
        AccountNovelAudit previous = new AccountNovelAudit();
        previous.detailAt = TODAY + 1;
        previous.detailChapters = 1;
        previous.ledgerMarker = "1:9";
        return previous;
    }

    private static VoucherLedger.Reading summary(int count) {
        return new VoucherLedger.Reading(true, count, 0, -1, null, count + "章节 - 0火券");
    }

    private static SubscribedDetail.Entry entry(int no, String title, int amount,
                                                String currency, String date) {
        return new SubscribedDetail.Entry(no, "卷一", title, amount, currency, date,
                "卷一 " + no + " " + title);
    }

    private static SubscribedDetail.ReadResult detail(SubscribedDetail.Entry entry) {
        return new SubscribedDetail.ReadResult(Collections.singletonList(entry),
                true, true, true, false, 0, 1, 1, "连续覆盖全部集合条目");
    }

    private static Chapter chapter(int no, String title) {
        Chapter chapter = new Chapter();
        chapter.id = no;
        chapter.novelId = 1;
        chapter.chapterNo = no;
        chapter.title = title;
        return chapter;
    }

    private static PurchaseRow row(Chapter chapter, long account) {
        PurchaseRow row = new PurchaseRow();
        row.purchaseId = account * 10 + chapter.id;
        row.accountId = account;
        row.chapterId = chapter.id;
        row.chapterNo = chapter.chapterNo;
        row.chapterTitle = chapter.title;
        row.costVouchers = 20;
        row.source = "AUTO";
        row.purchasedAt = RemoteLedgerRecovery.date("2026-09-10");
        return row;
    }
}
