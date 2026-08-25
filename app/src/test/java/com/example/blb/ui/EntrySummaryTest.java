package com.example.blb.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.example.blb.data.AccountStat;
import com.example.blb.data.Chapter;
import com.example.blb.data.CheckInLog;
import com.example.blb.data.CheckInRow;
import com.example.blb.data.Purchase;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 一级页面上那三句摘要（今日状态、各账号累计、这本书的账本）。
 *
 * <p>为什么它们值得断死：完整内容已经搬进 {@link DetailActivity} 一整屏，而这个 App 的
 * 使用者手指不能动 —— 他打不开二级页面，摘要那一句就是他能看到的全部。摘要少说一句，
 * 对他就是「这条信息没有了」。界面代码这个项目里测不了（没有 Robolectric），
 * 所以这三个函数刻意写成不碰 Android API 的纯函数，好让它们能被测。
 */
public class EntrySummaryTest {

    // ---------- 今日状态 ----------

    private static CheckInRow row(String name, String status) {
        CheckInRow r = new CheckInRow();
        r.label = name;
        r.status = status;
        return r;
    }

    @Test
    public void todaySummaryCountsSignedAgainstTotal() {
        List<CheckInRow> rows = Arrays.asList(
                row("主号", CheckInLog.OK),
                row("备用1", CheckInLog.ALREADY),
                row("备用2", null));
        assertTrue(CheckInRows.summary(rows).startsWith("已签到 2/3"));
    }

    @Test
    public void todaySummaryNamesTheOnesThatFailed() {
        // 「有几个没成」不够，得说出是哪个号：他不能自己点进去翻列表。
        String s = CheckInRows.summary(Arrays.asList(
                row("主号", CheckInLog.OK),
                row("备用1", CheckInLog.FAILED),
                row("备用2", CheckInLog.BLOCKED_CAPTCHA)));
        assertTrue(s, s.contains("备用1"));
        assertTrue(s, s.contains("备用2"));
    }

    @Test
    public void todaySummaryTellsHowManyNotRunYet() {
        String s = CheckInRows.summary(Arrays.asList(
                row("主号", CheckInLog.OK), row("备用1", null), row("备用2", null)));
        assertTrue(s, s.contains("还没跑 2 个"));
    }

    @Test
    public void skippedAccountsAreNeitherDoneNorBlamed() {
        // 「已跳过」是我们自己决定不跑的，不是它出了问题，不该点名。
        String s = CheckInRows.summary(Arrays.asList(
                row("主号", CheckInLog.OK), row("备用1", CheckInLog.SKIPPED)));
        assertTrue(s, s.startsWith("已签到 1/2"));
        assertTrue(s, !s.contains("备用1"));
    }

    @Test
    public void tooManyFailuresCollapseToACount() {
        // 8 个号全挂时不能把 8 个名字塞进一行，那一行会被截断，等于什么都没说。
        List<CheckInRow> rows = new ArrayList<>();
        for (int i = 1; i <= 8; i++) rows.add(row("号" + i, CheckInLog.FAILED));
        String s = CheckInRows.summary(rows);
        assertTrue(s, s.contains("等 8 个"));
    }

    @Test
    public void adsLeftIsSaidOutLoud() {
        CheckInRow r = row("主号", CheckInLog.OK);
        r.adsWatched = 3;
        r.adAvailable = true;
        String s = CheckInRows.summary(Arrays.asList(r));
        assertTrue(s, s.contains("广告已看 3 次"));
        assertTrue(s, s.contains("还有广告没看完"));
    }

    @Test
    public void noAccountsMeansEmptyStringSoTheCallerCanSayItsOwnThing() {
        assertEquals("", CheckInRows.summary(null));
        assertEquals("", CheckInRows.summary(new ArrayList<>()));
    }

    // ---------- 各账号累计 ----------

    private static AccountStat stat(String name, int chapters, int vouchersSpent, int left) {
        AccountStat s = new AccountStat();
        s.label = name;
        s.chapterCount = chapters;
        s.totalVouchers = vouchersSpent;
        s.vouchers = left;
        return s;
    }

    @Test
    public void statsSummaryAddsUpChaptersSpendAndBalance() {
        String s = AccountStatText.summary(Arrays.asList(
                stat("主号", 30, 600, 20), stat("备用1", 21, 420, 15)));
        assertTrue(s, s.contains("2 个号共 51 章"));
        assertTrue(s, s.contains("花掉 1020 代券"));
        assertTrue(s, s.contains("手上还剩 35 代券"));
    }

    @Test
    public void unreadBalanceIsNotCountedAsZero() {
        // vouchers = -1 是「这个号的余额一次都没读到过」。把它当 0 加进去，
        // 会让「还剩多少」看着比实际少，而这个数决定还买不买得动。
        String s = AccountStatText.summary(Arrays.asList(
                stat("主号", 10, 200, 15), stat("备用1", 0, 0, -1)));
        assertTrue(s, s.contains("手上还剩 15 代券"));
        assertTrue(s, s.contains("另有 1 个号没读到余额"));
    }

    @Test
    public void fireCouponsOnlyShowUpWhenTheyReallyExist() {
        // 现在只买「实付 0 火券」的章，火券只会是历史遗留：有就得说，没有别提。
        AccountStat clean = stat("主号", 5, 100, 10);
        assertTrue(!AccountStatText.summary(Arrays.asList(clean)).contains("火券"));

        AccountStat legacy = stat("备用1", 5, 100, 10);
        legacy.totalCost = 25;
        assertTrue(AccountStatText.summary(Arrays.asList(legacy)).contains("25 火券"));
    }

    @Test
    public void statsLineSaysEverythingAboutOneAccount() {
        String line = AccountStatText.line(stat("主号", 12, 240, 60));
        assertTrue(line, line.contains("12 章"));
        assertTrue(line, line.contains("花 240 代券"));
        assertTrue(line, line.contains("余 60 代券"));
    }

    @Test
    public void balanceNeverReadShowsAQuestionMarkNotZero() {
        assertTrue(AccountStatText.line(stat("主号", 0, 0, -1)).contains("余 ? 代券"));
    }

    @Test
    public void deviceOnlyChaptersAreNeverAddedIntoTheAccountsOwnCount() {
        // 免费章（source=OWNED）在 8 个号名下各有一条记录，一分券都没花。加进「这个号买了几章」
        // 就会出现 8 个号各自声称买过同一批章、总数比全书还多（真花过钱的只有 4 章）。
        AccountStat s = stat("主号", 4, 80, 18);
        s.deviceCount = 47;
        assertTrue(AccountStatText.line(s), AccountStatText.line(s).startsWith("4 章"));
        assertTrue(AccountStatText.line(s), AccountStatText.line(s).contains("另有 47 章免费"));

        String sum = AccountStatText.summary(Arrays.asList(s));
        assertTrue(sum, sum.contains("1 个号共 4 章"));
        assertTrue(sum, !sum.contains("47"));      // 8 个号加起来会是 376，那个数没有意义
        assertTrue(sum, sum.contains("不含免费章"));
    }

    @Test
    public void withoutDeviceOnlyRecordsTheCaveatIsNotMentioned() {
        String sum = AccountStatText.summary(Arrays.asList(stat("主号", 4, 80, 18)));
        assertTrue(sum, !sum.contains("免费"));
    }

    // ---------- 这本书的账本 ----------

    private static Chapter chapter(long id, int no) {
        Chapter c = new Chapter();
        c.id = id;
        c.chapterNo = no;
        return c;
    }

    @Test
    public void ledgerSummaryCountsRegisteredOwnedAndMissing() {
        List<Chapter> chapters = Arrays.asList(chapter(1, 1), chapter(2, 2), chapter(3, 3));
        List<Purchase> purchases = Arrays.asList(
                Purchase.of(10, 1, 0, 15, Purchase.SRC_AUTO),
                Purchase.of(11, 2, 0, 15, Purchase.SRC_MANUAL));
        String s = ChapterLedgerText.summary(chapters, purchases);
        assertTrue(s, s.contains("登记 3 章"));
        assertTrue(s, s.contains("已订阅 2 章"));
        assertTrue(s, s.contains("还要买 1 章"));
    }

    @Test
    public void oneChapterBoughtTwiceStillCountsAsOne() {
        // 一章两个号买过是要修的错（钱白花了），但摘要不能因此报出比登记章数还多的
        // 「已订」—— 那看着像账本坏了，会把注意力引到错的地方。
        List<Chapter> chapters = Arrays.asList(chapter(1, 1), chapter(2, 2));
        List<Purchase> purchases = Arrays.asList(
                Purchase.of(10, 1, 0, 15, Purchase.SRC_AUTO),
                Purchase.of(11, 1, 0, 15, Purchase.SRC_AUTO));
        String s = ChapterLedgerText.summary(chapters, purchases);
        assertTrue(s, s.contains("已订阅 1 章"));
        assertTrue(s, s.contains("还要买 1 章"));
    }

    @Test
    public void deviceOnlyOwnershipIsReportedSeparately() {
        // 免费章（source=OWNED）一分券都不用花，但它也不是「谁买的」：单独报一栏，
        // 而且绝不能算进「还要买」—— 算进去会让人以为 8 个号拼不出完整一本。
        List<Chapter> chapters = Arrays.asList(chapter(1, 1), chapter(2, 2));
        List<Purchase> purchases = Arrays.asList(
                Purchase.of(10, 1, 0, 15, Purchase.SRC_AUTO),
                Purchase.of(11, 2, 0, 0, Purchase.SRC_OWNED));
        String s = ChapterLedgerText.summary(chapters, purchases);
        assertTrue(s, s.contains("已订阅 1 章"));
        assertTrue(s, s.contains("免费 1 章"));
        assertTrue(s, s.contains("还要买 0 章"));
    }

    @Test
    public void purchasesPointingAtOtherBooksAreIgnored() {
        List<Chapter> chapters = Arrays.asList(chapter(1, 1));
        List<Purchase> purchases = Arrays.asList(
                Purchase.of(10, 999, 0, 15, Purchase.SRC_AUTO));
        String s = ChapterLedgerText.summary(chapters, purchases);
        assertTrue(s, s.contains("已订阅 0 章"));
    }

    @Test
    public void noChaptersRegisteredSaysSoInsteadOfShowingZeros() {
        assertEquals("还没登记章节", ChapterLedgerText.summary(null, null));
        assertEquals("还没登记章节", ChapterLedgerText.summary(new ArrayList<>(), null));
    }
}
