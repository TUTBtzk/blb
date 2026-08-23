package com.example.blb.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 自动订阅队列依赖的三条查询。它们决定「这一轮买哪几章、让哪个号买、还剩多少额度」，
 * 算错了就会重复买或者超花，所以边界单独钉住。
 */
public class SubscribeQueueDaoTest extends DbTestBase {

    private long purchaseAt(long accountId, long chapterId, int cost, String source, long at) {
        Purchase p = Purchase.of(accountId, chapterId, cost, source);
        p.purchasedAt = at;
        return subs.upsertPurchase(p);
    }

    // ---------- findUnownedChapters ----------

    @Test
    public void unownedChaptersComeBackInOrderAndRespectTheLimit() {
        long novel = newNovel("测试书", true);
        for (int no = 1; no <= 5; no++) newChapter(novel, no, 10);

        List<Chapter> two = subs.findUnownedChapters(novel, 2);
        assertEquals(2, two.size());
        assertEquals(1, two.get(0).chapterNo);
        assertEquals(2, two.get(1).chapterNo);

        assertEquals(5, subs.findUnownedChapters(novel, 50).size());
    }

    @Test
    public void aRealPurchaseByAnyAccountRemovesTheChapter() {
        long novel = newNovel("测试书", true);
        long c1 = newChapter(novel, 1, 10);
        newChapter(novel, 2, 10);
        long a = newAccount("a@x.com", 100, true, 0);
        buy(a, c1, 10, Purchase.SRC_AUTO);

        List<Chapter> pending = subs.findUnownedChapters(novel, 10);
        assertEquals(1, pending.size());
        assertEquals(2, pending.get(0).chapterNo);
    }

    /**
     * 干跑记录不算已有 —— 否则干跑一遍就把整轮章节「用掉」了，真买时反而跳过。
     * 这也是队列必须一次取一批、而不是循环取「下一章」的原因：干跑时那一章会一直返回。
     */
    @Test
    public void dryRunRecordsDoNotHideChapters() {
        long novel = newNovel("测试书", true);
        long c1 = newChapter(novel, 1, 10);
        long a = newAccount("a@x.com", 100, true, 0);
        purchaseAt(a, c1, 0, Purchase.SRC_DRY_RUN, 1_000L);

        List<Chapter> pending = subs.findUnownedChapters(novel, 10);
        assertEquals(1, pending.size());
        assertEquals(1, pending.get(0).chapterNo);
    }

    @Test
    public void otherNovelsChaptersAreNotMixedIn() {
        long mine = newNovel("目标书", true);
        long other = newNovel("别的书", false);
        newChapter(mine, 1, 10);
        newChapter(other, 1, 10);
        newChapter(other, 2, 10);

        List<Chapter> pending = subs.findUnownedChapters(mine, 10);
        assertEquals(1, pending.size());
        assertEquals(mine, pending.get(0).novelId);
    }

    @Test
    public void noChaptersMeansEmptyNotNull() {
        long novel = newNovel("空书", true);
        assertTrue(subs.findUnownedChapters(novel, 10).isEmpty());
    }

    // ---------- suggestBuyers ----------

    @Test
    public void buyersAreOrderedByCouponsThenSortOrderThenId() {
        long novel = newNovel("测试书", true);
        long c1 = newChapter(novel, 1, 10);
        long poor = newAccount("poor@x.com", 5, true, 0);
        long rich = newAccount("rich@x.com", 900, true, 9);
        long tieLate = newAccount("tie2@x.com", 5, true, 1);

        List<Account> buyers = subs.suggestBuyers(c1);
        assertEquals(3, buyers.size());
        assertEquals(rich, buyers.get(0).id);
        // 火券并列时按 sort_order，再按 id —— 顺序必须稳定，队列才不会每轮换个号试。
        assertEquals(poor, buyers.get(1).id);
        assertEquals(tieLate, buyers.get(2).id);
    }

    @Test
    public void buyersExcludeRealBuyersAndDisabledAccountsButKeepDryRunOnes() {
        long novel = newNovel("测试书", true);
        long c1 = newChapter(novel, 1, 10);
        long bought = newAccount("bought@x.com", 900, true, 0);
        long off = newAccount("off@x.com", 800, false, 1);
        long dry = newAccount("dry@x.com", 700, true, 2);
        long free = newAccount("free@x.com", 600, true, 3);
        buy(bought, c1, 10, Purchase.SRC_AUTO);
        purchaseAt(dry, c1, 0, Purchase.SRC_DRY_RUN, 1_000L);

        List<Account> buyers = subs.suggestBuyers(c1);
        assertEquals(2, buyers.size());
        assertEquals(dry, buyers.get(0).id);
        assertEquals(free, buyers.get(1).id);
        for (Account a : buyers) {
            assertTrue(a.id != bought && a.id != off);
        }
    }

    /** 单数和复数两个版本必须给出同一个第一人选，否则建议卡和队列会说两套话。 */
    @Test
    public void suggestBuyerAgreesWithTheFirstOfSuggestBuyers() {
        long novel = newNovel("测试书", true);
        long c1 = newChapter(novel, 1, 10);
        newAccount("a@x.com", 10, true, 0);
        long rich = newAccount("b@x.com", 500, true, 1);
        newAccount("c@x.com", 300, true, 2);

        assertEquals(rich, subs.suggestBuyer(c1).id);
        assertEquals(rich, subs.suggestBuyers(c1).get(0).id);
    }

    @Test
    public void noCandidateLeftMeansEmptyList() {
        long novel = newNovel("测试书", true);
        long c1 = newChapter(novel, 1, 10);
        long only = newAccount("only@x.com", 100, true, 0);
        buy(only, c1, 10, Purchase.SRC_AUTO);

        assertTrue(subs.suggestBuyers(c1).isEmpty());
    }

    // ---------- spentSince ----------

    @Test
    public void spentSinceSumsOnlyRealPurchasesAfterTheCutoff() {
        long novel = newNovel("测试书", true);
        long c1 = newChapter(novel, 1, 10);
        long c2 = newChapter(novel, 2, 20);
        long c3 = newChapter(novel, 3, 30);
        long c4 = newChapter(novel, 4, 40);
        long a = newAccount("a@x.com", 100, true, 0);
        long cutoff = 10_000L;

        purchaseAt(a, c1, 7, Purchase.SRC_AUTO, cutoff - 1);       // 昨天，不算
        purchaseAt(a, c2, 11, Purchase.SRC_AUTO, cutoff);          // 正好在边界上，算
        purchaseAt(a, c3, 13, Purchase.SRC_MANUAL, cutoff + 1);    // 手动补录也是真花的
        purchaseAt(a, c4, 99, Purchase.SRC_DRY_RUN, cutoff + 2);   // 干跑不算

        assertEquals(24, subs.spentSince(a, cutoff));
    }

    @Test
    public void spentSinceIsPerAccountAndZeroWhenNothingMatches() {
        long novel = newNovel("测试书", true);
        long c1 = newChapter(novel, 1, 10);
        long mine = newAccount("mine@x.com", 100, true, 0);
        long other = newAccount("other@x.com", 100, true, 1);
        purchaseAt(other, c1, 50, Purchase.SRC_AUTO, 20_000L);

        assertEquals(0, subs.spentSince(mine, 0L));
        assertEquals(50, subs.spentSince(other, 0L));
        assertEquals(0, subs.spentSince(other, 30_000L));
    }
}
