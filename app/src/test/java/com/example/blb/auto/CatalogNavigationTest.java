package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 运行生产捕获、等待、回顶与拼接链路，只替换窗口、动作回执和时间。
 * list_view/layoutRoot/title/goto_top 的结构取自 v7-directory-tail-user-dump.txt；
 * 中段、短暂残行、动作误移是离线回归场景，不冒充真机执行结果。
 */
public class CatalogNavigationTest {
    @Test public void enteringAtChapter81UsesMeasuredButtonThenIndependentlyProvesTop() throws Exception {
        NavigationRunner runner = new NavigationRunner(normalPages());
        runner.show(1);
        assertTrue(runner.activeText().contains("第81章 心脏"));

        CatalogScanner.toTop(runner, null, 120);

        assertEquals(0, runner.page);
        assertEquals(1, runner.topClicks);
        assertTrue(runner.backwardProbes >= 2);
        assertTrue(runner.logText().contains("第81章 心脏"));
        assertTrue(runner.logText().contains("同一纵向列表反向拒绝滚动"));
    }

    /**
     * 2026-09-15 现场：到顶后每多滑一次就触发一次下拉刷新，页面跳回已读章节，循环 122 次仍没到顶。
     * 回顶只许用应用自己的按钮和列表容器的原生反向动作，任何情况下都不派发向下手势。
     */
    @Test public void returningToTheTopNeverDispatchesADownwardGesture() throws Exception {
        NavigationRunner runner = new NavigationRunner(normalPages());
        runner.show(2);

        CatalogScanner.toTop(runner, null, 120);

        assertEquals(0, runner.page);
        assertTrue(runner.backwardProbes > 0);
        assertTrue(runner.logText().contains("目录回顶已确认"));
    }

    /** 没有回顶按钮时也只能靠容器动作往回走，同样不许派发向下手势。 */
    @Test public void withoutATopButtonReturnStillUsesOnlyContainerActions() throws Exception {
        NavigationRunner runner = new NavigationRunner(normalPages());
        runner.hideTopButton = true;
        runner.show(2);

        CatalogScanner.toTop(runner, null, 120);

        assertEquals(0, runner.topClicks);
        assertEquals(0, runner.page);
        assertTrue(runner.backwardProbes > 0);
    }

    @Test public void firstScanAcceptsASectionAtPhysicalTopWithoutGuessingPrintedChapterOne() throws Exception {
        NavigationRunner runner = new NavigationRunner(normalPages());
        CatalogScanner.toTop(runner, null, 120);
        assertTrue(runner.logText().contains("首行「铃兰花」"));
        assertEquals(0, runner.page);
    }

    @Test public void aStablePartialRowDuringReturnDoesNotMakeTheWholePageUnstable() throws Exception {
        List<RowSpec> partial = rows("第81章 心脏", "第81章 沙滩", "第83章 最后", "第84章 迷梦");
        partial.get(3).missingTitle = true;
        NavigationRunner runner = new NavigationRunner(normalPages());
        runner.show(2);
        runner.topAction = TopAction.NONE;
        runner.oneBackwardFrame = directory(partial);

        CatalogScanner.toTop(runner, null, 120);

        assertEquals(0, runner.page);
        assertTrue(runner.backwardProbes > 0);
        assertTrue(runner.logText().contains("目录回顶已确认"));
    }

    @Test public void aTemporaryEmptyTreeDuringReturnIsRereadBeforeAnyBoundaryDecision() throws Exception {
        NavigationRunner runner = new NavigationRunner(normalPages());
        runner.show(2);
        runner.topAction = TopAction.NONE;
        runner.temporaryBackwardFrame = FakeNode.node();
        runner.temporaryDelay = 3_000;

        CatalogScanner.toTop(runner, null, 120);

        assertEquals(0, runner.page);
        assertTrue(runner.logText().contains("有限重读"));
        assertTrue(runner.backwardProbes > 0);
    }

    @Test public void anEmptyButStableDirectoryCannotProveTop() throws Exception {
        NavigationRunner runner = new NavigationRunner(Arrays.asList(new ArrayList<>()));
        StepRunner.StepFailure failure = topFailure(runner, null, 120);

        assertTrue(failure.getMessage().contains("没有可读"));
        assertTrue(failure.getMessage().contains("列表项=0"));
        assertEquals(0, runner.backwardProbes);
        assertTrue(runner.topClicks <= 2);
        assertTrue(runner.now < 30_000);
    }

    @Test public void wrongPageWithACatalogLikeListIsNeverClickedOrProbed() throws Exception {
        NavigationRunner runner = new NavigationRunner(normalPages());
        ((FakeNode) runner.active).add(FakeNode.text("已选0章").withId("tvSelect"));
        topFailure(runner, null, 120);
        assertEquals(0, runner.topClicks);
        assertEquals("认错页面时连容器动作都不许试", 0, runner.backwardProbes);
    }

    @Test public void duplicateVisibleTopButtonsUseTheVerifiedListInsteadOfGuessingCoordinates() throws Exception {
        NavigationRunner runner = new NavigationRunner(normalPages());
        runner.duplicateTopButton = true;
        runner.show(2);

        CatalogScanner.toTop(runner, null, 120);

        assertEquals(0, runner.topClicks);
        assertEquals(0, runner.page);
        assertTrue(runner.logText().contains("goto_top 数量=2"));
    }

    @Test public void noTopButtonStillReturnsUsingOnlyContainerActions() throws Exception {
        NavigationRunner runner = new NavigationRunner(normalPages());
        runner.hideTopButton = true;
        runner.show(2);

        CatalogScanner.toTop(runner, null, 120);

        assertEquals(0, runner.topClicks);
        assertEquals(0, runner.page);
        assertTrue(runner.backwardProbes > 0);
    }

    @Test public void aButtonThatMovesToTheBottomDoesNotAuthorizeScanningFromThere() throws Exception {
        NavigationRunner runner = new NavigationRunner(manyPages());
        runner.show(1);
        runner.topAction = TopAction.BOTTOM;

        StepRunner.StepFailure failure = topFailure(runner, null, 0);

        assertTrue(runner.page > 0);
        assertTrue(failure.getMessage().contains("达到次数上限"));
        assertTrue(failure.getMessage().contains("反向动作=4"));
        assertFalse(runner.logText().contains("回顶已确认"));
    }

    @Test public void unchangedContentWithAnAcceptedScrollIsNotTop() throws Exception {
        NavigationRunner runner = new NavigationRunner(normalPages());
        runner.show(1);
        runner.topAction = TopAction.NONE;
        runner.neverMoveBackward = true;
        runner.alwaysAcceptBackwardProbe = true;

        StepRunner.StepFailure failure = topFailure(runner, null, 120);

        assertTrue(failure.getMessage().contains("不能确认顶部"));
        assertTrue(runner.topClicks <= 2);
        assertTrue(runner.backwardProbes < 30);
        assertFalse(runner.logText().contains("回顶已确认"));
    }

    @Test public void anUnavailableContainerCannotProveTheTop() throws Exception {
        NavigationRunner runner = new NavigationRunner(normalPages());
        runner.unavailableBackwardProbe = true;
        StepRunner.StepFailure failure = topFailure(runner, null, 120);

        assertTrue(failure.getMessage(), failure.getMessage().contains("读不到可核实的纵向目录容器"));
        assertFalse(runner.logText().contains("回顶已确认"));
    }

    /**
     * 2026-09-15 现场那条向下手势已经把整趟同步拖死（顶部触发下拉刷新，循环 122 次）。
     * 修复不只是"不再调用"：入口本身被删掉，谁也不能再把它加回来而当测试看不出来。
     */
    @Test public void theCatalogAndDetailHaveNoDownwardGestureEntryPointLeft() {
        for (String name : new String[]{"scrollCatalogBackward", "scrollDetailBackward"}) {
            try {
                StepRunner.class.getMethod(name);
                fail(name + " 又能被调用了：向后的手势入口必须不存在");
            } catch (NoSuchMethodException expected) {
                // 正是我们要的：类型上就没有这个入口。
            }
        }
        try {
            BlbAccessibilityService.class.getMethod("swipeCatalogDown", NodeView.class);
            fail("swipeCatalogDown 又能被调用了：向下的目录手势会触发下拉刷新");
        } catch (NoSuchMethodException expected) {
            // 同上。
        }
    }

    @Test public void findingTheOldFirstTitleBelowANewFirstRowDoesNotConfirmTheOldCatalog() throws Exception {
        NavigationRunner runner = new NavigationRunner(Arrays.asList(rows("新增序言", "第1章 开始", "第2章 后续")));
        StepRunner.StepFailure failure = topFailure(runner, "第1章 开始", 120);
        assertTrue(failure.getMessage().contains("当前首行不是先前首行"));
        assertTrue(failure.getMessage().contains("新增序言"));
    }

    /**
     * 2026-09-15 第四次现场（blb-log-f1.1.4.txt 第 86–111 行）：选择章节页是 RecyclerView，
     * 翻页后上边被裁掉的那一行连 title 节点都不在可见树里（只剩居中的锁和勾选圈），
     * 于是「可靠的重叠行」判不出来，整趟同步中止。它必然是重叠区前一行，必须能跳过它对位。
     */
    @Test public void aTitlelessFragmentAtTheTopOfThePickerListDoesNotBreakTheOverlap() throws Exception {
        NavigationRunner directoryRunner = new NavigationRunner(normalPages());
        directoryRunner.show(0);
        CatalogScanner.Result directory = CatalogScanner.scanDirectory(directoryRunner);
        assertTrue(directory.gapNote, directory.trustworthy());

        NavigationRunner picker = new NavigationRunner(fragmentPages());
        picker.picker = true;
        picker.show(0);
        CatalogScanner.toTop(picker, null, 120);
        CatalogScanner.Result scan = CatalogScanner.scan(picker, directory);

        assertNull("屏边残行不能变成『没有可靠的重叠行』：" + scan.gapNote, scan.gapNote);
        assertTrue(scan.trustworthy());
        assertEquals(titles(directory.chapters), titles(scan.chapters));
        assertEquals(titles(directory.allRows), titles(scan.allRows));
        assertEquals(10, scan.allRows.size());
    }

    /**
     * 反例：读不出来的行高度正常（不是被视口裁短的残行）时，仍然必须拦住整趟扫描 ——
     * 「屏边残行」这条豁免不能变成「读不出来就跳过」。
     */
    @Test public void anUnreadableFullHeightRowStillBlocksTheScan() throws Exception {
        List<RowSpec> second = new ArrayList<>(normalPages().get(2));
        second.get(0).missingTitle = true;   // 高度照旧，只是没有 title：不是屏边残行
        NavigationRunner runner = new NavigationRunner(normalPages());
        runner.show(0);
        CatalogScanner.Result directory = CatalogScanner.scanDirectory(runner);

        NavigationRunner picker = new NavigationRunner(
                Arrays.asList(normalPages().get(1), second));
        picker.picker = true;
        picker.show(0);
        CatalogScanner.Result scan = CatalogScanner.scan(picker, directory);

        assertFalse(scan.trustworthy());
        assertTrue("必须仍然报缺口：" + scan.gapNote,
                scan.gapNote != null && scan.gapNote.contains("没有可靠的重叠行"));
    }

    /**
     * 反方向：残行在本屏**末尾**（真机 blb-log-f1.1.5 第 88 行第 15 行：`rowBounds=[0,2181,1080,2182]`，
     * 1px）。它落在上一屏的后缀里，同样会让「后缀↔前缀」逐位置对齐整体错位。
     */
    @Test public void aTitlelessFragmentAtTheBottomOfThePickerListDoesNotBreakTheOverlap() throws Exception {
        NavigationRunner directoryRunner = new NavigationRunner(normalPages());
        directoryRunner.show(0);
        CatalogScanner.Result directory = CatalogScanner.scanDirectory(directoryRunner);
        assertTrue(directory.gapNote, directory.trustworthy());

        NavigationRunner picker = new NavigationRunner(bottomFragmentPages());
        picker.picker = true;
        picker.show(0);
        CatalogScanner.toTop(picker, null, 120);
        CatalogScanner.Result scan = CatalogScanner.scan(picker, directory);

        assertNull("屏边残行不能变成『没有可靠的重叠行』：" + scan.gapNote, scan.gapNote);
        assertTrue(scan.trustworthy());
        assertEquals(titles(directory.chapters), titles(scan.chapters));
        assertEquals(titles(directory.allRows), titles(scan.allRows));
        assertEquals(10, scan.allRows.size());
    }

    @Test public void productionCaptureKeepsUnnumberedChaptersAndAuthorDuplicateNumbers() throws Exception {
        NavigationRunner runner = new NavigationRunner(normalPages());
        CatalogScanner.toTop(runner, null, 120);

        CatalogScanner.Result directory = CatalogScanner.scanDirectory(runner);

        assertTrue(directory.gapNote, directory.trustworthy());
        assertEquals(Arrays.asList("铃兰花", "星彩"), directory.skipped);
        assertEquals(Arrays.asList("第1章 开始", "第2章 后续", "第81章 心脏", "第81章 沙滩",
                        "第83章 最后", "一卷总结", "藏在地下室的恶鬼（上）", "迷信的可怖后果"),
                titles(directory.chapters));
        assertEquals(81, directory.chapters.get(2).printedNo);
        assertEquals(81, directory.chapters.get(3).printedNo);
        assertEquals(0, directory.freeCount());
        assertTrue(directory.endProbes > 0);

        runner.picker = true;
        runner.show(0);
        CatalogScanner.toTop(runner, null, 120);
        CatalogScanner.Result picker = CatalogScanner.scan(runner, directory);
        assertTrue(picker.gapNote, picker.trustworthy());
        assertEquals(titles(directory.chapters), titles(picker.chapters));
    }

    @Test public void aMissingTitleIsRetainedAtItsOriginalListPositionAndBlocksPublishing() throws Exception {
        List<RowSpec> source = rows("第1章 甲", "第2章 乙", "暂缺标题", "第4章 丁", "第5章 戊");
        source.get(2).missingTitle = true;
        NavigationRunner runner = new NavigationRunner(Arrays.asList(source));

        CatalogScanner.Result result = CatalogScanner.scanDirectory(runner);

        assertFalse(result.trustworthy());
        assertEquals(5, result.allRows.size());
        assertEquals("第1章 甲", result.allRows.get(0).title);
        assertEquals("", result.allRows.get(2).title);
        assertEquals("第4章 丁", result.allRows.get(3).title);
        assertTrue(result.rowProblems.toString().contains("没有 title 节点"));
        assertFalse(result.unresolved.isEmpty());
        assertFalse(result.truncated);
    }

    @Test public void anEmptyTitleZeroBoundsAndTwoTitlesRemainExplicitUnresolvedRows() throws Exception {
        for (int defect = 0; defect < 4; defect++) {
            List<RowSpec> source = rows("第1章 甲", "第2章 乙", "第3章 丙", "第4章 丁", "第5章 戊");
            if (defect == 0) source.get(2).text = "";
            if (defect == 1) source.get(2).zeroRowBounds = true;
            if (defect == 2) source.get(2).zeroTitleBounds = true;
            if (defect == 3) source.get(2).twoTitles = true;
            NavigationRunner runner = new NavigationRunner(Arrays.asList(source));

            CatalogScanner.Result result = CatalogScanner.scanDirectory(runner);

            assertFalse("缺陷 " + defect, result.trustworthy());
            assertEquals(5, result.allRows.size());
            assertFalse(result.rowProblems.isEmpty());
            assertFalse(result.unresolved.isEmpty());
            assertTrue(runner.forwardCalls <= 4);
        }
    }

    @Test public void aMissingTitleCanBeRecoveredOnlyWithTwoOtherMovingAnchorsAndItsOwnPosition() throws Exception {
        List<RowSpec> first = rows("第1章 甲", "第2章 乙", "第3章 丙", "第4章 丁", "第5章 戊", "第6章 己");
        first.get(4).missingTitle = true;
        List<RowSpec> second = rows("第3章 丙", "第4章 丁", "第5章 戊", "第6章 己", "第7章 庚");
        NavigationRunner runner = new NavigationRunner(Arrays.asList(first, second));

        CatalogScanner.Result result = CatalogScanner.scanDirectory(runner);

        assertTrue(result.gapNote, result.trustworthy());
        assertEquals(7, result.chapters.size());
        assertEquals("第5章 戊", result.chapters.get(4).title);
        assertTrue(result.rowProblems.isEmpty());
        assertTrue(result.unresolved.isEmpty());
    }

    @Test public void aMissingTitleWithoutReliableOverlapCannotBeFilledByGuessingItsNumber() throws Exception {
        List<RowSpec> first = rows("第1章 甲", "第2章 乙", "第3章 丙");
        first.get(2).missingTitle = true;
        NavigationRunner runner = new NavigationRunner(Arrays.asList(first, rows("第3章 丙", "第4章 丁", "第5章 戊")));

        CatalogScanner.Result result = CatalogScanner.scanDirectory(runner);

        assertFalse(result.trustworthy());
        assertTrue(result.gapNote.contains("重叠"));
        assertEquals("", result.allRows.get(2).title);
    }

    @Test public void aStableEmptyTreeDuringScanNeverBecomesAnAcceptedEnd() throws Exception {
        NavigationRunner runner = new NavigationRunner(Arrays.asList(rows("第1章 甲", "第2章 乙"), new ArrayList<>()));
        CatalogScanner.Result result = CatalogScanner.scanDirectory(runner);
        assertFalse(result.trustworthy());
        assertTrue(result.truncated);
        assertTrue(result.gapNote.contains("没有可读"));
        assertEquals(0, result.endProbes);
    }

    @Test public void temporaryMissingTitlesBeforeCaptureAreAllowedToSettleWithoutLosingRows() throws Exception {
        List<RowSpec> source = rows("第1章 甲", "第2章 乙", "第3章 丙", "第4章 丁");
        NavigationRunner runner = new NavigationRunner(Arrays.asList(source));
        List<RowSpec> incomplete = rows("第1章 甲", "第2章 乙", "第3章 丙", "第4章 丁");
        incomplete.get(2).missingTitle = true;
        runner.active = directory(incomplete);
        runner.pending = directory(source);
        runner.readyAt = runner.now + 1_000;

        CatalogScanner.Result result = CatalogScanner.scanDirectory(runner);

        assertTrue(result.gapNote, result.trustworthy());
        assertEquals(4, result.chapters.size());
        assertTrue(result.rowProblems.isEmpty());
    }

    @Test public void aStablePartialScreenThatLaterCompletesWithoutMovingRefreshesExistingOccurrences() throws Exception {
        List<RowSpec> source = rows("第1章 甲", "第2章 乙", "第3章 丙", "第4章 丁");
        NavigationRunner runner = new NavigationRunner(Arrays.asList(source));
        List<RowSpec> incomplete = rows("第1章 甲", "第2章 乙", "第3章 丙", "第4章 丁");
        incomplete.get(2).missingTitle = true;
        runner.active = directory(incomplete);
        runner.pending = directory(source);
        runner.readyAt = runner.now + 3_000;

        CatalogScanner.Result result = CatalogScanner.scanDirectory(runner);

        assertTrue(result.gapNote, result.trustworthy());
        assertEquals(Arrays.asList("第1章 甲", "第2章 乙", "第3章 丙", "第4章 丁"), titles(result.chapters));
        assertEquals(4, result.allRows.size());
        assertTrue(result.rowProblems.isEmpty());
        assertTrue(result.unresolved.isEmpty());
        assertEquals(1, result.endProbes);
    }

    @Test public void samePositionWithConflictingReadableTitlesCannotRefreshOrPublish() throws Exception {
        List<RowSpec> source = rows("第1章 甲", "第2章 乙", "第3章 丙", "第4章 丁");
        NavigationRunner runner = new NavigationRunner(Arrays.asList(source));
        runner.pending = directory(rows("第1章 甲", "第2章 乙", "第3章 另一本书", "第4章 丁"));
        runner.readyAt = runner.now + 1_500;

        CatalogScanner.Result result = CatalogScanner.scanDirectory(runner);

        assertFalse(result.trustworthy());
        assertTrue(result.gapNote.contains("重叠"));
        assertEquals(0, result.endProbes);
    }

    @Test public void completingAnInvisibleTitleCannotErasePreviouslySeenPositiveState() throws Exception {
        List<RowSpec> source = sequenceRows(1, 4);
        CatalogScanner.Result directory = CatalogScanner.scanDirectory(new NavigationRunner(Arrays.asList(source)));
        assertTrue(directory.gapNote, directory.trustworthy());
        for (int marker = 0; marker < 3; marker++) {
            List<RowSpec> before = sequenceRows(1, 4);
            List<RowSpec> after = sequenceRows(1, 4);
            before.get(2).titleInvisible = true;
            markerOnly(before.get(2), marker);
            markerOnly(after.get(2), marker == 2 ? 1 : 2);
            NavigationRunner runner = new NavigationRunner(Arrays.asList(before));
            runner.picker = true;
            runner.show(0);
            runner.pending = frame(after, true, false, false);
            runner.readyAt = runner.now + 3_000;

            CatalogScanner.Result result = CatalogScanner.scan(runner, directory);

            assertFalse("同位置补齐不能撤销正标记 " + marker, result.trustworthy());
            assertEquals(0, result.endProbes);
            assertStateConflictRetainsMarker(result, 2, marker);
        }
    }

    @Test public void movingOverlapCannotEraseAPositiveMarkerSeenOnTheClippedRow() throws Exception {
        NavigationRunner directoryRunner = new NavigationRunner(Arrays.asList(sequenceRows(1, 12), sequenceRows(10, 18)));
        CatalogScanner.Result directory = CatalogScanner.scanDirectory(directoryRunner);
        assertTrue(directory.gapNote, directory.trustworthy());
        for (int marker = 0; marker < 3; marker++) {
            // 第15行越过2400的视口底边，但其2353..2393范围内的标记节点确实已被读到。
            List<RowSpec> before = sequenceRows(1, 15);
            List<RowSpec> after = sequenceRows(13, 18);
            markerOnly(before.get(14), marker);
            markerOnly(after.get(2), marker == 2 ? 1 : 2);
            NavigationRunner runner = new NavigationRunner(Arrays.asList(before, after));
            runner.picker = true;
            runner.show(0);

            CatalogScanner.Result result = CatalogScanner.scan(runner, directory);

            assertFalse("移动重叠补齐不能撤销正标记 " + marker, result.trustworthy());
            assertEquals(0, result.endProbes);
            assertStateConflictRetainsMarker(result, 14, marker);
        }
    }

    @Test public void aConfirmedStateConflictCannotBeClearedByAnOtherwiseCompleteRescan() throws Exception {
        List<RowSpec> after = sequenceRows(1, 4);
        CatalogScanner.Result directory = CatalogScanner.scanDirectory(new NavigationRunner(Arrays.asList(after)));
        assertTrue(directory.gapNote, directory.trustworthy());
        List<RowSpec> before = sequenceRows(1, 4);
        before.get(2).titleInvisible = true;
        markerOnly(before.get(2), 0);
        NavigationRunner firstRunner = new NavigationRunner(Arrays.asList(before));
        firstRunner.picker = true;
        firstRunner.show(0);
        firstRunner.pending = frame(after, true, false, false);
        firstRunner.readyAt = firstRunner.now + 3_000;
        CatalogScanner.Result first = CatalogScanner.scan(firstRunner, directory);
        assertStateConflictRetainsMarker(first, 2, 0);

        // 单独重读这张新树会得到完整的“免费章”；生产重试入口必须保住前一遍读到的锁。
        NavigationRunner independentRunner = new NavigationRunner(Arrays.asList(after));
        independentRunner.picker = true;
        independentRunner.show(0);
        CatalogScanner.Result independentlyComplete = CatalogScanner.scan(independentRunner, directory);
        assertTrue(independentlyComplete.gapNote, independentlyComplete.trustworthy());
        assertFalse(independentlyComplete.chapters.get(2).state.lock);

        NavigationRunner retryRunner = new NavigationRunner(Arrays.asList(after));
        retryRunner.picker = true;
        retryRunner.show(0);
        CatalogScanner.Result retained = CatalogSync.rescanIfGap(retryRunner, first, directory);

        assertSame(first, retained);
        assertFalse(retained.trustworthy());
        assertStateConflictRetainsMarker(retained, 2, 0);
        assertEquals(0, retryRunner.topClicks);
        assertEquals(0, retryRunner.backwardProbes);
        assertEquals(0, retryRunner.forwardCalls);
        assertTrue(retryRunner.logText().contains("不用重扫覆盖原证据"));
    }

    @Test public void settlingCannotForgetAPositiveMarkerSeenOnlyInTheMiddleObservation() throws Exception {
        CatalogScanner.Result directory = CatalogScanner.scanDirectory(
                new NavigationRunner(Arrays.asList(sequenceRows(1, 4))));
        assertTrue(directory.gapNote, directory.trustworthy());
        for (int scenario = 0; scenario < 9; scenario++) {
            int marker = scenario % 3;
            boolean temporaryEmptyTree = scenario >= 3;
            long emptyUntil = scenario >= 6 ? 4_500 : 2_500;
            List<RowSpec> first = sequenceRows(1, 4);
            List<RowSpec> middle = sequenceRows(1, 4);
            List<RowSpec> last = sequenceRows(1, 4);
            first.get(2).titleInvisible = true;
            middle.get(2).titleInvisible = true;
            markerOnly(first.get(2), -1);
            markerOnly(middle.get(2), marker);
            markerOnly(last.get(2), marker == 2 ? 1 : 2);
            NavigationRunner runner = new NavigationRunner(Arrays.asList(first)) {
                @Override public void waitMillis(long milliseconds) {
                    super.waitMillis(milliseconds);
                    // 每次捕获前重新提供树，三个同位观察均经过真实 settledScreen。
                    List<RowSpec> next = now < 2_000 ? middle : last;
                    if (temporaryEmptyTree && now >= 2_000 && now < emptyUntil) next = new ArrayList<>();
                    active = frame(next, true, false, false);
                }
            };
            runner.picker = true;
            runner.show(0);

            CatalogScanner.Result result = CatalogScanner.scan(runner, directory);

            assertFalse("等待稳定不能忘掉中间已见正标记，场景 " + scenario, result.trustworthy());
            assertStateConflictRetainsMarker(result, 2, marker);
            assertEquals(0, result.endProbes);
        }
    }

    @Test public void aStillPartialMiddleScreenMustRetainNewPositiveFactsBeforeAnyRescan() throws Exception {
        CatalogScanner.Result directory = CatalogScanner.scanDirectory(
                new NavigationRunner(Arrays.asList(sequenceRows(1, 4))));
        assertTrue(directory.gapNote, directory.trustworthy());
        for (int marker = 0; marker < 3; marker++) {
            List<RowSpec> first = sequenceRows(1, 4);
            List<RowSpec> middle = sequenceRows(1, 4);
            List<RowSpec> last = sequenceRows(1, 4);
            first.get(2).titleInvisible = true;
            middle.get(2).titleInvisible = true;
            markerOnly(first.get(2), -1);
            markerOnly(middle.get(2), marker);
            markerOnly(last.get(2), marker == 2 ? 1 : 2);
            NavigationRunner runner = new NavigationRunner(Arrays.asList(first, middle, last));
            runner.picker = true;
            runner.show(0);
            CatalogScanner.Result result = CatalogScanner.scan(runner, directory);

            NavigationRunner retry = new NavigationRunner(Arrays.asList(last));
            retry.picker = true;
            retry.show(0);
            CatalogScanner.Result retained = CatalogSync.rescanIfGap(retry, result, directory);

            assertFalse("中间残行的新事实不能随普通缺口一起重扫丢弃 " + marker, retained.trustworthy());
            assertSame(result, retained);
            assertStateConflictRetainsMarker(retained, 2, marker);
            assertEquals(0, retry.topClicks);
            assertEquals(0, retry.forwardCalls);
        }
    }

    @Test public void navigationRecoveryIsCancellable() throws Exception {
        NavigationRunner runner = new NavigationRunner(normalPages()) {
            @Override public void waitMillis(long milliseconds) {
                super.waitMillis(milliseconds);
                testHost.cancelled = true;
            }
        };
        StepRunner.StepFailure failure = topFailure(runner, null, 120);
        assertEquals(StepRunner.Kind.CANCELLED, failure.kind);
        assertEquals(0, runner.topClicks);
    }

    private static StepRunner.StepFailure topFailure(NavigationRunner runner, String first, int scrolls) throws Exception {
        try {
            CatalogScanner.toTop(runner, first, scrolls);
            throw new AssertionError("没有顶部证据时必须拒绝");
        } catch (StepRunner.StepFailure failure) {
            return failure;
        }
    }

    private static List<String> titles(List<CatalogScanner.Row> rows) {
        return rows.stream().map(row -> row.title).collect(Collectors.toList());
    }

    /**
     * 选择章节页的两屏：第二屏开头是「上边被裁掉、连 title 节点都没有」的残行
     * （真机 childCount=2：只剩居中的锁和勾选圈），其余与 {@link #normalPages()} 同一份目录。
     */
    private static List<List<RowSpec>> fragmentPages() {
        List<RowSpec> all = rows("铃兰花", "第1章 开始", "第2章 后续", "第81章 心脏", "第81章 沙滩",
                "第83章 最后", "一卷总结", "星彩", "藏在地下室的恶鬼（上）", "迷信的可怖后果");
        all.get(0).section = true;
        all.get(7).section = true;
        RowSpec fragment = new RowSpec("");
        fragment.missingTitle = true;
        fragment.clippedTop = true;
        List<RowSpec> second = new ArrayList<>();
        second.add(fragment);
        second.addAll(all.subList(3, 10));
        return Arrays.asList(new ArrayList<>(all.subList(0, 6)), second);
    }

    /** 同上，但残行在第二屏的**末尾**（1px，贴着列表下边）。 */
    private static List<List<RowSpec>> bottomFragmentPages() {
        List<RowSpec> all = rows("铃兰花", "第1章 开始", "第2章 后续", "第81章 心脏", "第81章 沙滩",
                "第83章 最后", "一卷总结", "星彩", "藏在地下室的恶鬼（上）", "迷信的可怖后果");
        all.get(0).section = true;
        all.get(7).section = true;
        RowSpec fragment = new RowSpec("");
        fragment.missingTitle = true;
        fragment.clippedBottom = true;
        List<RowSpec> second = new ArrayList<>(all.subList(3, 10));
        second.add(fragment);
        return Arrays.asList(new ArrayList<>(all.subList(0, 7)), second);
    }

    private static List<List<RowSpec>> normalPages() {
        List<RowSpec> all = rows("铃兰花", "第1章 开始", "第2章 后续", "第81章 心脏", "第81章 沙滩",
                "第83章 最后", "一卷总结", "星彩", "藏在地下室的恶鬼（上）", "迷信的可怖后果");
        all.get(0).section = true;
        all.get(7).section = true;
        return Arrays.asList(new ArrayList<>(all.subList(0, 6)), new ArrayList<>(all.subList(3, 9)),
                new ArrayList<>(all.subList(6, 10)));
    }

    private static List<List<RowSpec>> manyPages() {
        List<List<RowSpec>> pages = new ArrayList<>();
        for (int i = 0; i < 12; i++) pages.add(rows("第" + (i + 1) + "章 甲", "后续乙", "后续丙"));
        return pages;
    }

    private static List<RowSpec> rows(String... titles) {
        List<RowSpec> rows = new ArrayList<>();
        for (String title : titles) rows.add(new RowSpec(title));
        return rows;
    }

    private static List<RowSpec> sequenceRows(int first, int last) {
        List<RowSpec> rows = new ArrayList<>();
        for (int i = first; i <= last; i++) rows.add(new RowSpec("第" + i + "章 章节" + i));
        return rows;
    }

    private static void markerOnly(RowSpec row, int marker) {
        row.lock = marker == 0;
        row.downloaded = marker == 1;
        row.selectable = marker == 2;
    }

    private static void assertStateConflictRetainsMarker(CatalogScanner.Result result, int index, int marker) {
        String markerName = marker == 0 ? "付费锁" : marker == 1 ? "已下载" : "可选标记";
        assertNotNull(result.stateConflict);
        assertTrue(result.stateConflict, result.stateConflict.contains("第" + (index + 1) + "章 章节" + (index + 1)));
        assertTrue(result.stateConflict, result.stateConflict.contains(markerName));
        ChapterRowState retained = result.chapters.get(index).state;
        assertEquals(marker == 0, retained.lock);
        assertEquals(marker == 1, retained.downloaded);
        assertEquals(marker == 2, retained.selectable);
    }

    private static final class RowSpec {
        String text;
        boolean section;
        boolean missingTitle;
        /** 视口上边裁出来的残行：行框只剩一条贴着列表上边的尾巴。 */
        boolean clippedTop;
        /** 视口下边裁出来的残行（真机 blb-log-f1.1.5 第 88 行：1px）。 */
        boolean clippedBottom;
        boolean zeroRowBounds;
        boolean zeroTitleBounds;
        boolean twoTitles;
        boolean titleInvisible;
        boolean lock;
        boolean downloaded;
        boolean selectable = true;

        RowSpec(String text) { this.text = text; }
    }

    private static FakeNode directory(List<RowSpec> rows) { return frame(rows, false, false, false); }

    private static FakeNode frame(List<RowSpec> rows, boolean picker, boolean hideTop, boolean duplicateTop) {
        FakeNode root = FakeNode.node().withClass("android.widget.FrameLayout").withBounds(0, 0, 1080, 2400);
        root.add(FakeNode.text(picker ? "已选 0 章" : "目录列表").withClass("android.widget.TextView")
                .withId(picker ? "tvSelect" : "edit_title").withBounds(440, 137, 640, 205));
        FakeNode list = FakeNode.node().withId(picker ? "downloadRecycler" : "list_view")
                .withClass(picker ? "androidx.recyclerview.widget.RecyclerView" : "android.widget.ListView")
                .scrollable(true).withBounds(0, 381, 1080, 2400);
        for (int i = 0; i < rows.size(); i++) {
            RowSpec spec = rows.get(i);
            int top = 381 + i * 138;
            FakeNode row = FakeNode.node().withClass("android.widget.RelativeLayout").clickable(true)
                    .withBounds(0, top, 1080, top + 138);
            if (spec.section && !picker) row.withId("layoutRoot");
            if (spec.clippedTop) row.withBounds(0, 381, 1080, 415);
            if (spec.clippedBottom) row.withBounds(0, 2399, 1080, 2400);
            if (spec.zeroRowBounds) row.withBounds(0, 0, 0, 0);
            if (!spec.missingTitle) {
                FakeNode title = FakeNode.text(spec.text).withId("title").withClass("android.widget.TextView")
                        .withBounds(0, top + 12, 1080, top + 126);
                if (spec.zeroTitleBounds) title.withBounds(0, 0, 0, 0);
                if (spec.titleInvisible) title.visible(false);
                row.add(title);
                if (spec.twoTitles) row.add(FakeNode.text("另一个标题").withId("title")
                        .withClass("android.widget.TextView").withBounds(0, top + 20, 500, top + 100));
            }
            if (picker && spec.selectable) row.add(FakeNode.node().withId("item_cb").withClass("android.widget.ImageView")
                    .clickable(true).withBounds(1000, top + 40, 1040, top + 80));
            if (picker && spec.lock) row.add(FakeNode.node().withId("title_lock").withClass("android.widget.TextView")
                    .withBounds(940, top + 40, 980, top + 80));
            if (picker && spec.downloaded) row.add(FakeNode.text("已下载").withId("title_check")
                    .withClass("android.widget.TextView").withBounds(1000, top + 40, 1080, top + 80));
            list.add(row);
        }
        root.add(list);
        if (!hideTop) root.add(topButton(55));
        if (duplicateTop) root.add(topButton(850));
        root.add(FakeNode.node().withId("goto_bottom").withClass("android.widget.ImageView")
                .clickable(true).withBounds(55, 2109, 181, 2235));
        return root;
    }

    private static FakeNode topButton(int left) {
        return FakeNode.node().withId("goto_top").withClass("android.widget.ImageView")
                .clickable(true).withBounds(left, 1950, left + 126, 2076);
    }

    private enum TopAction { TOP, BOTTOM, NONE }

    private static class NavigationRunner extends OfflineRunner {
        final List<List<RowSpec>> pages;
        int page;
        int topClicks;
        int backwardProbes;
        int forwardCalls;
        boolean picker;
        boolean hideTopButton;
        boolean duplicateTopButton;
        boolean neverMoveBackward;
        boolean alwaysAcceptBackwardProbe;
        boolean unavailableBackwardProbe;
        TopAction topAction = TopAction.TOP;
        NodeView oneBackwardFrame;
        NodeView temporaryBackwardFrame;
        long temporaryDelay;
        NodeView pending;
        long readyAt;

        NavigationRunner(List<List<RowSpec>> pages) throws Exception {
            this.pages = pages;
            show(0);
        }

        void show(int page) {
            this.page = page;
            active = frame(pages.get(page), picker, hideTopButton, duplicateTopButton);
        }

        String activeText() { return TreeDump.dump(active); }
        String logText() { return String.join("\n", testHost.logs); }

        @Override public void clickNode(String label, NodeView node) throws StepFailure {
            checkCancelled();
            assertEquals("goto_top", node.viewId());
            assertEquals("回到顶部", label);
            topClicks++;
            if (topAction == TopAction.TOP) show(0);
            if (topAction == TopAction.BOTTOM) show(pages.size() - 1);
        }

        @Override public CatalogScroll probeCatalogContainerBackward() throws StepFailure {
            checkCancelled();
            backwardProbes++;
            if (unavailableBackwardProbe) return CatalogScroll.UNAVAILABLE;
            if (alwaysAcceptBackwardProbe) return CatalogScroll.ACCEPTED;
            if (neverMoveBackward) return CatalogScroll.BLOCKED;
            if (page > 0) show(page - 1);
            if (oneBackwardFrame != null) {
                active = oneBackwardFrame;
                oneBackwardFrame = null;
            }
            if (temporaryBackwardFrame != null) {
                pending = active;
                readyAt = now + temporaryDelay;
                active = temporaryBackwardFrame;
                temporaryBackwardFrame = null;
            }
            return page == 0 ? CatalogScroll.BLOCKED : CatalogScroll.ACCEPTED;
        }

        @Override public boolean scrollCatalogForward() throws StepFailure {
            checkCancelled();
            forwardCalls++;
            if (page + 1 < pages.size()) show(page + 1);
            return true;
        }

        @Override public CatalogScroll probeCatalogContainerForward() throws StepFailure {
            checkCancelled();
            return page + 1 == pages.size() ? CatalogScroll.BLOCKED : CatalogScroll.ACCEPTED;
        }

        @Override public void waitMillis(long milliseconds) {
            super.waitMillis(milliseconds);
            if (pending != null && now >= readyAt) {
                active = pending;
                pending = null;
            }
        }
    }
}
