package com.example.blb.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com.example.blb.auto.SubscribeRun;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private long purchaseAt(long accountId, long chapterId, int cost, int costVouchers,
                            String source, long at) {
        Purchase p = Purchase.of(accountId, chapterId, cost, costVouchers, source);
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
    public void buyersAreOrderedByVouchersThenSortOrderThenId() {
        long novel = newNovel("测试书", true);
        long c1 = newChapter(novel, 1, 10);
        long poor = newAccount("poor@x.com", 0, 5, true, 0);
        long rich = newAccount("rich@x.com", 0, 900, true, 9);
        long tieLate = newAccount("tie2@x.com", 0, 5, true, 1);

        List<Account> buyers = subs.suggestBuyers(c1);
        assertEquals(3, buyers.size());
        assertEquals(rich, buyers.get(0).id);
        // 代券并列时按 sort_order，再按 id —— 顺序必须稳定，队列才不会每轮换个号试。
        assertEquals(poor, buyers.get(1).id);
        assertEquals(tieLate, buyers.get(2).id);
    }

    /** 已经真买过这一章的号、以及停用的号，都不该再被推荐。 */
    @Test
    public void buyersExcludeRealBuyersAndDisabledAccounts() {
        long novel = newNovel("测试书", true);
        long c1 = newChapter(novel, 1, 10);
        long bought = newAccount("bought@x.com", 0, 900, true, 0);
        long off = newAccount("off@x.com", 0, 800, false, 1);
        long rich = newAccount("rich@x.com", 0, 700, true, 2);
        long free = newAccount("free@x.com", 0, 600, true, 3);
        buy(bought, c1, 10, Purchase.SRC_AUTO);

        List<Account> buyers = subs.suggestBuyers(c1);
        assertEquals(2, buyers.size());
        assertEquals(rich, buyers.get(0).id);
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
        newAccount("a@x.com", 0, 10, true, 0);
        long rich = newAccount("b@x.com", 0, 500, true, 1);
        newAccount("c@x.com", 0, 300, true, 2);

        assertEquals(rich, subs.suggestBuyer(c1).id);
        assertEquals(rich, subs.suggestBuyers(c1).get(0).id);
    }

    @Test
    public void noCandidateLeftMeansEmptyList() {
        long novel = newNovel("测试书", true);
        long c1 = newChapter(novel, 1, 10);
        long only = newAccount("only@x.com", 0, 100, true, 0);
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
        purchaseAt(a, c4, 0, Purchase.SRC_OWNED, cutoff + 2);      // 免费章，一分没花

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

    // ---------- findUnownedChaptersFrom：8 个号合起来拼一本 ----------

    /**
     * 「下一章」是<b>所有账号合起来</b>还没真买过的最小章，跟现在登着哪个号无关。
     * 这是「不多订、不漏订、8 个号能拼出完整一本」的全部实现 —— 算错就会重复买同一章。
     */
    @Test
    public void theNextChapterIsGlobalNotPerAccount() {
        long novel = newNovel("测试书", true);
        long c1 = newChapter(novel, 1, 10);
        long c2 = newChapter(novel, 2, 10);
        long c3 = newChapter(novel, 3, 10);
        long c4 = newChapter(novel, 4, 10);
        long a = newAccount("a@x.com", 0, 100, true, 0);
        long b = newAccount("b@x.com", 0, 100, true, 1);
        long c = newAccount("c@x.com", 0, 100, true, 2);

        // 三个号各买了一章，中间第 3 章谁都没买。
        buy(a, c1, 10, 10, Purchase.SRC_AUTO);
        buy(b, c2, 10, 10, Purchase.SRC_AUTO);
        buy(c, c4, 10, 10, Purchase.SRC_AUTO);

        assertEquals("缺口章才是下一章，不是 a 自己没买过的第 2 章",
                c3, subs.findNextUnownedChapterFrom(novel, 1).id);
        List<Chapter> pending = subs.findUnownedChaptersFrom(novel, 1, 10);
        assertEquals(1, pending.size());
        assertEquals(c3, pending.get(0).id);

        buy(a, c3, 10, 10, Purchase.SRC_AUTO);
        assertNull("四章都有号拥有了，就该说没得买", subs.findNextUnownedChapterFrom(novel, 1));
        assertTrue(subs.findUnownedChaptersFrom(novel, 1, 10).isEmpty());
    }

    /** 用 title_lock 回填的「这个号本来就有」（OWNED）也算真拥有，不该再花券买一遍。 */
    @Test
    public void chaptersBackfilledAsOwnedAreNotBoughtAgain() {
        long novel = newNovel("测试书", true);
        long c1 = newChapter(novel, 1, 10);
        long c2 = newChapter(novel, 2, 10);
        long a = newAccount("a@x.com", 0, 100, true, 0);
        buy(a, c1, 0, 0, Purchase.SRC_OWNED);

        assertEquals(c2, subs.findNextUnownedChapterFrom(novel, 1).id);
    }

    /** 指定从第 N 章开始订阅时，前面的旧章就算没人买也不该动。 */
    @Test
    public void chaptersBeforeTheStartChapterAreLeftAlone() {
        long novel = newNovel("测试书", true);
        newChapter(novel, 1, 10);
        newChapter(novel, 2, 10);
        long c3 = newChapter(novel, 3, 10);
        newChapter(novel, 4, 10);

        subs.setStartChapter(novel, 3);
        int from = subs.novelById(novel).startFrom();
        assertEquals(3, from);

        List<Chapter> pending = subs.findUnownedChaptersFrom(novel, from, 10);
        assertEquals(2, pending.size());
        assertEquals(c3, pending.get(0).id);
        assertEquals(c3, subs.findNextUnownedChapterFrom(novel, from).id);
    }

    // ---------- spentVouchersSince ----------

    /** 每日花费上限按代券算，所以只能加 cost_vouchers 那一列，火券那一列不能混进来。 */
    @Test
    public void spentVouchersSinceSumsOnlyTheVoucherColumn() {
        long novel = newNovel("测试书", true);
        long c1 = newChapter(novel, 1, 10);
        long c2 = newChapter(novel, 2, 20);
        long c3 = newChapter(novel, 3, 30);
        long a = newAccount("a@x.com", 0, 100, true, 0);
        long cutoff = 10_000L;

        purchaseAt(a, c1, 0, 9, Purchase.SRC_AUTO, cutoff - 1);      // 昨天，不算
        purchaseAt(a, c2, 0, 12, Purchase.SRC_AUTO, cutoff);         // 边界上，算
        purchaseAt(a, c3, 0, 0, Purchase.SRC_OWNED, cutoff + 1);     // 免费章，一分钱没花

        assertEquals(12, subs.spentVouchersSince(a, cutoff));
        assertEquals("这一趟没动火券", 0, subs.spentSince(a, cutoff));
    }

    /** 旧账（只记了火券的历史记录）在代券统计里是 0，不该把上限提前吃掉。 */
    @Test
    public void oldFireCoinOnlyRecordsCountAsZeroVouchers() {
        long novel = newNovel("测试书", true);
        long c1 = newChapter(novel, 1, 10);
        long a = newAccount("a@x.com", 0, 100, true, 0);
        buy(a, c1, 25, Purchase.SRC_MANUAL);   // 4 参版本：cost_vouchers 默认 0

        assertEquals(0, subs.spentVouchersSince(a, 0L));
        assertEquals(25, subs.spentSince(a, 0L));
    }

    // ---------- 目标小说（is_target 这一位丢了的时候） ----------

    /**
     * 只登记了一本书、这一位却是 0 —— 2026-08-24 真机上出现过两次（章节和 190 条账本都在，
     * 只有这一位回到了 0），整轮订阅因此一个动作都没有。唯一一本就该被认出来，并把这一位补回去。
     */
    @Test
    public void theOnlyNovelIsTheTargetEvenWithoutTheFlag() {
        long id = newNovel("测试书", false);
        assertNull("前提：这一位是 0", subs.targetNovel());

        Novel resolved = SubscribeRun.resolveTarget(subs, null);
        assertEquals(id, resolved.id);
        assertTrue("标志位要被补回去", resolved.isTarget);
        assertEquals("补回去之后正常查询也该取到", id, subs.targetNovel().id);
    }

    /** 登记了多本又没标目标，那就是真的说不清 —— 绝不替用户猜是哪本。 */
    @Test
    public void twoNovelsWithoutAFlagStayUnresolved() {
        newNovel("甲", false);
        newNovel("乙", false);
        assertNull(SubscribeRun.resolveTarget(subs, null));
        assertNull("一位都不许乱写", subs.targetNovel());
    }

    /** 标了目标就照标的来，哪怕登记了好几本。 */
    @Test
    public void theFlaggedNovelWins() {
        newNovel("甲", false);
        long target = newNovel("乙", true);
        newNovel("丙", false);
        assertEquals(target, SubscribeRun.resolveTarget(subs, null).id);
    }

    // ---------- 账本里每一条都是真的（干跑整套删除之后） ----------

    /**
     * 真买、手动补录、免费章回填都是真台账，删掉任何一条都会让那一章被重新买一遍 ——
     * 那是真花钱。干跑功能删掉之后账本里<b>不该再有</b>「只留痕、不算归属」的记录：
     * 每一条都让那一章从待买队列里消失。
     */
    @Test
    public void everyRecordCountsAsOwnership() {
        long novel = newNovel("测试书", true);
        long c1 = newChapter(novel, 1, 10);
        long c2 = newChapter(novel, 2, 10);
        long c3 = newChapter(novel, 3, 10);
        long c4 = newChapter(novel, 4, 10);
        long a = newAccount("a@x.com", 0, 100, true, 0);
        long b = newAccount("b@x.com", 0, 100, true, 1);
        buy(a, c1, 0, 20, Purchase.SRC_AUTO);
        buy(a, c2, 0, 0, Purchase.SRC_OWNED);
        buy(b, c3, 25, Purchase.SRC_MANUAL);

        assertEquals("三条记录一条都不许掉", 3, subs.loadPurchasesOfNovel(novel).size());
        assertEquals(1, subs.countRealPurchase(a, c1));
        assertEquals(1, subs.countRealPurchase(a, c2));
        assertEquals(1, subs.countRealPurchase(b, c3));
        assertEquals("前三章都有归属了，下一章只能是第 4 章",
                c4, subs.findNextUnownedChapterFrom(novel, 1).id);
    }

    // ---------- 章号搬家（作者往中间插了一章） ----------

    /**
     * 作者往中间插了一章 → 后面每一章的章号都要 +1。
     *
     * <p>这里钉的是<b>库这一层</b>：{@code (novel_id, chapter_no)} 是唯一索引，第 2 章搬到 3 的
     * 时候 3 还被老的第 3 章占着，一条一条改必然撞索引、搬不动 —— 而搬不动就会被
     * {@code CatalogSync} 的落库复核拦成「这轮一章都不买」，自动订阅从此卡死。
     * 判据本身在 {@code CatalogAlignTest}；这里只管「算出来的搬法真能落进库」。
     */
    @Test
    public void realignShiftsChapterNosWithoutHittingTheUniqueIndex() {
        long novel = newNovel("测试书", true);
        long c1 = newChapter(novel, 1, 10);
        long c2 = newChapter(novel, 2, 10);
        long c3 = newChapter(novel, 3, 10);
        long a = newAccount("a@x.com", 0, 100, true, 0);
        buy(a, c2, 0, 20, Purchase.SRC_AUTO);

        Map<Long, Integer> renumber = new LinkedHashMap<>();
        renumber.put(c2, 3);
        renumber.put(c3, 4);
        subs.realign(renumber, Collections.<Long>emptyList(), -1, novel);

        assertEquals("第1章没被插章影响", c1, subs.chapterByNo(novel, 1).id);
        assertEquals(c2, subs.chapterByNo(novel, 3).id);
        assertEquals(c3, subs.chapterByNo(novel, 4).id);
        assertNull("老的第 2 号腾出来了，等着扫描把插进来的那一章登记上", subs.chapterByNo(novel, 2));
        assertEquals("三章都还在，没有谁被负数卡住", 3, subs.loadChapters(novel).size());

        assertEquals("购买记录挂在 chapter.id 上，搬号一条都不许动",
                1, subs.countRealPurchase(a, c2));
        assertEquals(1, subs.loadPurchasesOfNovel(novel).size());
        assertEquals("买过的那一章搬到第 3 章之后，下一章该是第 1 章（谁都没买）",
                c1, subs.findNextUnownedChapterFrom(novel, 1).id);
    }

    /** 空登记删掉、起始章跟着搬，一次事务里做完。 */
    @Test
    public void realignDropsGhostChaptersAndMovesTheStartChapter() {
        long novel = newNovel("测试书", true);
        long c1 = newChapter(novel, 1, 10);
        long ghost = newChapter(novel, 2, 10);
        long c3 = newChapter(novel, 3, 10);
        subs.setStartChapter(novel, 3);

        Map<Long, Integer> renumber = new LinkedHashMap<>();
        renumber.put(c3, 2);
        subs.realign(renumber, Collections.singletonList(ghost), 2, novel);

        assertNull("没人买过、界面上也没有了的空登记该删掉", subs.chapterById(ghost));
        assertEquals(c1, subs.chapterByNo(novel, 1).id);
        assertEquals(c3, subs.chapterByNo(novel, 2).id);
        assertEquals("起始章说的是那一章本身，位置变了就得跟着搬",
                2, subs.novelById(novel).startFrom());
    }

    /** 搬到一半失败必须整体回滚 —— 半搬的账本比不搬更危险（会拿别人的章号去买）。 */
    @Test
    public void aFailedRealignLeavesTheLedgerUntouched() {
        long novel = newNovel("测试书", true);
        long c1 = newChapter(novel, 1, 10);
        long c2 = newChapter(novel, 2, 10);

        Map<Long, Integer> clashing = new LinkedHashMap<>();
        clashing.put(c1, 3);
        clashing.put(c2, 3);   // 两章搬到同一号：判据本该先拦住，库这一层也不许写进去
        try {
            subs.realign(clashing, Collections.<Long>emptyList(), -1, novel);
            fail("撞唯一索引就该抛出来，绝不能悄悄写一半");
        } catch (RuntimeException expected) {
            // 落到这里就对了
        }

        assertEquals(c1, subs.chapterByNo(novel, 1).id);
        assertEquals(c2, subs.chapterByNo(novel, 2).id);
        assertEquals(2, subs.loadChapters(novel).size());
    }
}
