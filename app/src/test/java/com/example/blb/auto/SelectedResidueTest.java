package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.blb.data.Chapter;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 买这一章之前「先清掉上一章留在页面上的勾」这条判据。
 *
 * <p>为什么单独钉住：2026-09-15 真机现场（`blb-log-订阅` L180-190）每个号买成 1 章之后，
 * 下一章一点就变成「已选 2 章」→ 被「必须正好 1 章」的护栏拦下 → 每个号一轮只订得到 1 章，
 * 表现出来就是用户说的「代券明明够却换号」。清残留的顺序（按行清 → 重进页面 → 不许买）
 * 就是这条 bug 的判据本体，而它在真机上要跑一整趟 8 个号才看得到一次。
 *
 * <p>这个项目没有 Robolectric，界面代码测不了，所以判据抽在
 * {@link SubscribeTask.PickerPage} 后面，这里用假页面跑同一段代码。
 */
public class SelectedResidueTest {

    private static Chapter chapter(int no, String title) {
        Chapter c = new Chapter();
        c.id = no;
        c.novelId = 1;
        c.chapterNo = no;
        c.title = "第" + no + "章 " + title;
        return c;
    }

    /**
     * 假的选择章节页：只记「已选」和调用过的动作。
     *
     * @param counts 每次读「已选」依次返回的值（读不到用 -1）；读完就停在最后一个值上
     */
    private static class FakePage implements SubscribeTask.PickerPage {
        final List<Integer> counts = new ArrayList<>();
        final List<String> unselected = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        int reads;
        int leaves;
        int reopens;
        /** 置 true＝上一章那一行找不到或点不动（用来走「改走重进页面」那条路）。 */
        boolean unselectFails;

        FakePage(Integer... values) {
            for (Integer value : values) counts.add(value);
        }

        @Override public int selectedCount() {
            int value = counts.get(Math.min(reads, counts.size() - 1));
            reads++;
            return value;
        }

        @Override public boolean unselect(Chapter target, String label) {
            unselected.add(label);
            return !unselectFails;
        }

        @Override public void leave() {
            leaves++;
        }

        @Override public void reopen() {
            reopens++;
        }

        @Override public void log(String message) {
            logs.add(message);
        }
    }

    // ---------- 纯判据 ----------

    @Test
    public void theStepTableCoversEveryWayOut() {
        assertEquals(SubscribeTask.ResidueStep.PROCEED,
                SubscribeTask.residueStep(0, true, false, false));
        assertEquals("上一章知道就先按它那一行清",
                SubscribeTask.ResidueStep.CLEAR_BY_ROW,
                SubscribeTask.residueStep(1, true, false, false));
        assertEquals("不知道上一章是哪一章，只能重进页面",
                SubscribeTask.ResidueStep.REENTER,
                SubscribeTask.residueStep(1, false, false, false));
        assertEquals("按行清过了还没清掉，就重进页面",
                SubscribeTask.ResidueStep.REENTER,
                SubscribeTask.residueStep(2, true, true, false));
        assertEquals("两条路都走完还留着勾：不买",
                SubscribeTask.ResidueStep.GIVE_UP,
                SubscribeTask.residueStep(1, true, true, true));
        assertEquals("「已选」读不到＝不能当成干净的，直接不买",
                SubscribeTask.ResidueStep.GIVE_UP,
                SubscribeTask.residueStep(-1, true, false, false));
    }

    /** 「买单章时仍然必须已选 == 1」这条规则一个字都没放宽。 */
    @Test
    public void buyingStillRequiresExactlyOneSelectedChapter() {
        assertFalse("0 章＝没勾上，不许买", SubscribeTask.selectionIsExactlyTheTarget(0));
        assertTrue(SubscribeTask.selectionIsExactlyTheTarget(1));
        assertFalse("2 章＝勾中一批，按下去会一次买两章",
                SubscribeTask.selectionIsExactlyTheTarget(2));
        assertFalse("读不到（-1）同样不许买", SubscribeTask.selectionIsExactlyTheTarget(-1));
    }

    // ---------- 清残留的顺序 ----------

    @Test
    public void aCleanPageGoesStraightToClickingTheTarget() throws Exception {
        FakePage page = new FakePage(0);
        SubscribeTask.ResidueResult result =
                SubscribeTask.clearSelectionResidue(page, chapter(111, "旧章"), "第112章");

        assertEquals(0, result.selected);
        assertFalse(result.reentered);
        assertTrue(page.unselected.isEmpty());
        assertEquals("干净的页面不该退出去重进", 0, page.leaves);
        assertEquals(0, page.reopens);
    }

    @Test
    public void aLeftoverCheckIsClearedByItsOwnRowBeforeBuying() throws Exception {
        // 进页读到 1 章残留 → 按上一章那一行清 → 读回 0
        FakePage page = new FakePage(1, 0);
        SubscribeTask.ResidueResult result =
                SubscribeTask.clearSelectionResidue(page, chapter(111, "旧章"), "第112章");

        assertEquals("清干净了才允许买", 0, result.selected);
        assertFalse("按行就清掉了，不必重进页面", result.reentered);
        assertEquals(1, page.unselected.size());
        assertEquals("第111章", page.unselected.get(0));
        assertEquals(0, page.reopens);
        assertTrue("日志要说清用什么清的：" + page.logs,
                page.logs.toString().contains("按「第111章」那一行清"));
    }

    @Test
    public void whenThePreviousRowCannotClearItThePageIsReentered() throws Exception {
        // 读到 1 → 按行清之后还是 1 → 退出去重进 → 读回 0
        FakePage page = new FakePage(1, 1, 0);
        SubscribeTask.ResidueResult result =
                SubscribeTask.clearSelectionResidue(page, chapter(111, "旧章"), "第112章");

        assertEquals(0, result.selected);
        assertTrue("重进过页面：调用方必须重新定位目标行", result.reentered);
        assertEquals("必须先真的退出去，否则 openChapterPicker 见页面有「已选」就不重进",
                1, page.leaves);
        assertEquals(1, page.reopens);
        assertTrue("日志要写清走了重进这条路：" + page.logs,
                page.logs.toString().contains("重进选择章节页之后"));
    }

    @Test
    public void anUnknownPreviousChapterGoesStraightToReentering() throws Exception {
        FakePage page = new FakePage(2, 0);
        SubscribeTask.ResidueResult result =
                SubscribeTask.clearSelectionResidue(page, null, "第112章");

        assertEquals(0, result.selected);
        assertTrue(result.reentered);
        assertTrue("不知道上一章是哪一章，不该瞎按别的行", page.unselected.isEmpty());
        assertEquals(1, page.reopens);
    }

    @Test
    public void whenNothingClearsTheResidueTheChapterIsNotBought() throws Exception {
        // 读到 1 → 按行清还是 1 → 重进还是 1 → 放弃
        FakePage page = new FakePage(1, 1, 1);
        SubscribeTask.ResidueResult result =
                SubscribeTask.clearSelectionResidue(page, chapter(111, "旧章"), "第112章");

        assertEquals(1, result.selected);
        assertTrue("重进过", result.reentered);

        SubscribeTask.Result failure = result.failure("第112章");
        assertEquals(SubscribeTask.Status.FAILED, failure.status);
        assertTrue("话要写准：这是勾没清掉，" + failure.message,
                failure.message.contains("勾清不掉"));
        assertTrue("要点明它不是余额问题：" + failure.message,
                failure.message.contains("不是余额不足"));
        assertFalse("不能把原因说成页面报余额不足：" + failure.message,
                failure.message.contains("页面显示余额不足") || failure.message.contains("页面说余额不足"));
        assertTrue("要说清这次没订、也没点下载：" + failure.message,
                failure.message.contains("没订") && failure.message.contains("没敢点「立即下载」"));
    }

    @Test
    public void anUnreadableSelectedCountNeverCountsAsClean() throws Exception {
        FakePage page = new FakePage(-1);
        SubscribeTask.ResidueResult result =
                SubscribeTask.clearSelectionResidue(page, chapter(111, "旧章"), "第112章");

        assertEquals(-1, result.selected);
        assertEquals("读不到就不折腾页面，直接不买", 0, page.leaves);
        assertEquals(0, page.reopens);
        SubscribeTask.Result failure = result.failure("第112章");
        assertEquals(SubscribeTask.Status.FAILED, failure.status);
        assertTrue(failure.message, failure.message.contains("没能确认"));
    }

    @Test
    public void aRowThatCannotBePressedFallsBackToReentering() throws Exception {
        // 按行清点不动 → 「已选」还是 1 → 重进页面 → 0
        FakePage page = new FakePage(1, 1, 0);
        page.unselectFails = true;   // 行找不到或点不动
        SubscribeTask.ResidueResult result =
                SubscribeTask.clearSelectionResidue(page, chapter(111, "旧章"), "第112章");

        assertEquals(0, result.selected);
        assertTrue(result.reentered);
        assertEquals(1, page.reopens);
        assertTrue("日志要说清是按行清不动才重进的：" + page.logs,
                page.logs.toString().contains("找不到或点不动"));
    }
}
