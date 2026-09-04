package com.example.blb.auto;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 「这一章有没有归属」必须<b>跨号</b>问（{@link CatalogSync#verdict}）。
 *
 * <p>2026-09-03 那趟的运行日志里，每个号都刷出一条「N 章在这台手机上已下载、但账本里没有
 * 归属（第48章、第49章、第50章…）」，一共 8 条 —— <b>全是误报</b>。原因是那时候
 * 「有没有归属」和「是不是我买的」共用同一个变量，只查了当前账号：而「已下载」是本机的
 * 下载状态、8 个号共用（皓平买完下载到这台手机，换任何一个号登录，那一行照样写着「已下载」）。
 * 同一趟的逐章对账（{@link SubscribedDetail}）对每个号都说「逐章都对上了」，自相矛盾。
 *
 * <p>误报本身不花钱，但它把真正需要人核对的那几章埋进了 8 条噪音里 —— 使用者手指动不了，
 * 弹窗和小结是他唯一读得到的东西，那里每多一句假话，真话就少一句。
 */
public class CatalogOwnershipTest {

    /** 免费章（没有锁）：谁登录都看得到，当前号还没记就补一条。 */
    @Test
    public void freeChapterIsBackfilledForWhoeverIsLoggedIn() {
        ChapterRowState free = ChapterRowState.of(false, false, true);
        assertEquals(CatalogSync.Verdict.BACKFILL_FREE,
                CatalogSync.verdict(free, false, false));
        assertEquals("已经记过就别再写一条",
                CatalogSync.Verdict.NOTHING, CatalogSync.verdict(free, true, true));
    }

    /**
     * <b>这就是被修掉的那一条</b>：付费章、本机已下载、当前号账本里没有 —— 但<b>别的号买过</b>。
     * 那它是有主的，一个字都不该说。
     */
    @Test
    public void chapterBoughtByAnotherAccountIsNotReportedAsUnowned() {
        ChapterRowState downloaded = ChapterRowState.of(true, true, false);
        assertEquals(CatalogSync.Verdict.NOTHING,
                CatalogSync.verdict(downloaded, false, true));
    }

    /** 8 个号都查不到归属，才真的说不清是谁买的 —— 这种才值得报出来让人核对。 */
    @Test
    public void downloadedButNobodyOwnsItIsWorthReporting() {
        ChapterRowState downloaded = ChapterRowState.of(true, true, false);
        assertEquals(CatalogSync.Verdict.FOREIGN,
                CatalogSync.verdict(downloaded, false, false));
    }

    /** 就是我买的，当然什么都不用说。 */
    @Test
    public void myOwnPurchaseIsSilent() {
        ChapterRowState downloaded = ChapterRowState.of(true, true, false);
        assertEquals(CatalogSync.Verdict.NOTHING,
                CatalogSync.verdict(downloaded, true, true));
    }

    /**
     * 账本说这个号买过、界面上却还能勾选要花券 —— 账本和界面对不上，必须让人知道
     * （不自动改账本：删了会被重买一次，留着又会一直被跳过）。
     */
    @Test
    public void ledgerSaysMineButThePageStillWantsMoney() {
        ChapterRowState buyable = ChapterRowState.of(true, false, true);
        assertEquals(CatalogSync.Verdict.CONTRADICTION,
                CatalogSync.verdict(buyable, true, true));
    }

    /** 付费、还能买、谁都没买过 —— 这是正常的待买章，不是问题。 */
    @Test
    public void plainBuyableChapterIsNotAProblem() {
        ChapterRowState buyable = ChapterRowState.of(true, false, true);
        assertEquals(CatalogSync.Verdict.NOTHING,
                CatalogSync.verdict(buyable, false, false));
    }
}
