package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.blb.data.Chapter;
import com.example.blb.util.Texts;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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

    // ---------- 行首标号：它只能识别编号，不能替代普通目录角色证据 ----------

    /**
     * 卷标题行（「番外」「作品相关」）也用 title 这个 id、也带勾选框，勾下去等于勾整卷。
     * 支持裸数字和「第N章」前缀，但不能把卷号当章号；2026-09-14 的番外同样没有章号，
     * 因此这些 -1 只表示未读到编号，不能说明该行是卷标题。
     */
    @Test
    public void volumeHeadersHaveNoPrintedNumber() {
        assertEquals(67, Texts.rowChapterNo("67   周日工作"));
        assertEquals(1, Texts.rowChapterNo("1 开学第一天"));
        assertEquals(83, Texts.rowChapterNo("第83章 最后"));
        assertEquals(-1, Texts.rowChapterNo("第1卷 铃兰花"));
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

    @Test
    public void catalogTextAloneCannotProveAllUnnumberedRowsAreSectionHeaders() throws Exception {
        List<String> source;
        try (InputStream stream = getClass().getResourceAsStream("/catalog/real-chapter-picker.txt")) {
            assertNotNull(stream);
            source = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .lines().collect(Collectors.toList());
        }
        PagedRunner runner = new PagedRunner(pages(source, 14, 7));

        CatalogScanner.Result result = CatalogScanner.scan(runner);

        assertFalse("文本夹具不是番外节点证据，未知行不能被当成分节静默丢弃", result.trustworthy());
        assertFalse(result.directoryScanned);
        assertEquals("纯文本回放也必须保留所有行的位置，不能先把分节或番外去掉", source.size(), result.allRows.size());
        assertEquals(483, result.chapters.size());
        assertEquals(0, result.skipped.size());
        assertEquals(Arrays.asList("铃兰花", "上架感言", "一卷总结", "星彩", "红莲", "焰火", "凌唯",
                "完结感言，以及反思", "番外", "藏在地下室的恶鬼（上）"), result.unresolved);
        assertEquals("第81章 心脏", result.chapters.get(80).title);
        assertEquals("第81章 沙滩", result.chapters.get(81).title);
        assertEquals("第83章 最后", result.chapters.get(82).title);
        assertEquals("第6章 薯片", result.chapters.get(98).title);
        assertTrue(titles(result).contains("第68 投影"));
        assertTrue(titles(result).contains("第30张 红温"));
        assertTrue(titles(result).contains("第96章 伤"));
        assertTrue(result.scrolls > 60);
        assertEquals(0, runner.presses.size());
    }

    @Test
    public void authoredNumberingIsAllowedOnlyWithObservedAdjacency() throws Exception {
        PagedRunner runner = new PagedRunner(new String[]{
                "第80章 镜子", "第81章 心脏", "第81章 沙滩", "第83章 最后"},
                new String[]{"第81章 心脏", "第81章 沙滩", "第83章 最后", "第84章 迷梦"});

        CatalogScanner.Result result = CatalogScanner.scan(runner);

        assertTrue(result.gapNote, result.trustworthy());
        assertEquals(Arrays.asList("第80章 镜子", "第81章 心脏", "第81章 沙滩", "第83章 最后",
                "第84章 迷梦"), titles(result));
        assertTrue(result.skipped.isEmpty());
    }

    @Test
    public void headerCheckboxesNeverBecomePurchasableChapters() throws Exception {
        PagedRunner runner = new PagedRunner(new String[]{"第1卷 铃兰花", "第67章 小屋", "第68 投影",
                "第69章 诅咒", "第2卷 红莲", "第29章 甲", "第30张 红温", "第31章 乙"});

        CatalogScanner.Result result = CatalogScanner.scan(runner);

        assertFalse("卷标题同样需要本 App 的结构证据，勾选框不能代替它", result.trustworthy());
        assertEquals(6, result.chapters.size());
        assertEquals(6, result.buyableCount());
        assertTrue(result.skipped.isEmpty());
        assertEquals(Arrays.asList("第1卷 铃兰花", "第2卷 红莲"), result.unresolved);
        assertTrue(runner.presses.isEmpty());
    }

    @Test
    public void theSameRawTitleInTwoVolumesRemainsTwoChapters() throws Exception {
        List<String> source = Arrays.asList("卷一", "第1章 同名", "第2章 甲", "第3章 乙", "第4章 丙",
                "卷二", "第1章 同名", "第2章 丁", "第3章 戊", "第4章 己");
        CatalogScanner.Result result = CatalogScanner.scan(new PagedRunner(pages(source, 6, 3)));

        assertFalse("未经节点取证的分节行不能从位置账里静默删掉", result.trustworthy());
        assertEquals(Arrays.asList("卷一", "卷二"), result.unresolved);
        assertEquals(8, result.chapters.size());
        assertEquals("第1章 同名", result.chapters.get(0).title);
        assertEquals("第1章 同名", result.chapters.get(4).title);
    }

    @Test
    public void identicalAdjacentRowsAreNotGloballyDeduplicated() throws Exception {
        CatalogScanner.Result result = CatalogScanner.scan(new PagedRunner(new String[]{
                "第1章 同名", "第1章 同名", "第3章 其他"}));

        assertTrue(result.gapNote, result.trustworthy());
        assertEquals(Arrays.asList("第1章 同名", "第1章 同名", "第3章 其他"), titles(result));
        assertEquals(3, result.allRows.size());
    }

    @Test
    public void repeatedTitlesAloneCannotAnchorTwoPages() throws Exception {
        CatalogScanner.Result result = CatalogScanner.scan(new PagedRunner(new String[]{
                "第1章 甲", "第2章 乙", "第3章 同名", "第3章 同名"},
                new String[]{"第3章 同名", "第3章 同名", "第4章 丙", "第5章 丁"}));

        assertFalse(result.trustworthy());
        assertTrue(result.gapNote, result.gapNote.contains("重叠"));
    }

    @Test
    public void consecutiveNumbersCannotProveAnUnjoinedPageComplete() throws Exception {
        CatalogScanner.Result result = CatalogScanner.scan(new PagedRunner(new String[]{
                "第79章 秘密", "第80章 镜子"},
                new String[]{"第81章 沙滩", "第83章 最后"}));

        // 数字80→81看似连续，但这一页可能漏掉另一个第81章「心脏」。
        assertFalse(result.trustworthy());
        assertTrue(result.gapNote, result.gapNote.contains("重叠"));
    }

    @Test
    public void skippedPagesAndUnjoinedVolumeResetsAreRejected() throws Exception {
        CatalogScanner.Result jumped = CatalogScanner.scan(new PagedRunner(
                new String[]{"第1章 甲", "第2章 乙"}, new String[]{"第20章 丙", "第21章 丁"}));
        CatalogScanner.Result reset = CatalogScanner.scan(new PagedRunner(
                new String[]{"第91章 花叶", "第92章 初次见面"}, new String[]{"第1章 晚餐", "第2章 契约"}));

        assertFalse(jumped.trustworthy());
        assertFalse(reset.trustworthy());
        assertTrue(jumped.gapNote, jumped.gapNote.contains("重叠"));
        assertTrue(reset.gapNote, reset.gapNote.contains("重叠"));
    }

    @Test
    public void aMissingTitleWithinTheScreenIsNotAnAuthoredNumberingMistake() throws Exception {
        CatalogScanner.Result result = CatalogScanner.scan(new PagedRunner(new String[]{
                "第80章 镜子", null, "第81章 沙滩", "第83章 最后"}));

        assertFalse(result.trustworthy());
        assertTrue(result.gapNote, result.gapNote.contains("镜子"));
        assertTrue(result.gapNote, result.gapNote.contains("沙滩"));
    }

    @Test
    public void adjacencyFromAnotherVolumeCannotExcuseAMissingRow() throws Exception {
        CatalogScanner.Result result = CatalogScanner.scan(new PagedRunner(new String[]{
                "卷一", "第1章 同名甲", "第3章 同名乙", "卷二", "第1章 同名甲", null, "第3章 同名乙"}));

        assertFalse(result.trustworthy());
        assertNotNull(result.gapNote);
        assertEquals(4, result.chapters.size());
    }

    @Test
    public void legacyRowNumbersStillDetectAForwardGap() throws Exception {
        CatalogScanner.Result result = CatalogScanner.scan(new PagedRunner(
                new String[]{"1 甲", "2 乙", "5 丙"}));

        assertFalse(result.trustworthy());
        assertTrue(result.gapNote, result.gapNote.contains("缺 2 行"));
    }

    @Test
    public void aClickableListCannotBeMistakenForEachChapterRow() throws Exception {
        PagedRunner runner = new PagedRunner(new String[]{"第1章 甲", "第2章 乙"});
        runner.active = FakeNode.node().clickable(true).add(
                FakeNode.text("第1章 甲").withId("title").withBounds(10, 100, 600, 140),
                FakeNode.text("第2章 乙").withId("title").withBounds(10, 160, 600, 200),
                FakeNode.node().withId("item_cb"));

        CatalogScanner.Result result = CatalogScanner.scan(runner);

        assertFalse(result.trustworthy());
        assertEquals(0, runner.scrollCalls);
        assertTrue(result.gapNote, result.gapNote.contains("整行"));
    }

    @Test
    public void anEmptyTreeDuringScrollingIsWaitedOut() throws Exception {
        PagedRunner runner = new PagedRunner(new String[]{"第1章 甲", "第2章 乙", "第3章 丙", "第4章 丁"},
                new String[]{"第3章 丙", "第4章 丁", "第5章 戊"});
        runner.scrollDelay = 900;
        runner.emptyDuringDelay = true;

        CatalogScanner.Result result = CatalogScanner.scan(runner);

        assertTrue(result.gapNote, result.trustworthy());
        assertEquals(5, result.chapters.size());
    }

    @Test
    public void theOldScreenWhileAGestureIsPendingDoesNotCountAsTheEnd() throws Exception {
        PagedRunner runner = new PagedRunner(new String[]{"第1章 甲", "第2章 乙", "第3章 丙", "第4章 丁"},
                new String[]{"第3章 丙", "第4章 丁", "第5章 戊"});
        runner.scrollDelay = 1_400;

        CatalogScanner.Result result = CatalogScanner.scan(runner);

        assertTrue(result.gapNote, result.trustworthy());
        assertEquals(5, result.chapters.size());
        assertEquals(3, runner.scrollCalls);
    }

    @Test
    public void persistentEmptyAndUnstableScreensCannotProveTheEnd() throws Exception {
        CatalogScanner.Result empty = CatalogScanner.scan(new PagedRunner(
                new String[]{"第1章 甲", "第2章 乙"}, new String[]{}));
        PagedRunner moving = new PagedRunner(new String[]{"第1章 甲", "第2章 乙"}) {
            int offset;
            @Override public void waitMillis(long millis) {
                super.waitMillis(millis);
                active = screen(++offset % 2, new String[]{"第1章 甲", "第2章 乙"});
            }
        };
        CatalogScanner.Result unstable = CatalogScanner.scan(moving);

        assertFalse(empty.trustworthy());
        assertFalse(unstable.trustworthy());
        assertTrue(empty.gapNote, empty.gapNote.contains("没有可读"));
        assertTrue(unstable.gapNote, unstable.gapNote.contains("稳定"));
    }

    @Test
    public void failedScrollingAndThePageLimitCannotProveTheEnd() throws Exception {
        PagedRunner failed = new PagedRunner(new String[]{"第1章 甲", "第2章 乙"});
        failed.scrollAllowed = false;
        CatalogScanner.Result result = CatalogScanner.scan(failed);
        CatalogScanner.Result limited = CatalogScanner.scan(new PagedRunner(
                new String[]{"第1章 甲", "第2章 乙", "第3章 丙", "第4章 丁"},
                new String[]{"第3章 丙", "第4章 丁", "第5章 戊"}), 1);

        assertFalse(result.trustworthy());
        assertTrue(result.gapNote, result.gapNote.contains("滑动没有执行成功"));
        assertFalse(limited.trustworthy());
        assertTrue(limited.truncated);
    }

    @Test
    public void reachingTheEndRequiresUnchangedScreensAndABlockedContainer() throws Exception {
        PagedRunner runner = new PagedRunner(new String[]{"第1章 甲", "第2章 乙"});

        CatalogScanner.Result result = CatalogScanner.scan(runner);

        assertTrue(result.gapNote, result.trustworthy());
        assertEquals(2, runner.scrollCalls);
        assertEquals(2, result.scrolls);
        assertEquals(1, result.endProbes);
    }

    @Test
    public void settlingRemainsCancellable() throws Exception {
        PagedRunner runner = new PagedRunner(new String[]{"第1章 甲", "第2章 乙"}) {
            @Override public void waitMillis(long millis) {
                super.waitMillis(millis);
                testHost.cancelled = true;
            }
        };

        try {
            CatalogScanner.scan(runner);
            throw new AssertionError("取消必须打断目录等待");
        } catch (StepRunner.StepFailure expected) {
            assertEquals(StepRunner.Kind.CANCELLED, expected.kind);
        }
        assertEquals(0, runner.scrollCalls);
    }

    private static List<String> titles(CatalogScanner.Result result) {
        return result.chapters.stream().map(row -> row.title).collect(Collectors.toList());
    }

    private static String[][] pages(List<String> rows, int visible, int step) {
        List<String[]> result = new ArrayList<>();
        for (int start = 0; start < rows.size(); start += step) {
            int end = Math.min(start + visible, rows.size());
            result.add(rows.subList(start, end).toArray(new String[0]));
            if (end == rows.size()) break;
        }
        return result.toArray(new String[0][]);
    }

    private static FakeNode screen(int offset, String[] titles) {
        FakeNode root = FakeNode.node().withBounds(0, 0, 1000, 2000);
        for (int i = 0; i < titles.length; i++) {
            if (titles[i] == null) continue; // 空位保留，模拟某一行的标题没有被无障碍树读到。
            int top = 100 + offset + i * 60;
            root.add(FakeNode.node().clickable(true).withBounds(0, top, 1000, top + 60).add(
                    FakeNode.text(titles[i]).withId("title").withBounds(40, top + 10, 750, top + 50),
                    FakeNode.node().withId("title_lock").withBounds(780, top + 10, 820, top + 50),
                    FakeNode.node().withId("item_cb").withBounds(860, top + 10, 900, top + 50)));
        }
        return root;
    }

    /** 仅替换界面和时间；目录选择器、捕获、拼接及完整性检查均使用生产代码。 */
    private static class PagedRunner extends OfflineRunner {
        final List<NodeView> screens = new ArrayList<>();
        int page;
        int scrollCalls;
        boolean scrollAllowed = true;
        boolean emptyDuringDelay;
        long scrollDelay;
        long readyAt;
        NodeView pending;

        PagedRunner(String[]... pages) throws Exception {
            for (String[] titles : pages) screens.add(screen(0, titles));
            active = screens.get(0);
        }

        @Override public boolean scrollCatalogForward() throws StepFailure {
            checkCancelled();
            scrollCalls++;
            if (!scrollAllowed) return false;
            if (page + 1 < screens.size()) {
                NodeView next = screens.get(++page);
                if (scrollDelay > 0) {
                    readyAt = now + scrollDelay;
                    pending = next;
                    if (emptyDuringDelay) active = FakeNode.node();
                } else active = next;
            }
            return true;
        }

        @Override public boolean scrollForward() {
            throw new AssertionError("目录扫描应使用保留重叠行的短滑动");
        }

        @Override public CatalogScroll probeCatalogContainerForward() {
            return page + 1 >= screens.size() ? CatalogScroll.BLOCKED : CatalogScroll.ACCEPTED;
        }

        @Override public void waitMillis(long millis) {
            super.waitMillis(millis);
            if (pending != null && now >= readyAt) {
                active = pending;
                pending = null;
            }
        }
    }
}
