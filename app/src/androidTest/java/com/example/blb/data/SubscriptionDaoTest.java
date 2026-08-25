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
    public void samePurchaseTwiceOverwritesInsteadOfDoubleCounting() {
        long acc = newAccount("a@x.com", 100, true, 1);
        long novel = newNovel("目标书", true);
        long ch = newChapter(novel, 1, 20);

        buy(acc, ch, 20, Purchase.SRC_AUTO);
        buy(acc, ch, 25, Purchase.SRC_MANUAL);

        List<Purchase> rows = subs.loadPurchasesOfNovel(novel);
        assertEquals(1, rows.size());
        assertEquals(25, rows.get(0).costCoupons);
        assertEquals(Purchase.SRC_MANUAL, rows.get(0).source);
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
    public void deletingNovelCascadesToChaptersAndPurchases() {
        long acc = newAccount("a@x.com", 100, true, 1);
        long novel = newNovel("目标书", true);
        long ch = newChapter(novel, 1, 20);
        buy(acc, ch, 20, Purchase.SRC_AUTO);

        Novel n = subs.novelById(novel);
        subs.deleteNovel(n);

        assertTrue(subs.loadChapters(novel).isEmpty());
        assertTrue(subs.loadPurchasesOfNovel(novel).isEmpty());
        assertEquals(0, subs.countRealPurchase(acc, ch));
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

        buy(poor, ch, 20, Purchase.SRC_AUTO);
        assertNull("剩下的都停用了，就该老实说没有人选", subs.suggestBuyer(ch));
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
}
