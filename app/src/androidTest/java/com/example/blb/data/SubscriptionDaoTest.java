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
    public void countRealPurchaseIgnoresDryRun() {
        long acc = newAccount("a@x.com", 100, true, 1);
        long novel = newNovel("目标书", true);
        long ch = newChapter(novel, 1, 20);

        buy(acc, ch, 0, Purchase.SRC_DRY_RUN);
        assertEquals(0, subs.countRealPurchase(acc, ch));

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

    @Test
    public void dryRunRecordDoesNotMakeAChapterLookOwned() {
        long acc = newAccount("a@x.com", 100, true, 1);
        long novel = newNovel("目标书", true);
        long ch1 = newChapter(novel, 1, 20);

        buy(acc, ch1, 0, Purchase.SRC_DRY_RUN);

        assertEquals("干跑只留痕，这一章还是没人真买", ch1, subs.findNextUnownedChapter(novel).id);
        assertEquals(acc, subs.suggestBuyer(ch1).id);
    }

    @Test
    public void suggestBuyerPicksTheEnabledAccountWithMostCoupons() {
        long poor = newAccount("poor@x.com", 10, true, 1);
        long rich = newAccount("rich@x.com", 500, true, 2);
        long richButOff = newAccount("off@x.com", 900, false, 3);
        long novel = newNovel("目标书", true);
        long ch = newChapter(novel, 1, 20);

        assertEquals(rich, subs.suggestBuyer(ch).id);

        buy(rich, ch, 20, Purchase.SRC_AUTO);
        assertEquals("已经买过的号不该再被建议", poor, subs.suggestBuyer(ch).id);

        buy(poor, ch, 20, Purchase.SRC_AUTO);
        assertNull("剩下的都停用了，就该老实说没有人选", subs.suggestBuyer(ch));
        assertEquals(0, subs.countRealPurchase(richButOff, ch));
    }

    @Test
    public void suggestBuyerBreaksTiesBySortOrderThenId() {
        long later = newAccount("later@x.com", 100, true, 9);
        long earlier = newAccount("earlier@x.com", 100, true, 2);
        long novel = newNovel("目标书", true);
        long ch = newChapter(novel, 1, 20);

        assertEquals(earlier, subs.suggestBuyer(ch).id);

        buy(earlier, ch, 20, Purchase.SRC_AUTO);
        assertEquals(later, subs.suggestBuyer(ch).id);
    }

    @Test
    public void accountStatsAndRowsOnlyCountRealPurchases() {
        long acc = newAccount("a@x.com", 100, true, 1);
        long novel = newNovel("目标书", true);
        long ch1 = newChapter(novel, 1, 20);
        long ch2 = newChapter(novel, 2, 30);
        buy(acc, ch1, 20, Purchase.SRC_AUTO);
        buy(acc, ch2, 0, Purchase.SRC_DRY_RUN);

        List<PurchaseRow> rows = subs.loadAllRows();
        assertEquals("导出要连干跑记录一起带走，界面上会区分显示", 2, rows.size());
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
