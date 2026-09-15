package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

/** 用真实视口裁剪形状复现半行：可读到标题不代表同时可读到下面的锁和勾选圈。 */
public class CatalogScannerClippingTest {

    private static final int VIEWPORT_TOP = 200;
    private static final int VIEWPORT_BOTTOM = 1000;
    private static final int ROW_HEIGHT = 120;

    @Test
    public void aTrailingTitleOnlyRowIsCompletedOnTheNextScreenBeforePublishingItsState()
            throws Exception {
        ClippingRunner runner = new ClippingRunner(
                page(1, 7, VIEWPORT_TOP, 0, 0, false),
                page(4, 9, VIEWPORT_TOP, 0, 0, false));

        CatalogScanner.Result result = CatalogScanner.scan(runner);

        assertTrue(result.gapNote, result.trustworthy());
        assertEquals(9, result.chapters.size());
        CatalogScanner.Row recovered = result.chapters.get(6);
        assertEquals("第7章 章节7", recovered.title);
        assertTrue(recovered.state.lock);
        assertTrue(recovered.state.selectable);
        assertFalse(recovered.state.downloaded);
        assertFalse("首屏没读到锁不能把付费章变成免费章", recovered.state.free());
        assertEquals(9, result.buyableCount());
        assertEquals(0, result.freeCount());
        assertEquals(3, runner.scrollCalls);
        assertTrue(runner.presses.isEmpty());
    }

    @Test
    public void aClippedRowRectangleCanLaterRevealADownloadedPaidChapter() throws Exception {
        ClippingRunner runner = new ClippingRunner(
                page(1, 7, VIEWPORT_TOP, 0, 7, true),
                page(4, 9, VIEWPORT_TOP, 0, 7, true));

        CatalogScanner.Result result = CatalogScanner.scan(runner);

        assertTrue(result.gapNote, result.trustworthy());
        assertEquals(9, result.chapters.size());
        CatalogScanner.Row recovered = result.chapters.get(6);
        assertEquals("第7章 章节7", recovered.title);
        assertTrue(recovered.state.lock);
        assertTrue(recovered.state.downloaded);
        assertFalse(recovered.state.selectable);
        assertTrue(recovered.state.deviceHasIt());
        assertFalse(recovered.state.free());
        assertEquals(8, result.buyableCount());
        assertEquals(0, result.freeCount());
        assertTrue(runner.presses.isEmpty());
    }

    @Test
    public void aVisibleCheckboxWithoutTheClippedLockIsRecheckedOnTheNextScreen()
            throws Exception {
        ClippingRunner runner = new ClippingRunner(
                withVisibleCheckboxAtBottom(page(1, 7, VIEWPORT_TOP, 0, 0, false)),
                page(4, 9, VIEWPORT_TOP, 0, 0, false));

        CatalogScanner.Result result = CatalogScanner.scan(runner);

        assertTrue(result.gapNote, result.trustworthy());
        assertEquals(9, result.chapters.size());
        assertTrue("下一屏读到的锁必须替换屏尾的临时状态", result.chapters.get(6).state.lock);
        assertEquals(9, result.buyableCount());
        assertEquals("只看见圆圈不足以确认是免费章", 0, result.freeCount());
        assertTrue(runner.presses.isEmpty());
    }

    @Test
    public void aVisibleCheckboxCannotTurnAnUnresolvedClippedPaidRowIntoAFreeChapter()
            throws Exception {
        ClippingRunner runner = new ClippingRunner(
                withVisibleCheckboxAtBottom(page(1, 7, VIEWPORT_TOP, 0, 0, false)));

        CatalogScanner.Result result = CatalogScanner.scan(runner);

        assertFalse("锁所在区域一直不可见，不能根据圆圈判成免费章", result.trustworthy());
        assertNotNull(result.gapNote);
        assertEquals(0, result.freeCount());
        assertTrue(runner.presses.isEmpty());
    }

    @Test
    public void aTopClippedTitleDoesNotInvalidateTwoOtherCompleteMovingAnchors()
            throws Exception {
        // 第4行原来标题top=578，后来物理top=178但被裁到200；它的表观位移只有378。
        // 第5、6行标题各上移400，才是这一次滚动可用的两个完整锚点。
        ClippingRunner runner = new ClippingRunner(
                page(1, 6, VIEWPORT_TOP, 0, 0, false),
                page(4, 9, 160, 0, 0, false));

        CatalogScanner.Result result = CatalogScanner.scan(runner);

        assertTrue(result.gapNote, result.trustworthy());
        assertEquals(9, result.chapters.size());
        for (int i = 0; i < result.chapters.size(); i++) {
            assertEquals("第" + (i + 1) + "章 章节" + (i + 1), result.chapters.get(i).title);
        }
        assertEquals(9, result.buyableCount());
        assertEquals(0, result.freeCount());
        assertTrue(runner.presses.isEmpty());
    }

    @Test
    public void missingMarkersInTheMiddleCannotBeExcusedAsViewportClipping() throws Exception {
        ClippingRunner runner = new ClippingRunner(page(1, 6, VIEWPORT_TOP, 3, 0, false));

        CatalogScanner.Result result = CatalogScanner.scan(runner);

        assertFalse("中间行完整可见却没有状态，整本目录不能用于购买", result.trustworthy());
        assertNotNull(result.gapNote);
        assertEquals("未确认的行不能计为免费章", 0, result.freeCount());
        assertTrue(runner.presses.isEmpty());
    }

    @Test
    public void aTrailingPartialRowThatNeverBecomesCompleteCannotBeAnAcceptedEnd()
            throws Exception {
        ClippingRunner runner = new ClippingRunner(page(1, 7, VIEWPORT_TOP, 0, 0, false));

        CatalogScanner.Result result = CatalogScanner.scan(runner);

        assertFalse("重复静止的半行不能证明目录已经读完整", result.trustworthy());
        assertNotNull(result.gapNote);
        assertEquals("缺少锁信息不能发布为免费章", 0, result.freeCount());
        assertTrue(runner.presses.isEmpty());
    }

    @Test
    public void aPartialRowCannotServeAsTheSecondReliableOverlapAnchor() throws Exception {
        ClippingRunner runner = new ClippingRunner(
                page(1, 7, VIEWPORT_TOP, 0, 0, false),
                page(6, 10, VIEWPORT_TOP, 0, 0, false));

        CatalogScanner.Result result = CatalogScanner.scan(runner);

        assertFalse("仅第6行有完整位移证据，半露的第7行不能充当第二个锚点", result.trustworthy());
        assertNotNull(result.gapNote);
        assertEquals(0, result.freeCount());
        assertTrue(runner.presses.isEmpty());
    }

    @Test
    public void anUnnumberedExtraWithOnlyAClippedCheckboxCannotBecomeFree() {
        // 2026-09-14 番外被正式纳入后，不能保留“printedNo < 0 就是完整行”的旧豁免。
        ChapterRowState checkboxOnly = ChapterRowState.of(false, false, true);
        boolean complete = CatalogScanner.rowStateKnown(false, true, checkboxOnly);
        assertFalse(complete);
        CatalogScanner.Result result = extraFromFacts(checkboxOnly, complete);
        assertFalse(result.trustworthy());
        assertNotNull(result.gapNote);
        assertEquals(0, result.freeCount());
        assertTrue(result.unresolved.contains("迷信的可怖后果"));
    }

    @Test
    public void aCompleteUnnumberedPaidExtraRetainsItsLockAndBuyableState() {
        ChapterRowState paid = ChapterRowState.of(true, false, true);
        CatalogScanner.Result result = extraFromFacts(paid,
                CatalogScanner.rowStateKnown(false, false, paid));
        assertTrue(result.gapNote, result.trustworthy());
        assertEquals(-1, result.chapters.get(0).printedNo);
        assertEquals(1, result.buyableCount());
        assertEquals(0, result.freeCount());
    }

    @Test
    public void onlyACompleteUnnumberedRowCanContributeToTheFreeChapterCount() {
        ChapterRowState free = ChapterRowState.of(false, false, true);
        CatalogScanner.Result result = extraFromFacts(free,
                CatalogScanner.rowStateKnown(false, false, free));
        assertTrue(result.gapNote, result.trustworthy());
        assertEquals(1, result.freeCount());
        ChapterRowState missing = ChapterRowState.of(false, false, false);
        CatalogScanner.Result unread = extraFromFacts(missing,
                CatalogScanner.rowStateKnown(false, false, missing));
        assertFalse(unread.trustworthy());
        assertEquals(0, unread.freeCount());
    }

    @Test
    public void directoryRolesNeedWholeRowsButNeverInventPickerState() {
        assertTrue(CatalogScanner.rowStateKnown(true, false, null));
        assertFalse(CatalogScanner.rowStateKnown(true, true, null));
        CatalogScanner.Result directory = extraDirectory(true);
        assertTrue(directory.trustworthy());
        assertEquals(0, directory.freeCount());
        assertEquals(0, directory.buyableCount());
        assertFalse(extraDirectory(false).trustworthy());
    }

    private static CatalogScanner.Result extraDirectory(boolean complete) {
        CatalogScanner.DirectoryRowEvidence facts = new CatalogScanner.DirectoryRowEvidence(
                "list_view", "ListView", null, "RelativeLayout", true, true, 1, true,
                "title", "TextView", false);
        CatalogScanner.Row row = CatalogScanner.observedRow("迷信的可怖后果", null,
                true, complete, facts);
        return CatalogScanner.resolveRows(Collections.singletonList(row), true, false,
                false, null, null);
    }

    private static CatalogScanner.Result extraFromFacts(ChapterRowState state, boolean complete) {
        CatalogScanner.Row row = CatalogScanner.observedRow("迷信的可怖后果", state,
                true, complete, null);
        return CatalogScanner.resolveRows(Collections.singletonList(row), false, true,
                false, null, extraDirectory(true));
    }

    /**
     * 章节行的全高120；标题在+18..70，锁和状态在+92..112。
     * 第7行从920开始，标题仍可见，两个状态节点都已位于视口1000以下，因此树里不返回它们。
     * clippedRowBounds 同时覆盖无障碍框架把整行bounds也裁到视口边缘的情况。
     */
    private static FakeNode page(int first, int last, int firstTop,
                                 int missingMarkersChapter, int downloadedChapter,
                                 boolean clippedRowBounds) {
        FakeNode root = FakeNode.node().withBounds(0, 0, 1080, 1200);
        FakeNode list = FakeNode.node().withId("com.sfacg:id/downloadRecycler")
                .withClass("androidx.recyclerview.widget.RecyclerView").scrollable(true)
                .withBounds(0, VIEWPORT_TOP, 1080, VIEWPORT_BOTTOM);
        for (int number = first; number <= last; number++) {
            int top = firstTop + (number - first) * ROW_HEIGHT;
            int rowTop = clippedRowBounds ? Math.max(VIEWPORT_TOP, top) : top;
            int rowBottom = clippedRowBounds
                    ? Math.min(VIEWPORT_BOTTOM, top + ROW_HEIGHT) : top + ROW_HEIGHT;
            FakeNode row = FakeNode.node().withClass("android.widget.RelativeLayout")
                    .clickable(true).withBounds(0, rowTop, 1080, rowBottom)
                    .add(FakeNode.text("第" + number + "章 章节" + number)
                            .withId("com.sfacg:id/title")
                            .withBounds(40, Math.max(VIEWPORT_TOP, top + 18),
                                    900, Math.min(VIEWPORT_BOTTOM, top + 70)));
            int markerTop = top + 92;
            int markerBottom = top + 112;
            if (number != missingMarkersChapter
                    && markerTop >= VIEWPORT_TOP && markerBottom <= VIEWPORT_BOTTOM) {
                row.add(FakeNode.node().withId("com.sfacg:id/title_lock")
                        .withBounds(780, markerTop, 820, markerBottom));
                if (number == downloadedChapter) {
                    row.add(FakeNode.text("已下载").withId("com.sfacg:id/title_check")
                            .withBounds(840, markerTop, 960, markerBottom));
                } else {
                    row.add(FakeNode.node().withId("com.sfacg:id/item_cb")
                            .withBounds(960, markerTop, 1000, markerBottom));
                }
            }
            list.add(row);
        }
        return root.add(list);
    }

    /** 圆圈可在标题旁边，而锁在标题下面；前者仍露出时后者可能已经被视口裁掉。 */
    private static FakeNode withVisibleCheckboxAtBottom(FakeNode page) {
        FakeNode trailingRow = (FakeNode) page.child(0).child(6);
        trailingRow.add(FakeNode.node().withId("com.sfacg:id/item_cb")
                .withBounds(960, 960, 1000, 990));
        return page;
    }

    /** 手势完成与容器拒绝滚动分开提供；二者不是同一份到底证据。 */
    private static final class ClippingRunner extends OfflineRunner {
        final NodeView[] pages;
        int page;
        int scrollCalls;

        ClippingRunner(NodeView... pages) throws Exception {
            this.pages = pages;
            active = pages[0];
        }

        @Override public boolean scrollCatalogForward() throws StepFailure {
            checkCancelled();
            scrollCalls++;
            if (page + 1 < pages.length) active = pages[++page];
            return true;
        }

        @Override public boolean scrollForward() {
            throw new AssertionError("目录扫描应使用短滑动");
        }

        @Override public CatalogScroll probeCatalogContainerForward() {
            return page + 1 >= pages.length ? CatalogScroll.BLOCKED : CatalogScroll.ACCEPTED;
        }
    }
}
