package com.example.blb.auto;

import com.example.blb.data.Chapter;
import com.example.blb.data.Purchase;
import com.example.blb.data.PurchaseRow;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * v1.1 的长明细恢复必须在再次核对时收敛；从逐屏节点开始验证读取、映射与补账后的复核。
 * 这里只模拟已提交后的账本快照，真实 DAO 的原子性和主键幂等由设备测试另行验证。
 */
public class SubscriptionAuditReadbackTest {
    private static final long ACCOUNT = 3;
    private static final long OTHER_ACCOUNT = 8;
    private static final long NOW = RemoteLedgerRecovery.date("2026-09-15");

    @Test public void anIndependentSecondReadFindsNoBackfillGapAndKeepsOtherAccountsHistory()
            throws Exception {
        List<Chapter> chapters = chapters();
        VoucherLedger.Reading aggregate = aggregate();
        SubscribedDetail.ReadResult first = read(chapters);
        assertNull(first.describe(), SubscriptionAuditPolicy.detailProblem(aggregate, first));

        PurchaseRow other = ledgerRow(OTHER_ACCOUNT, chapters.get(0), 10,
                RemoteLedgerRecovery.date("2026-09-08"));
        List<PurchaseRow> before = Collections.singletonList(other);
        RemoteLedgerRecovery.Plan plan = RemoteLedgerRecovery.plan("合成账号", "合成书", ACCOUNT,
                aggregate.chapters, first.entries, chapters, before);
        assertTrue(plan.message, plan.ok);
        assertEquals(20, plan.purchases.size());
        assertEquals("其他号的真实历史不能被补账计划当成当前号已买", 1, before.size());

        List<PurchaseRow> after = new ArrayList<>(before);
        for (Purchase purchase : plan.purchases) {
            Chapter chapter = find(chapters, purchase.chapterId);
            PurchaseRow row = ledgerRow(purchase.accountId, chapter, purchase.costVouchers,
                    purchase.purchasedAt);
            row.costCoupons = purchase.costCoupons;
            row.source = purchase.source;
            after.add(row);
        }
        VoucherLedger.Audit firstCheck = SubscriptionAuditPolicy.reconcileComplete(
                "合成账号", "合成书", ACCOUNT, aggregate, first, chapters, after, NOW);
        assertTrue(firstCheck.message, firstCheck.ok && firstCheck.checked);

        // 第二次重新采集全部节点，不能直接复用第一次生成的成功 ReadResult。
        SubscribedDetail.ReadResult second = read(chapters);
        int mine = 0;
        for (PurchaseRow row : after) if (row.accountId == ACCOUNT) mine++;
        assertEquals(aggregate.chapters, mine);
        VoucherLedger.Audit checked = SubscriptionAuditPolicy.reconcileComplete(
                "合成账号", "合成书", ACCOUNT, aggregate, second, chapters, after, NOW);
        assertTrue(checked.message, checked.ok && checked.checked);
        RemoteLedgerRecovery.Plan repeated = RemoteLedgerRecovery.plan("合成账号", "合成书",
                ACCOUNT, aggregate.chapters, second.entries, chapters, after);
        assertTrue("已补齐快照不得再次生成购买记录", repeated.purchases.isEmpty());
        assertEquals(21, after.size());
        assertSame(other, after.get(0));
        assertEquals(OTHER_ACCOUNT, after.get(0).accountId);
        assertEquals(10, after.get(0).costVouchers);
    }

    @Test public void equalCountsAfterReadbackDoNotHideAChangedLocalTransaction() throws Exception {
        List<Chapter> chapters = chapters();
        SubscribedDetail.ReadResult detail = read(chapters);
        List<PurchaseRow> rows = new ArrayList<>();
        for (Chapter chapter : chapters) rows.add(ledgerRow(ACCOUNT, chapter, 10,
                RemoteLedgerRecovery.date("2026-09-08")));
        rows.get(7).costVouchers = 20;
        assertEquals(detail.entries.size(), rows.size());
        VoucherLedger.Audit checked = SubscriptionAuditPolicy.reconcileComplete(
                "合成账号", "合成书", ACCOUNT, aggregate(), detail, chapters, rows, NOW);
        assertFalse("数量一致不能掩盖金额不同", checked.ok && checked.checked);
    }

    private static SubscribedDetail.ReadResult read(List<Chapter> chapters) throws Exception {
        return SubscribedDetail.collectWithEvidence(SubscribedDetailPipelineTest.twenty(), chapters, 20);
    }

    private static VoucherLedger.Reading aggregate() {
        return new VoucherLedger.Reading(true, 20, 0, -1, null, "20章节 - 0火券");
    }

    private static List<Chapter> chapters() {
        List<Chapter> chapters = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            Chapter chapter = new Chapter();
            chapter.id = 1_000 + i;
            chapter.novelId = 1;
            chapter.chapterNo = 80 + i;
            chapter.title = "第" + i + "章 标题" + i;
            chapter.volumeTitle = "卷一";
            chapters.add(chapter);
        }
        return chapters;
    }

    private static Chapter find(List<Chapter> chapters, long id) {
        for (Chapter chapter : chapters) if (chapter.id == id) return chapter;
        throw new AssertionError("补记没有对应目录行：" + id);
    }

    private static PurchaseRow ledgerRow(long account, Chapter chapter, int amount, long purchasedAt) {
        PurchaseRow row = new PurchaseRow();
        row.purchaseId = account * 10_000 + chapter.id;
        row.accountId = account;
        row.chapterId = chapter.id;
        row.chapterNo = chapter.chapterNo;
        row.chapterTitle = chapter.title;
        row.costCoupons = 0;
        row.costVouchers = amount;
        row.purchasedAt = purchasedAt;
        row.source = Purchase.SRC_REMOTE_DETAIL;
        return row;
    }
}
