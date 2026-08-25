package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.blb.data.Chapter;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「小说随时更新新章，账本要跟着更新」这件事的判据本身。
 *
 * <p>为什么单独钉住：作者往中间插一章，后面每一章在界面上的位置就整体后移一格，
 * 而章号在这个 App 里就是「界面上第几行」。搬错了会拿别人的章号去买（买错章、花错券），
 * 不搬又会让自动订阅从此卡死 —— 而这个 App 的使用者按不动屏幕，卡死就没人能救。
 * 真机上这要等作者更新才看得到一次，所以只能在这里钉。
 */
public class CatalogAlignTest {

    /** 重排只看行序和标题，这里统一造成「付费、还没买、还能勾」的行。 */
    private static CatalogScanner.Row row(String title) {
        return new CatalogScanner.Row(title, com.example.blb.util.Texts.rowChapterNo(title),
                true, false, true);
    }

    private static List<CatalogScanner.Row> rows(String... titles) {
        List<CatalogScanner.Row> out = new ArrayList<>();
        for (String t : titles) out.add(row(t));
        return out;
    }

    private static Chapter chapter(long id, int no, String title) {
        Chapter c = new Chapter();
        c.id = id;
        c.chapterNo = no;
        c.title = title;
        return c;
    }

    private static Set<Long> bought(Long... ids) {
        return new HashSet<>(Arrays.asList(ids));
    }

    private static CatalogAlign.Plan plan(List<Chapter> ledger, List<CatalogScanner.Row> rows) {
        return CatalogAlign.plan(ledger, rows, 1, Collections.<Long>emptySet());
    }

    /** 新章加在末尾（最常见的更新方式）：位置没动，什么都不用搬，登记新章是扫描那一步的事。 */
    @Test
    public void aChapterAppendedAtTheEndNeedsNoRealign() {
        CatalogAlign.Plan p = plan(
                Arrays.asList(chapter(1, 1, "1 甲"), chapter(2, 2, "2 乙")),
                rows("1 甲", "2 乙", "3 新章"));
        assertNull(p.blocked);
        assertTrue("末尾加章不该搬任何章号", p.nothingToDo());
    }

    /**
     * 作者往中间插了一章：界面上「2 乙」变成「3 乙」，账本里的第2、3章要搬成第3、4章。
     * 认的是名字，不是行首那个号。
     */
    @Test
    public void aChapterInsertedInTheMiddleShiftsTheLedger() {
        CatalogAlign.Plan p = plan(
                Arrays.asList(chapter(11, 1, "1 甲"), chapter(22, 2, "2 乙"),
                        chapter(33, 3, "3 丙")),
                rows("1 甲", "2 插进来的", "3 乙", "4 丙"));
        assertNull(p.blocked);
        assertEquals(2, p.renumber.size());
        assertEquals(Integer.valueOf(3), p.renumber.get(22L));
        assertEquals(Integer.valueOf(4), p.renumber.get(33L));
        assertTrue("第1章没动，不该出现在搬家名单里", !p.renumber.containsKey(11L));
        assertTrue(p.dropIds.isEmpty());
        assertTrue(p.describe(), p.describe().contains("2 章的章号"));
    }

    /** 买过的那一章必须跟着搬，绝不能被当成「界面上没有了」删掉 —— 删了会被重新买一遍。 */
    @Test
    public void aPurchasedChapterIsMovedNotDropped() {
        CatalogAlign.Plan p = CatalogAlign.plan(
                Arrays.asList(chapter(7, 2, "2 已经买过的")),
                rows("1 甲", "2 插进来的", "3 已经买过的"), 1, bought(7L));
        assertNull(p.blocked);
        assertEquals(Integer.valueOf(3), p.renumber.get(7L));
        assertTrue("买过的章一条都不许删", p.dropIds.isEmpty());
    }

    /** 买过的那一章在界面上彻底找不到了（改了名或被删）：停下报告，一章都不买。 */
    @Test
    public void aPurchasedChapterThatVanishedBlocksTheRound() {
        CatalogAlign.Plan p = CatalogAlign.plan(
                Arrays.asList(chapter(7, 2, "2 买过又不见了")),
                rows("1 甲", "2 乙"), 1, bought(7L));
        assertNotNull(p.blocked);
        assertTrue(p.blocked, p.blocked.contains("有号买过"));
        assertTrue("拿不定主意就一个字都别改", p.renumber.isEmpty() && p.dropIds.isEmpty());
    }

    /** 没人买过、界面上也没有了的空登记：删掉就行，不必拦着整轮不买。 */
    @Test
    public void anUnownedGhostChapterIsJustDropped() {
        CatalogAlign.Plan p = plan(
                Arrays.asList(chapter(1, 1, "1 甲"), chapter(9, 2, "2 作者删掉的")),
                rows("1 甲"));
        assertNull(p.blocked);
        assertEquals(Collections.singletonList(9L), p.dropIds);
        assertTrue(p.describe(), p.describe().contains("空登记"));
    }

    /** 界面上有好几行同名、位置又变了：认不出是哪一行，停下，不猜。 */
    @Test
    public void duplicateTitlesThatMovedBlockTheRound() {
        CatalogAlign.Plan p = plan(
                Arrays.asList(chapter(5, 2, "2 同名")),
                rows("1 同名", "2 甲", "3 同名"));
        assertNotNull(p.blocked);
        assertTrue(p.blocked, p.blocked.contains("认不出"));
    }

    /** 同名多行但位置没动：按老位置算，不当成对不上。 */
    @Test
    public void duplicateTitlesThatDidNotMoveAreLeftAlone() {
        CatalogAlign.Plan p = plan(
                Arrays.asList(chapter(5, 1, "同名"), chapter(6, 3, "同名")),
                rows("1 同名", "2 甲", "3 同名"));
        assertNull(p.blocked);
        assertTrue(p.nothingToDo());
    }

    /** 两章对到同一行（比如账本里重复登记过）：说不清，停下。 */
    @Test
    public void twoLedgerChaptersLandingOnOneRowBlockTheRound() {
        CatalogAlign.Plan p = plan(
                Arrays.asList(chapter(1, 1, "甲"), chapter(2, 2, "甲")),
                rows("7 甲", "8 乙"));
        assertNotNull(p.blocked);
        assertTrue(p.blocked, p.blocked.contains("认不出谁是谁"));
    }

    /** 顺序被整体打乱（作者重排章节）：「下一章该买哪一章」失去意义，停下。 */
    @Test
    public void scrambledOrderBlocksTheRound() {
        CatalogAlign.Plan p = plan(
                Arrays.asList(chapter(1, 1, "甲"), chapter(2, 2, "乙")),
                rows("1 乙", "2 甲"));
        assertNotNull(p.blocked);
        assertTrue(p.blocked, p.blocked.contains("顺序反了"));
        assertTrue("停下的时候一个字都不许改", p.renumber.isEmpty() && p.dropIds.isEmpty());
    }

    /**
     * 起始章跟着搬。「从第 48 章起买」说的是那一章本身；作者往前面插了一章，
     * 起始章不跟着 +1 就会把已经买过的那一章又算成待买。
     */
    @Test
    public void theStartChapterFollowsItsChapter() {
        CatalogAlign.Plan p = CatalogAlign.plan(
                Arrays.asList(chapter(1, 1, "1 甲"), chapter(2, 2, "2 乙"), chapter(3, 3, "3 丙")),
                rows("1 甲", "2 插进来的", "3 乙", "4 丙"), 2, Collections.<Long>emptySet());
        assertEquals(3, p.newStartChapterNo);
        assertTrue(p.describe(), p.describe().contains("起始章"));
    }

    /** 从第 1 章起买的时候没什么可搬的，也别乱改起始章。 */
    @Test
    public void startingFromChapterOneIsNeverTouched() {
        CatalogAlign.Plan p = plan(
                Arrays.asList(chapter(1, 1, "1 甲")),
                rows("1 甲", "2 乙"));
        assertEquals(-1, p.newStartChapterNo);
    }

    /** 空账本、空界面都不该崩，也不该搬任何东西。 */
    @Test
    public void emptyInputsDoNothing() {
        assertTrue(plan(null, rows("1 甲")).nothingToDo());
        assertTrue(plan(new ArrayList<Chapter>(), rows("1 甲")).nothingToDo());
        assertTrue(plan(Arrays.asList(chapter(1, 1, "甲")), rows()).nothingToDo());
    }

    /** 老账本里连标题都没登记：认不出身份，只能留在原位；越界就停下。 */
    @Test
    public void ledgerRowsWithoutATitleStayPutOrBlock() {
        CatalogAlign.Plan p = plan(Arrays.asList(chapter(1, 2, null)), rows("1 甲", "2 乙"));
        assertNull(p.blocked);
        assertTrue(p.nothingToDo());

        CatalogAlign.Plan over = plan(Arrays.asList(chapter(1, 5, null)), rows("1 甲", "2 乙"));
        assertNotNull(over.blocked);
        assertTrue(over.blocked, over.blocked.contains("没登记标题"));
    }
}
