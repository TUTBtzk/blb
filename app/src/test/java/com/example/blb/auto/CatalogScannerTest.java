package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.blb.data.Chapter;
import com.example.blb.util.Texts;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 扫目录这一步的两道护栏。
 *
 * <p>这两道护栏是「8 个号合起来拼出完整一本、不多订不漏订」唯一的保险：章号用的是界面上的
 * 行序，一旦翻页漏了一屏、或者作者插了新章使位置整体后移，「下一章该买哪一章」就会算错，
 * 而买错章的钱是要不回来的。所以宁可这一轮什么都不买。
 */
public class CatalogScannerTest {

    /** 一行「付费、这个号还没有、还能勾」的普通待买章 —— 护栏那几条用例只关心行序。 */
    private static CatalogScanner.Row row(String title) {
        return new CatalogScanner.Row(title, Texts.rowChapterNo(title), true, false, true);
    }

    private static List<CatalogScanner.Row> rows(String... titles) {
        List<CatalogScanner.Row> out = new ArrayList<>();
        for (String t : titles) out.add(row(t));
        return out;
    }

    private static Chapter chapter(int no, String title) {
        Chapter c = new Chapter();
        c.chapterNo = no;
        c.title = title;
        return c;
    }

    // ---------- 行首标号：卷标题行不是章节 ----------

    /**
     * 卷标题行（「番外」「作品相关」）也用 title 这个 id、也带勾选框，勾下去等于勾整卷。
     * 唯一能把它和章节行分开的特征是行首没有阿拉伯标号。
     */
    @Test
    public void volumeHeadersHaveNoPrintedNumber() {
        assertEquals(67, Texts.rowChapterNo("67   周日工作"));
        assertEquals(1, Texts.rowChapterNo("1 开学第一天"));
        assertEquals(-1, Texts.rowChapterNo("番外"));
        assertEquals(-1, Texts.rowChapterNo("作品相关"));
        assertEquals(-1, Texts.rowChapterNo("人设图"));
        assertEquals(-1, Texts.rowChapterNo("尾声（下）"));
        assertEquals(-1, Texts.rowChapterNo("第七章 回家"));
    }

    // ---------- 护栏 1：标号链有缺口就是漏了行 ----------

    @Test
    public void continuousNumbersMeanNothingWasMissed() {
        assertNull(CatalogScanner.findGap(rows("1 甲", "2 乙", "3 丙")));
    }

    @Test
    public void aForwardJumpIsReportedAsAGap() {
        String gap = CatalogScanner.findGap(rows("12 甲", "13 乙", "20 丙"));
        assertNotNull(gap);
        assertTrue(gap, gap.contains("13"));
        assertTrue(gap, gap.contains("20"));
        assertTrue(gap, gap.contains("缺 6 行"));
    }

    /** 番外卷会从「1」重新排号：掉回更小的数是换卷，不是漏行。 */
    @Test
    public void restartingAtOneIsANewVolumeNotAGap() {
        assertNull(CatalogScanner.findGap(rows("610 甲", "611 乙", "612 丙", "1 番外一", "2 番外二")));
    }

    @Test
    public void aResultWithAGapIsNotTrustworthy() {
        CatalogScanner.Result r = new CatalogScanner.Result();
        r.chapters.addAll(rows("1 甲", "5 乙"));
        r.gapNote = CatalogScanner.findGap(r.chapters);
        assertFalse(r.trustworthy());

        CatalogScanner.Result truncated = new CatalogScanner.Result();
        truncated.chapters.addAll(rows("1 甲", "2 乙"));
        truncated.truncated = true;
        assertFalse("没扫到底的结果不能写账本", truncated.trustworthy());

        assertFalse("一行都没读到也不能写账本", new CatalogScanner.Result().trustworthy());

        CatalogScanner.Result good = new CatalogScanner.Result();
        good.chapters.addAll(rows("1 甲", "2 乙"));
        assertTrue(good.trustworthy());
    }

    /**
     * 「这个号还能买几章」数的是<b>付费且还有勾选圈</b>的行，「免费几章」数的是没有锁的行。
     *
     * <p>两条事实各出自一次事故：付费章买完之后锁还在（2026-08-24，40 代券）；
     * 「已下载」是本机状态、8 个号共用，不能当「这个号买过」（2026-08-25，第49章挂了两个号）。
     * 所以本机已下载的付费章既不算免费、也不算这个号能买 —— 它谁都不算。
     */
    @Test
    public void countsSeparateFreeChaptersFromTheOnesThisAccountCanBuy() {
        CatalogScanner.Result r = new CatalogScanner.Result();
        // 免费章：没有锁，下载完了
        r.chapters.add(new CatalogScanner.Row("1 甲", 1, false, true, false));
        // 付费章 + 本机已下载：买家可能是别的号，右边也没有勾选圈
        r.chapters.add(new CatalogScanner.Row("2 乙", 2, true, true, false));
        // 还能花券买的两章
        r.chapters.add(new CatalogScanner.Row("3 丙", 3, true, false, true));
        r.chapters.add(new CatalogScanner.Row("4 丁", 4, true, false, true));
        // 免费章、还没下载
        r.chapters.add(new CatalogScanner.Row("5 戊", 5, false, false, true));

        assertEquals("能买的只有丙、丁", 2, r.buyableCount());
        assertEquals("免费的只有甲、戊", 2, r.freeCount());
    }

    // ---------- 护栏 2：重排之后的复核，账面和界面必须逐行一致 ----------

    @Test
    public void anEmptyLedgerNeverDrifts() {
        assertNull(CatalogSync.findDrift(null, rows("1 甲")));
        assertNull(CatalogSync.findDrift(new ArrayList<Chapter>(), rows("1 甲")));
    }

    @Test
    public void matchingTitlesDoNotDrift() {
        assertNull(CatalogSync.findDrift(
                Arrays.asList(chapter(1, "1 甲"), chapter(2, "2 乙")),
                rows("1 甲", "2 乙", "3 丙")));
    }

    /**
     * 位置整体后移而章号没跟着搬 → 复核必须报出来。
     *
     * <p>这种情况正常会被 {@link CatalogAlign} 按名字重排掉（见 CatalogAlignTest）；
     * 这里钉的是「重排没落库」时的最后一道拦网 —— 那时候照着账本买会买错章。
     */
    @Test
    public void anUnshiftedLedgerIsCaught() {
        String drift = CatalogSync.findDrift(
                Arrays.asList(chapter(1, "1 甲"), chapter(2, "2 乙"), chapter(3, "3 丙")),
                rows("1 甲", "1.5 插进来的", "2 乙", "3 丙"));
        assertNotNull(drift);
        assertTrue(drift, drift.contains("第2章"));
        assertTrue(drift, drift.contains("插进来的"));
    }

    /** 台账比界面长（作者删章了）同样是位置错了。 */
    @Test
    public void aLedgerLongerThanTheCatalogIsCaught() {
        String drift = CatalogSync.findDrift(
                Arrays.asList(chapter(1, "1 甲"), chapter(9, "9 己")),
                rows("1 甲", "2 乙"));
        assertNotNull(drift);
        assertTrue(drift, drift.contains("第9章"));
        assertTrue(drift, drift.contains("只有 2 行"));
    }

    /**
     * 历史遗留的两种写法必须容忍，否则功能会被自己的护栏永久卡死：
     * 手工登记时只填了短标题（没有行首标号），或者干脆没填标题。
     *
     * <p>还要认「同一章、标号变了」：作者插了新章，「11 久违的笑」在界面上会变成「12 久违的笑」。
     * 号会变、名字不会，所以按名字算同一章 —— 位置由重排负责搬。
     */
    @Test
    public void oldHandWrittenLedgerRowsStillMatch() {
        assertTrue(CatalogSync.sameChapter("久违的笑", "11   久违的笑"));
        assertTrue(CatalogSync.sameChapter("11   久违的笑", "11   久违的笑"));
        assertTrue("插了新章之后标号会变，名字不会",
                CatalogSync.sameChapter("11   久违的笑", "12   久违的笑"));
        assertTrue("没登记标题的行谈不上对不上", CatalogSync.sameChapter(null, "11 久违的笑"));
        assertTrue(CatalogSync.sameChapter("  ", "11 久违的笑"));
        assertFalse(CatalogSync.sameChapter("久违的笑", "12   放学路上"));
    }
}
