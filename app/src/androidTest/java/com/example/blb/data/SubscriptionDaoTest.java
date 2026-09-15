package com.example.blb.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.database.sqlite.SQLiteConstraintException;

import org.junit.Test;

import java.util.List;

/** 订阅账本的约束与「下一章该谁买」的判断。这两处错了会买错书或重复花火券。 */
public class SubscriptionDaoTest extends DbTestBase {

    @Test
    public void duplicateChapterNoIsIgnoredRatherThanThrowing() {
        long novel = newNovel("目标书", true);
        long first = newChapter(novel, 1, 20);
        assertTrue(first > 0);
        // OnConflictStrategy.IGNORE：重复插入返回 -1，不炸。
        assertEquals(-1L, newChapter(novel, 1, 30));
        assertEquals(1, subs.loadChapters(novel).size());
    }

    @Test
    public void ensureChapterReusesTheExistingRow() {
        long novel = newNovel("目标书", true);
        Chapter a = subs.ensureChapter(novel, 3, "第三章", 20);
        Chapter b = subs.ensureChapter(novel, 3, "改了名字", 99);
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(a.id, b.id);
        assertEquals("第三章", b.title);
        assertEquals(1, subs.loadChapters(novel).size());
    }

    @Test
    public void exactReplayKeepsTheOriginalFactAndChangedFactsCannotOverwriteIt() {
        long acc = newAccount("a@x.com", 100, true, 1);
        long novel = newNovel("目标书", true);
        long ch = newChapter(novel, 1, 20);

        Purchase original = Purchase.of(acc, ch, 20, Purchase.SRC_AUTO);
        long originalId = subs.upsertPurchase(original);
        assertEquals(originalId, subs.upsertPurchase(original));
        try {
            buy(acc, ch, 25, Purchase.SRC_MANUAL);
            fail("没有核对证据，不能覆盖原来花过的券");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("不能覆盖"));
        }

        List<Purchase> rows = subs.loadPurchasesOfNovel(novel);
        assertEquals(1, rows.size());
        assertEquals(originalId, rows.get(0).id);
        assertEquals(20, rows.get(0).costCoupons);
        assertEquals(original.purchasedAt, rows.get(0).purchasedAt);
        assertEquals(Purchase.SRC_AUTO, rows.get(0).source);
    }

    @Test
    public void purchaseNeedsARealAccountAndChapter() {
        try {
            buy(999, 888, 20, Purchase.SRC_AUTO);
            fail("外键约束没生效，脏账会写进去");
        } catch (SQLiteConstraintException expected) {
            // 正是想要的
        }
    }

    @Test
    public void deletingNovelCannotCascadeAwayPurchaseEvidence() {
        long acc = newAccount("a@x.com", 100, true, 1);
        long novel = newNovel("目标书", true);
        long ch = newChapter(novel, 1, 20);
        buy(acc, ch, 20, Purchase.SRC_AUTO);

        Novel n = subs.novelById(novel);
        try {
            subs.deleteNovel(n);
            fail("删书不能绕过账本的证据与撤销要求");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("订阅"));
        }

        assertNotNull(subs.novelById(novel));
        assertEquals(1, subs.loadChapters(novel).size());
        assertEquals(1, subs.loadPurchasesOfNovel(novel).size());
        assertEquals(1, subs.countRealPurchase(acc, ch));
    }

    @Test
    public void deletingAnUnpurchasedNovelStillRemovesItsEmptyCatalog() {
        long novel = newNovel("空账本", true);
        newChapter(novel, 1, 20);
        subs.deleteNovel(subs.novelById(novel));
        assertNull(subs.novelById(novel));
        assertTrue(subs.loadChapters(novel).isEmpty());
    }

    @Test
    public void directAndChapterCascadeDeletesCannotEraseEvenFreeOwnedEvidence() {
        long account = newAccount("mine@x.com", 0, true, 1);
        long novel = newNovel("目标书", true);
        long chapter = newChapter(novel, 1, 20);
        long id = buy(account, chapter, 0, Purchase.SRC_OWNED);
        try {
            subs.deletePurchase(subs.loadPurchasesOfNovel(novel).get(0));
            fail("直接删记录不能绕过核对留痕");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("核对"));
        }
        try {
            subs.deleteChapter(subs.chapterById(chapter));
            fail("删章不能级联抹掉 OWNED 事实");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("订阅"));
        }
        try {
            subs.deleteChapterById(chapter);
            fail("按 id 删章同样要保护原记录");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("订阅"));
        }
        assertNotNull(subs.chapterById(chapter));
        assertEquals(1, subs.countRealPurchase(account, chapter));
        assertEquals(id, subs.loadPurchasesOfNovel(novel).get(0).id);
    }

    @Test
    public void countRealPurchaseCountsEveryRecord() {
        long acc = newAccount("a@x.com", 100, true, 1);
        long novel = newNovel("目标书", true);
        long ch = newChapter(novel, 1, 20);

        assertEquals("一条都没有的时候是 0", 0, subs.countRealPurchase(acc, ch));

        buy(acc, ch, 20, Purchase.SRC_AUTO);
        assertEquals(1, subs.countRealPurchase(acc, ch));
    }

    @Test
    public void setTargetNovelKeepsExactlyOneTarget() {
        long a = newNovel("甲", true);
        long b = newNovel("乙", false);

        subs.setTargetNovel(b);

        assertEquals(b, subs.targetNovel().id);
        assertTrue(subs.novelById(b).isTarget);
        assertTrue(!subs.novelById(a).isTarget);
    }

    @Test
    public void findNextUnownedChapterSkipsChaptersAnyoneAlreadyBought() {
        long acc1 = newAccount("a@x.com", 100, true, 1);
        long novel = newNovel("目标书", true);
        assertNull("一章都没有时不该给建议", subs.findNextUnownedChapter(novel));

        long ch1 = newChapter(novel, 1, 20);
        long ch2 = newChapter(novel, 2, 20);
        assertEquals(ch1, subs.findNextUnownedChapter(novel).id);

        buy(acc1, ch1, 20, Purchase.SRC_AUTO);
        assertEquals(ch2, subs.findNextUnownedChapter(novel).id);

        buy(acc1, ch2, 20, Purchase.SRC_AUTO);
        assertNull("全买完了就不该再建议", subs.findNextUnownedChapter(novel));
    }

    /**
     * 免费章回填（{@code OWNED}）也算这一章有了归属：一分券都没花，但谁登录都看得到，
     * 不该再有第二个号为它花券。
     */
    @Test
    public void aFreeChapterBackfillAlsoCountsAsOwned() {
        long acc = newAccount("a@x.com", 100, true, 1);
        long novel = newNovel("目标书", true);
        long ch1 = newChapter(novel, 1, 20);
        long ch2 = newChapter(novel, 2, 20);

        buy(acc, ch1, 0, Purchase.SRC_OWNED);

        assertEquals("第 1 章已经有归属了，下一章是第 2 章",
                ch2, subs.findNextUnownedChapter(novel).id);
        assertNull("这个号已经拥有第 1 章，不该再被建议去买它", subs.suggestBuyer(ch1));
    }

    /**
     * 挑号只看<b>代券</b>。火券多的号在这里毫无意义 —— 章节费两种券都能付，但菠萝包先扣代券，
     * 而用户不充值火券，所以「2000 火券、0 代券」那个号排在最前面只会白走一趟。
     */
    @Test
    public void suggestBuyerPicksTheEnabledAccountWithMostVouchers() {
        long poor = newAccount("poor@x.com", 0, 10, true, 1);
        long rich = newAccount("rich@x.com", 0, 500, true, 2);
        long richButOff = newAccount("off@x.com", 0, 900, false, 3);
        long novel = newNovel("目标书", true);
        long ch = newChapter(novel, 1, 20);

        assertEquals(rich, subs.suggestBuyer(ch).id);

        buy(rich, ch, 20, Purchase.SRC_AUTO);
        assertEquals("已经买过的号不该再被建议", poor, subs.suggestBuyer(ch).id);

        try {
            buy(poor, ch, 20, Purchase.SRC_AUTO);
            fail("候选号排序不能授权第二个号重复花券");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("其他账号花过券"));
        }
        assertNull("整本下一章以所有号的归属为准", subs.findNextUnownedChapter(novel));
        assertEquals(1, subs.loadPurchasesOfNovel(novel).size());
        assertEquals(0, subs.countRealPurchase(richButOff, ch));
    }

    /** 火券堆得再高也不该抢到前面：能不能买下一章完全看代券。 */
    @Test
    public void aPileOfFireCoinsDoesNotWinTheTurn() {
        long fireOnly = newAccount("fire@x.com", 2000, 0, true, 1);
        long vouchers = newAccount("voucher@x.com", 0, 15, true, 2);
        long novel = newNovel("目标书", true);
        long ch = newChapter(novel, 1, 20);

        assertEquals(vouchers, subs.suggestBuyer(ch).id);
        assertEquals(fireOnly, subs.suggestBuyers(ch).get(1).id);
    }

    /** 代券并列时按 sort_order 再按 id —— 顺序必须稳定，否则每轮换个号试，白重登。 */
    @Test
    public void suggestBuyerBreaksTiesBySortOrderThenId() {
        long later = newAccount("later@x.com", 0, 100, true, 9);
        long earlier = newAccount("earlier@x.com", 0, 100, true, 2);
        long novel = newNovel("目标书", true);
        long ch = newChapter(novel, 1, 20);

        assertEquals(earlier, subs.suggestBuyer(ch).id);

        buy(earlier, ch, 20, Purchase.SRC_AUTO);
        assertEquals(later, subs.suggestBuyer(ch).id);
    }

    /** 代券还没读到过（-1）的号排在读到 0 的号后面，但仍然是候选 —— 到场再读余额才知道。 */
    @Test
    public void anAccountWithUnknownVouchersIsStillACandidate() {
        long unknown = newAccount("unknown@x.com", 0, true, 1);
        long zero = newAccount("zero@x.com", 0, 0, true, 2);
        long novel = newNovel("目标书", true);
        long ch = newChapter(novel, 1, 20);

        List<Account> buyers = subs.suggestBuyers(ch);
        assertEquals(2, buyers.size());
        assertEquals(zero, buyers.get(0).id);
        assertEquals(unknown, buyers.get(1).id);
    }

    @Test
    public void loadAllRowsCarriesEveryPurchaseForCsvExport() {
        long acc = newAccount("a@x.com", 100, true, 1);
        long novel = newNovel("目标书", true);
        long ch1 = newChapter(novel, 1, 20);
        long ch2 = newChapter(novel, 2, 30);
        buy(acc, ch1, 20, Purchase.SRC_AUTO);
        buy(acc, ch2, 0, Purchase.SRC_OWNED);

        List<PurchaseRow> rows = subs.loadAllRows();
        assertEquals("导出要连免费章回填一起带走，界面上会区分显示", 2, rows.size());
        assertEquals(1, rows.get(0).chapterNo);
        assertEquals("目标书", rows.get(0).novelTitle);
    }

    @Test
    public void novelByTitleAndChapterByNoAreUsedByCsvImport() {
        long novel = newNovel("目标书", true);
        newChapter(novel, 7, 20);

        assertEquals(novel, subs.novelByTitle("目标书").id);
        assertNull(subs.novelByTitle("不存在的书"));
        assertEquals(7, subs.chapterByNo(novel, 7).chapterNo);
        assertNull(subs.chapterByNo(novel, 8));
        assertEquals(7, subs.maxChapterNo(novel));
    }

    private boolean restore(long accountId, long novelId, List<Purchase> purchases) {
        return subs.restoreRemotePurchases(accountId, novelId, purchases,
                subs.loadChapters(novelId), subs.loadPaidRowsOfNovel(novelId));
    }

    /** 2026-09-14 的真实跨号订阅要整批补回；别号既有事实、主键和金额都必须保留。 */
    @Test
    public void remoteRecoveryCommitsTheWholeBatchAlongsideAnotherAccount() {
        long mine = newAccount("mine@x.com", 0, 100, true, 1);
        long other = newAccount("other@x.com", 0, 100, true, 2);
        long novel = newNovel("目标书", true);
        long ch1 = newChapter(novel, 1, 20);
        long ch2 = newChapter(novel, 2, 20);
        long otherId = buy(other, ch2, 0, 20, Purchase.SRC_AUTO);

        Purchase first = Purchase.of(mine, ch1, 0, 20, Purchase.SRC_REMOTE_DETAIL);
        Purchase sharedHistory = Purchase.of(mine, ch2, 0, 20, Purchase.SRC_REMOTE_DETAIL);
        assertTrue(restore(mine, novel, java.util.Arrays.asList(first, sharedHistory)));
        assertEquals(1, subs.countRealPurchase(mine, ch1));
        assertEquals(1, subs.countRealPurchase(mine, ch2));
        assertEquals(1, subs.countRealPurchase(other, ch2));
        assertEquals(3, subs.loadPurchasesOfNovel(novel).size());
        for (Purchase stored : subs.loadPurchasesOfNovel(novel)) {
            if (stored.accountId == other) {
                assertEquals(otherId, stored.id);
                assertEquals(20, stored.costVouchers);
                assertEquals(Purchase.SRC_AUTO, stored.source);
            }
        }
        assertNull(subs.findNextUnownedChapter(novel));
    }

    /** 2026-09-14 跨号事实允许保留；同账号再次恢复必须幂等，不能换主键或覆盖旧金额。 */
    @Test
    public void remoteRecoveryIsIdempotentWhenAnotherPaidAccountAlreadyExists() {
        long mine = newAccount("mine@x.com", 0, 100, true, 1);
        long other = newAccount("other@x.com", 0, 100, true, 2);
        long novel = newNovel("目标书", true);
        long chapter = newChapter(novel, 1, 20);
        buy(other, chapter, 0, 20, Purchase.SRC_AUTO);
        Purchase recovered = Purchase.of(mine, chapter, 0, 20,
                Purchase.SRC_REMOTE_DETAIL);
        recovered.purchasedAt = 1_700_000_000_000L;
        assertTrue(restore(mine, novel, java.util.Collections.singletonList(recovered)));
        List<Purchase> before = subs.loadPurchasesOfNovel(novel);
        assertTrue(restore(mine, novel, java.util.Collections.singletonList(recovered)));
        List<Purchase> after = subs.loadPurchasesOfNovel(novel);
        assertEquals(2, after.size());
        for (Purchase old : before) {
            Purchase same = SubscriptionDao.purchaseOf(after, old.accountId);
            assertNotNull(same);
            assertTrue(LedgerWritePolicy.samePurchase(old, same));
        }
    }

    /** DAO 边界也拒绝同批重复章节，不能依赖上层 planner 永远传干净参数。 */
    @Test
    public void remoteRecoveryRejectsDuplicateIncomingChapters() {
        long account = newAccount("mine@x.com", 0, 100, true, 1);
        long novel = newNovel("目标书", true);
        long chapter = newChapter(novel, 1, 20);
        Purchase first = Purchase.of(account, chapter, 0, 20,
                Purchase.SRC_REMOTE_DETAIL);
        Purchase duplicate = Purchase.of(account, chapter, 0, 20,
                Purchase.SRC_REMOTE_DETAIL);

        assertTrue(!restore(account, novel,
                java.util.Arrays.asList(first, duplicate)));
        assertTrue(subs.loadPurchasesOfNovel(novel).isEmpty());
    }

    /** 带主键的恢复对象可能 REPLACE 完全无关的一行，事务入口只收 id=0 的新记录。 */
    @Test
    public void remoteRecoveryRejectsAnIncomingPrimaryKey() {
        long account = newAccount("mine@x.com", 0, 100, true, 1);
        long novel = newNovel("目标书", true);
        long chapter = newChapter(novel, 1, 20);
        Purchase recovered = Purchase.of(account, chapter, 0, 20,
                Purchase.SRC_REMOTE_DETAIL);
        recovered.id = 123;

        assertTrue(!restore(account, novel,
                java.util.Collections.singletonList(recovered)));
        assertTrue(subs.loadPurchasesOfNovel(novel).isEmpty());
    }

    @Test
    public void remoteRecoveryPromotesOwnedBesideAnotherPaidAccountWithoutChangingItsId() {
        long account = newAccount("mine@x.com", 0, 100, true, 1);
        long other = newAccount("other@x.com", 0, 100, true, 2);
        long novel = newNovel("目标书", true);
        long chapter = newChapter(novel, 1, 20);
        long originalId = buy(account, chapter, 0, 0, Purchase.SRC_OWNED);
        long otherId = buy(other, chapter, 0, 20, Purchase.SRC_AUTO);
        Purchase recovered = Purchase.of(account, chapter, 0, 12,
                Purchase.SRC_REMOTE_DETAIL);
        recovered.purchasedAt = 1_700_000_000_000L;

        assertTrue(restore(account, novel,
                java.util.Collections.singletonList(recovered)));
        List<Purchase> rows = subs.loadPurchasesOfNovel(novel);
        Purchase stored = SubscriptionDao.purchaseOf(rows, account);
        assertNotNull(stored);
        assertEquals(originalId, stored.id);
        assertEquals(12, stored.costVouchers);
        assertEquals(Purchase.SRC_REMOTE_DETAIL, stored.source);
        assertEquals(1_700_000_000_000L, stored.purchasedAt);
        assertEquals(otherId, SubscriptionDao.purchaseOf(rows, other).id);
        assertEquals(2, rows.size());
    }

    @Test
    public void anotherAccountsZeroCostOwnedDoesNotBlockRecovery() {
        long mine = newAccount("mine@x.com", 0, 100, true, 1);
        long other = newAccount("other@x.com", 0, 100, true, 2);
        long novel = newNovel("目标书", true);
        long chapter = newChapter(novel, 1, 20);
        buy(other, chapter, 0, 0, Purchase.SRC_OWNED);
        Purchase recovered = Purchase.of(mine, chapter, 0, 12,
                Purchase.SRC_REMOTE_DETAIL);
        recovered.purchasedAt = 1_700_000_000_000L;

        assertTrue(restore(mine, novel,
                java.util.Collections.singletonList(recovered)));
        assertEquals(2, subs.loadPurchasesOfNovel(novel).size());
        assertEquals(1, subs.countPaidPurchases(mine, novel));
    }

    @Test
    public void idempotentRemoteRecoveryPreservesPrimaryKey() {
        long account = newAccount("mine@x.com", 0, 100, true, 1);
        long novel = newNovel("目标书", true);
        long chapter = newChapter(novel, 1, 20);
        Purchase recovered = Purchase.of(account, chapter, 0, 12,
                Purchase.SRC_REMOTE_DETAIL);
        recovered.purchasedAt = 1_700_000_000_000L;
        assertTrue(restore(account, novel,
                java.util.Collections.singletonList(recovered)));
        long originalId = subs.loadPurchasesOfNovel(novel).get(0).id;

        assertTrue(restore(account, novel,
                java.util.Collections.singletonList(recovered)));
        assertEquals(originalId, subs.loadPurchasesOfNovel(novel).get(0).id);
    }

    @Test
    public void anExistingAutoFactIsNotReplacedByRecovery() {
        long account = newAccount("mine@x.com", 0, 100, true, 1);
        long novel = newNovel("目标书", true);
        long chapter = newChapter(novel, 1, 20);
        long originalId = buy(account, chapter, 0, 12, Purchase.SRC_AUTO);
        Purchase recovered = Purchase.of(account, chapter, 0, 12,
                Purchase.SRC_REMOTE_DETAIL);

        assertTrue(!restore(account, novel,
                java.util.Collections.singletonList(recovered)));
        Purchase stored = subs.loadPurchasesOfNovel(novel).get(0);
        assertEquals(originalId, stored.id);
        assertEquals(Purchase.SRC_AUTO, stored.source);
    }

    @Test
    public void changedCatalogSnapshotRejectsRecoveryWithoutWriting() {
        long account = newAccount("mine@x.com", 0, 100, true, 1);
        long novel = newNovel("目标书", true);
        long chapter = newChapter(novel, 1, 20);
        List<Chapter> plannedChapters = subs.loadChapters(novel);
        List<PurchaseRow> plannedPaid = subs.loadPaidRowsOfNovel(novel);
        Purchase recovered = Purchase.of(account, chapter, 0, 12,
                Purchase.SRC_REMOTE_DETAIL);
        recovered.purchasedAt = 1_700_000_000_000L;
        Chapter changed = subs.chapterById(chapter);
        changed.title = "1 改名了";
        subs.updateChapter(changed);

        assertTrue(!subs.restoreRemotePurchases(account, novel,
                java.util.Collections.singletonList(recovered), plannedChapters, plannedPaid));
        assertTrue(subs.loadPurchasesOfNovel(novel).isEmpty());
    }

    @Test
    public void newPaidFactAfterPlanningRejectsRecoveryWithoutOverwritingIt() {
        long account = newAccount("mine@x.com", 0, 100, true, 1);
        long other = newAccount("other@x.com", 0, 100, true, 2);
        long novel = newNovel("目标书", true);
        long chapter = newChapter(novel, 1, 20);
        List<Chapter> plannedChapters = subs.loadChapters(novel);
        List<PurchaseRow> plannedPaid = subs.loadPaidRowsOfNovel(novel);
        Purchase recovered = Purchase.of(account, chapter, 0, 12,
                Purchase.SRC_REMOTE_DETAIL);
        recovered.purchasedAt = 1_700_000_000_000L;
        long autoId = buy(other, chapter, 0, 12, Purchase.SRC_AUTO);

        assertTrue(!subs.restoreRemotePurchases(account, novel,
                java.util.Collections.singletonList(recovered), plannedChapters, plannedPaid));
        List<Purchase> stored = subs.loadPurchasesOfNovel(novel);
        assertEquals(1, stored.size());
        assertEquals(autoId, stored.get(0).id);
        assertEquals(Purchase.SRC_AUTO, stored.get(0).source);
    }

    /** 同一批经核实的历史账可以幂等重放，并保留真实购买日期。 */
    @Test
    public void remoteRecoveryIsIdempotentAndCountsAsPaid() {
        long account = newAccount("mine@x.com", 0, 100, true, 1);
        long novel = newNovel("目标书", true);
        long chapter = newChapter(novel, 1, 20);
        Purchase recovered = Purchase.of(account, chapter, 0, 20,
                Purchase.SRC_REMOTE_DETAIL);
        recovered.purchasedAt = 1_700_000_000_000L;

        assertTrue(restore(account, novel,
                java.util.Collections.singletonList(recovered)));
        assertTrue(restore(account, novel,
                java.util.Collections.singletonList(recovered)));
        assertEquals(1, subs.countPaidPurchases(account, novel));
        assertEquals(1, subs.loadPurchasesOfNovel(novel).size());
        assertEquals(1_700_000_000_000L,
                subs.loadPurchasesOfNovel(novel).get(0).purchasedAt);
        assertNull("恢复后绝不能再买同一章", subs.findNextUnownedChapter(novel));
    }
}
