package com.example.blb.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.database.sqlite.SQLiteException;

import com.example.blb.auto.RemoteLedgerRepair;
import com.example.blb.auto.SubscribedDetail;
import com.example.blb.auto.VoucherLedger;

import org.junit.Test;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 真正落库时仍要守住证据与回滚，不能只靠纯函数测试掩盖事务中的半批写入。 */
public class AuditDaoTest extends DbTestBase {
    private static final String DAY = "2026-09-01";
    private static final long OLD = day(DAY);
    private static final long NOW = day("2026-09-14") + 12 * 60 * 60_000L;

    private static long day(String value) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(value).getTime();
        } catch (ParseException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private final class Fixture {
        final AuditDao audit = db.auditDao();
        final long mine = newAccount("mine@test", 0, 100, true, 1);
        final long other = newAccount("other@test", 0, 100, true, 2);
        final long novel = newNovel("目标书", true);
        final long first = chapter(novel, 1, "甲");
        final long extra = chapter(novel, 2, "乙");
        final long free = chapter(novel, 3, "丙");
        final long spare = chapter(novel, 4, "丁");
        final long firstId = old(mine, first, 10, Purchase.SRC_AUTO);
        final long extraId = old(mine, extra, 10, Purchase.SRC_AUTO);
        final long freeId = old(mine, free, 0, Purchase.SRC_OWNED);
        final long anotherNovel = newNovel("另一书", false);
        final long anotherChapter = chapter(anotherNovel, 1, "戊");
        final long unrelatedId = old(other, anotherChapter, 10, Purchase.SRC_AUTO);
        List<Chapter> chapters;
        List<PurchaseRow> paid;
        List<PurchaseRow> all;
        RemoteLedgerRepair.Plan firstPlan;
        RemoteLedgerRepair.Plan secondPlan;

        void capture() {
            chapters = subs.loadChapters(novel);
            paid = subs.loadPaidRowsOfNovel(novel);
            all = audit.loadAllRowsOfNovel(novel);
            firstPlan = plan(VoucherLedger.parseSummary("1章节 - 0火券", null), NOW);
            secondPlan = plan(VoucherLedger.parseSummary("1章节 - 0火券", null), NOW + 1_000);
            assertTrue(firstPlan.message, firstPlan.ok);
            assertTrue(secondPlan.message, secondPlan.ok);
        }

        RemoteLedgerRepair.Plan plan(VoucherLedger.Reading aggregate, long now) {
            SubscribedDetail.Entry entry = SubscribedDetail.parseRow(
                    "卷一 第1章 甲", "10", "代券", DAY);
            assertNotNull(entry);
            SubscribedDetail.ReadResult detail = new SubscribedDetail.ReadResult(
                    Collections.singletonList(entry), true, true, true, false,
                    0, 1, 1, "列表项从 0 完整覆盖到末项");
            return RemoteLedgerRepair.deletionPlan("mine@test", "目标书", mine,
                    aggregate, detail, chapters, all, now);
        }

        AuditDao.RepairResult commit() {
            return audit.commitDeletion(firstPlan, secondPlan, chapters, paid, all, NOW + 2_000);
        }

        LedgerAudit deleteExtra() {
            capture();
            AuditDao.RepairResult result = commit();
            assertTrue(result.message, result.ok);
            assertEquals(1, result.deleted);
            List<LedgerAudit> events = audit.loadRecentLedgerAudits(novel, 10);
            assertEquals(1, events.size());
            assertEquals(LedgerAudit.KIND_DELETE, events.get(0).kind);
            return events.get(0);
        }

        void assertOnlySuspect() {
            List<LedgerAudit> events = audit.loadRecentLedgerAudits(novel, 10);
            assertEquals(1, events.size());
            assertEquals(LedgerAudit.KIND_SUSPECT, events.get(0).kind);
            assertNotNull(audit.purchaseById(extraId));
        }
    }

    private long chapter(long novel, int no, String title) {
        return subs.ensureChapter(novel, no, "第" + no + "章 " + title, 10).id;
    }

    private long old(long account, long chapter, int vouchers, String source) {
        Purchase row = Purchase.of(account, chapter, 0, vouchers, source);
        row.purchasedAt = OLD;
        return subs.upsertPurchase(row);
    }

    private void progress(AuditDao audit, long account, long novel) {
        AccountNovelAudit row = new AccountNovelAudit();
        row.accountId = account;
        row.novelId = novel;
        row.aggregateAt = NOW;
        row.aggregateChapters = 1;
        row.detailAt = NOW;
        row.detailChapters = 1;
        row.ledgerMarker = audit.paidLedgerMarker(account, novel);
        audit.saveProgress(row);
    }

    /**
     * 2026-09-15 用户要的「一键清理」：只清这本书的留痕，购买记录、章节、核对凭证一条不动。
     *
     * <p>为什么这几条断言都要有：清掉 {@code ledger_audit} 只该丢「撤销的依据」，
     * 不该让任何账本事实变化 —— 也不该把别的书的存疑记录一起带走。
     */
    @Test
    public void clearingOneNovelRemovesOnlyItsOwnAuditTrail() {
        Fixture f = new Fixture();
        long otherNovelAudit = f.audit.insertLedgerAudit(suspectOf(f.other, f.anotherNovel, 1));
        f.audit.insertLedgerAudit(suspectOf(f.mine, f.novel, 1));
        f.audit.insertLedgerAudit(suspectOf(f.mine, f.novel, 2));
        progress(f.audit, f.mine, f.novel);
        String markerBefore = f.audit.paidLedgerMarker(f.mine, f.novel);
        int paidBefore = subs.countPaidPurchases(f.mine, f.novel);

        assertEquals(2, f.audit.deleteLedgerAuditsOfNovel(f.novel));

        assertTrue(f.audit.loadRecentLedgerAudits(f.novel, 10).isEmpty());
        assertEquals("别的书的存疑记录不许一起清掉", 1,
                f.audit.loadRecentLedgerAudits(f.anotherNovel, 10).size());
        assertNotNull("别的书的留痕还在", f.audit.ledgerAuditById(otherNovelAudit));
        assertEquals("购买记录一条都不能动", paidBefore, subs.countPaidPurchases(f.mine, f.novel));
        assertNotNull(f.audit.purchaseById(f.extraId));
        assertEquals("已登记的章节不许动", 4, subs.loadChapters(f.novel).size());
        assertEquals("核对凭证（购买闸门）不许动", markerBefore,
                f.audit.paidLedgerMarker(f.mine, f.novel));
        assertNotNull("核对进度不许动", f.audit.auditFor(f.mine, f.novel));
        assertEquals("清第二遍没有可删的", 0, f.audit.deleteLedgerAuditsOfNovel(f.novel));
    }

    private static LedgerAudit suspectOf(long account, long novel, int chapterNo) {
        LedgerAudit audit = new LedgerAudit();
        audit.at = NOW;
        audit.accountId = account;
        audit.novelId = novel;
        audit.kind = LedgerAudit.KIND_SUSPECT;
        audit.chapterNo = chapterNo;
        audit.title = "第" + chapterNo + "章";
        audit.detail = "";
        return audit;
    }

    @Test
    public void twoIndependentReadsDeleteOnlyTheProvenOldExtraFact() {
        Fixture f = new Fixture();
        long foreignFree = old(f.other, f.first, 0, Purchase.SRC_OWNED);
        Purchase original = f.audit.purchaseById(f.extraId);
        progress(f.audit, f.mine, f.novel);
        progress(f.audit, f.other, f.novel);
        LedgerAudit deleted = f.deleteExtra();

        assertNull(f.audit.purchaseById(f.extraId));
        assertNotNull(f.audit.purchaseById(f.firstId));
        assertNotNull(f.audit.purchaseById(f.freeId));
        assertNotNull(f.audit.purchaseById(foreignFree));
        assertNotNull(f.audit.purchaseById(f.unrelatedId));
        assertEquals(f.extra, deleted.chapterId);
        assertEquals(2, deleted.chapterNo);
        assertTrue(LedgerWritePolicy.samePurchase(original,
                LedgerAuditPayload.parsedPurchase(deleted.detail)));
        assertEquals(0L, LedgerAuditPayload.deleteAuditId(deleted.detail));
        assertNull(f.audit.auditFor(f.mine, f.novel).ledgerMarker);
        assertNotNull(f.audit.auditFor(f.other, f.novel).ledgerMarker);
        assertEquals(NOW, f.audit.auditFor(f.mine, f.novel).detailAt);
    }

    @Test
    public void restoreKeepsTheOriginalIdMoneyTimeAndSourceAndIsIdempotent() {
        Fixture f = new Fixture();
        Purchase original = f.audit.purchaseById(f.extraId);
        LedgerAudit deleted = f.deleteExtra();
        progress(f.audit, f.mine, f.novel);
        progress(f.audit, f.other, f.novel);

        assertTrue(f.audit.restore(deleted.id).contains("已撤销修正"));
        assertTrue(LedgerWritePolicy.samePurchase(original, f.audit.purchaseById(f.extraId)));
        assertTrue(f.audit.restore(deleted.id).contains("已经撤销"));
        List<LedgerAudit> events = f.audit.loadRecentLedgerAudits(f.novel, 10);
        assertEquals(2, events.size());
        LedgerAudit restored = null;
        for (LedgerAudit event : events) {
            if (LedgerAudit.KIND_RESTORE.equals(event.kind)) restored = event;
        }
        assertNotNull(restored);
        assertEquals(LedgerAudit.KIND_RESTORE, restored.kind);
        assertEquals(deleted.id, LedgerAuditPayload.deleteAuditId(restored.detail));
        assertTrue(LedgerWritePolicy.samePurchase(original,
                LedgerAuditPayload.parsedPurchase(restored.detail)));
        assertNull(f.audit.auditFor(f.mine, f.novel).ledgerMarker);
        assertNull(f.audit.auditFor(f.other, f.novel).ledgerMarker);
        assertEquals(NOW, f.audit.auditFor(f.other, f.novel).detailAt);
    }

    @Test
    public void changedSecondReadingNeverAuthorizesDeletionEvenWithTheSameCount() {
        Fixture f = new Fixture();
        f.capture();
        f.secondPlan = f.plan(VoucherLedger.parseSummary("1章节 - 0火券", "2026-09-02"), NOW + 1_000);
        assertTrue(f.secondPlan.message, f.secondPlan.ok);

        AuditDao.RepairResult result = f.commit();
        assertFalse(result.message, result.ok);
        assertEquals(0, result.deleted);
        assertTrue(LedgerWritePolicy.sameRows(f.all, f.audit.loadAllRowsOfNovel(f.novel)));
        f.assertOnlySuspect();
    }

    @Test
    public void priceAndRemoteChapterIdChangesInvalidateTheFullCatalogSnapshot() {
        Fixture f = new Fixture();
        f.capture();
        Chapter changed = subs.chapterById(f.extra);
        changed.priceCoupons++;
        subs.updateChapter(changed);
        assertFalse(f.audit.snapshotMatches(f.novel, f.chapters, f.paid, f.all));
        changed.priceCoupons--;
        changed.sfChapterId = "new-remote-id";
        subs.updateChapter(changed);
        assertFalse(f.audit.snapshotMatches(f.novel, f.chapters, f.paid, f.all));

        assertFalse(f.commit().ok);
        f.assertOnlySuspect();
    }

    @Test
    public void anInPlaceMoneyEditCannotHideBehindAnUnchangedShortMarker() {
        Fixture f = new Fixture();
        f.capture();
        String marker = f.audit.paidLedgerMarker(f.mine, f.novel);
        // 只在隔离内存库模拟旧版手改；生产入口已经禁止这样覆盖购买事实。
        db.getOpenHelper().getWritableDatabase().execSQL(
                "UPDATE purchase SET cost_vouchers = 11 WHERE id = ?", new Object[]{f.extraId});
        assertEquals(marker, f.audit.paidLedgerMarker(f.mine, f.novel));

        assertFalse(f.commit().ok);
        assertEquals(11, f.audit.purchaseById(f.extraId).costVouchers);
        f.assertOnlySuspect();
    }

    @Test
    public void anotherAccountsNewOwnedFactInvalidatesTheAllRowsSnapshot() {
        Fixture f = new Fixture();
        f.capture();
        old(f.other, f.extra, 0, Purchase.SRC_OWNED);
        assertTrue(LedgerWritePolicy.sameRows(f.paid, subs.loadPaidRowsOfNovel(f.novel)));

        assertFalse(f.commit().ok);
        f.assertOnlySuspect();
    }

    @Test
    public void suppliedSnapshotsCannotBeSwappedForDifferentOnesAtCommitTime() {
        Fixture f = new Fixture();
        f.capture();
        f.paid = Collections.emptyList();
        assertFalse(f.commit().ok);
        assertEquals(3, subs.loadPurchasesOfNovel(f.novel).size());
        f.assertOnlySuspect();
    }

    @Test
    public void failureOnTheSecondDeleteRollsBackTheFirstDeleteAndBothAuditRows() {
        Fixture f = new Fixture();
        long laterExtra = old(f.mine, f.spare, 10, Purchase.SRC_AUTO);
        f.capture();
        assertEquals(2, f.firstPlan.deletions.size());
        // 刻意让第二条 SQL 失败，才能证明 @Transaction 真正保护了前面的删除与留痕。
        db.getOpenHelper().getWritableDatabase().execSQL(
                "CREATE TRIGGER abort_second_audit_delete BEFORE DELETE ON purchase "
                        + "WHEN OLD.id = " + laterExtra
                        + " BEGIN SELECT RAISE(ABORT, 'test second delete failure'); END");
        try {
            f.commit();
            fail("第二条删除应该失败，不能留下半批修改");
        } catch (SQLiteException expected) {
            assertTrue(expected.getMessage().contains("test second delete failure"));
        }

        assertTrue(LedgerWritePolicy.sameRows(f.all, f.audit.loadAllRowsOfNovel(f.novel)));
        assertTrue(f.audit.loadRecentLedgerAudits(f.novel, 10).isEmpty());
    }

    @Test
    public void restoreNeverOverwritesAnOccupiedOriginalPrimaryKey() {
        Fixture f = new Fixture();
        LedgerAudit deleted = f.deleteExtra();
        rawPurchase(f.extraId, f.mine, f.spare, 10, Purchase.SRC_MANUAL);

        assertTrue(f.audit.restore(deleted.id).contains("原主键已有记录"));
        assertEquals(f.spare, f.audit.purchaseById(f.extraId).chapterId);
        assertEquals(0, subs.countRealPurchase(f.mine, f.extra));
        assertEquals(1, f.audit.loadRecentLedgerAudits(f.novel, 10).size());
    }

    @Test
    public void restoreNeverOverwritesAReplacementFactOfTheSameAccount() {
        Fixture f = new Fixture();
        LedgerAudit deleted = f.deleteExtra();
        long replacement = old(f.mine, f.extra, 11, Purchase.SRC_MANUAL);

        assertTrue(f.audit.restore(deleted.id).contains("这个号已有该章记录"));
        assertNull(f.audit.purchaseById(f.extraId));
        assertEquals(11, f.audit.purchaseById(replacement).costVouchers);
        assertEquals(1, f.audit.loadRecentLedgerAudits(f.novel, 10).size());
    }

    @Test
    public void restoreAllowsAnotherFreeOwnedWithoutCreatingTwoPayingOwners() {
        Fixture f = new Fixture();
        LedgerAudit deleted = f.deleteExtra();
        long freeOwner = old(f.other, f.extra, 0, Purchase.SRC_OWNED);
        assertTrue(f.audit.restore(deleted.id).contains("已撤销修正"));
        assertNotNull(f.audit.purchaseById(freeOwner));
        assertNotNull(f.audit.purchaseById(f.extraId));

    }

    @Test
    public void restoreRejectsAnotherPayingOwnerThatAppearedAfterDeletion() {
        Fixture f = new Fixture();
        LedgerAudit deleted = f.deleteExtra();
        long payingOwner = old(f.other, f.extra, 10, Purchase.SRC_AUTO);
        assertTrue(f.audit.restore(deleted.id).contains("已有其他账号付费"));
        assertNull(f.audit.purchaseById(f.extraId));
        assertNotNull(f.audit.purchaseById(payingOwner));
        assertEquals(1, f.audit.loadRecentLedgerAudits(f.novel, 10).size());
    }

    @Test
    public void restoreRequiresIntactPayloadAndExistingParents() {
        Fixture f = new Fixture();
        LedgerAudit deleted = f.deleteExtra();
        LedgerAudit broken = new LedgerAudit();
        broken.at = NOW;
        broken.accountId = f.mine;
        broken.novelId = f.novel;
        broken.chapterId = f.extra;
        broken.kind = LedgerAudit.KIND_DELETE;
        broken.detail = "原记录缺了金额和日期";
        broken.id = f.audit.insertLedgerAudit(broken);
        assertTrue(f.audit.restore(broken.id).contains("留痕不完整"));
        assertTrue(f.audit.restore(-1).contains("没有找到"));

        subs.deleteChapter(subs.chapterById(f.extra));
        assertTrue(f.audit.restore(deleted.id).contains("原账号或原章节已不存在"));
        assertNull(f.audit.purchaseById(f.extraId));
        for (LedgerAudit event : f.audit.loadRecentLedgerAudits(f.novel, 10)) {
            assertFalse(LedgerAudit.KIND_RESTORE.equals(event.kind));
        }
    }

    @Test
    public void aPreviouslyRestoredThenChangedFactIsNeverReinsertedAgain() {
        Fixture f = new Fixture();
        LedgerAudit deleted = f.deleteExtra();
        assertTrue(f.audit.restore(deleted.id).contains("已撤销修正"));
        db.getOpenHelper().getWritableDatabase().execSQL(
                "DELETE FROM purchase WHERE id = ?", new Object[]{f.extraId});

        assertTrue(f.audit.restore(deleted.id).contains("账本后来又有变化"));
        assertNull(f.audit.purchaseById(f.extraId));
        assertEquals(2, f.audit.loadRecentLedgerAudits(f.novel, 10).size());
    }

    @Test
    public void backfillInvalidatesOnlyTheRepairedAccountAndNormalWritesKeepOtherMarkers() {
        Fixture f = new Fixture();
        progress(f.audit, f.mine, f.novel);
        progress(f.audit, f.other, f.novel);
        Purchase remote = Purchase.of(f.mine, f.spare, 0, 10, Purchase.SRC_REMOTE_DETAIL);
        remote.purchasedAt = OLD;
        assertTrue(subs.restoreRemotePurchases(f.mine, f.novel, Collections.singletonList(remote),
                subs.loadChapters(f.novel), subs.loadPaidRowsOfNovel(f.novel)));
        assertNotNull(f.audit.auditFor(f.mine, f.novel).ledgerMarker);
        PurchaseRow restored = null;
        for (PurchaseRow row : subs.loadPaidRowsOfNovel(f.novel)) {
            if (row.chapterId == f.spare) restored = row;
        }
        assertNotNull(restored);
        PurchaseRow finalRestored = restored;
        db.runInTransaction(() -> {
            f.audit.recordBackfill(f.novel, finalRestored, "账本漏记 1 章，已补回", NOW);
        });

        assertNull(f.audit.auditFor(f.mine, f.novel).ledgerMarker);
        assertNotNull(f.audit.auditFor(f.other, f.novel).ledgerMarker);
        assertEquals(NOW, f.audit.auditFor(f.mine, f.novel).detailAt);
        LedgerAudit event = f.audit.loadRecentLedgerAudits(f.novel, 10).get(0);
        assertEquals(LedgerAudit.KIND_BACKFILL, event.kind);
        assertTrue(LedgerWritePolicy.samePurchase(f.audit.purchaseById(restored.purchaseId),
                LedgerAuditPayload.parsedPurchase(event.detail)));
    }

    /** 原主键碰撞只能在隔离测试库造；业务入口已经禁止指定旧主键补录。 */
    private void rawPurchase(long id, long account, long chapter, int vouchers, String source) {
        db.getOpenHelper().getWritableDatabase().execSQL(
                "INSERT INTO purchase(id, account_id, chapter_id, cost_coupons, cost_vouchers, purchased_at, source) "
                        + "VALUES (?, ?, ?, 0, ?, ?, ?)", new Object[]{id, account, chapter, vouchers, OLD, source});
    }
}
