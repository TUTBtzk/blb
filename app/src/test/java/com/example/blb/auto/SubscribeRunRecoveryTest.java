package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.blb.data.Chapter;
import com.example.blb.data.Novel;
import com.example.blb.data.Purchase;
import com.example.blb.data.PurchaseRow;
import com.example.blb.data.SubscriptionDao;

import org.junit.Test;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 目录独立后仍须保住原入口的扫描护栏；七笔明细只交给纯判据，不伪造完整读屏或恢复付费。 */
public class SubscribeRunRecoveryTest {
    private static final long NOVEL_ID = 42;
    private static final long ACCOUNT_ID = 7;
    private static final String BOOK = "反派干部今天也在扮魔法少女";
    private static final int[] REMOTE_POSITIONS = {83, 99, 65, 92, 91, 82, 71};
    private static final int[] AMOUNTS = {11, 10, 12, 12, 11, 12, 10};
    private static final String[] DESCRIPTIONS = {
            "铃兰花 第83章 最后", "星彩 第6章 薯片", "铃兰花 第65章 薄暮",
            "铃兰花 第92章 初次见面", "铃兰花 第91章 花叶", "铃兰花 第81章 沙滩",
            "铃兰花 第71章 前夜"
    };
    private static final String[] DATES = {
            "2026-09-12", "2026-09-08", "2026-09-08", "2026-09-08",
            "2026-04-05", "2026-04-03", "2026-03-30"
    };

    @Test
    public void emptyCatalogIsScannedWithoutReadingTheLedgerOrRestoringPaidRows() throws Exception {
        for (boolean downloaded : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            f.runner.downloadedHistory = downloaded;

            CatalogSync.Report report = f.sync();

            assertTrue(report.message, report.ok);
            assertEquals(99, report.scanned);
            assertEquals(99, report.added);
            assertEquals(92, report.backfilled);
            assertEquals(downloaded ? 7 : 0, report.foreign.size());
            assertEquals(99, f.db.chapters.size());
            assertEquals(92, f.db.purchases.size());
            assertTrue(f.db.paidRows().isEmpty());
            assertTrue(f.db.purchases.values().stream().allMatch(p ->
                    Purchase.SRC_OWNED.equals(p.source) && p.costCoupons == 0 && p.costVouchers == 0));
            for (int position : REMOTE_POSITIONS) {
                assertNull("付费章不能因已下载就被补成归属",
                        f.db.purchase(f.db.dao.chapterByNo(NOVEL_ID, position).id));
            }
            Chapter chips = f.db.dao.chapterByNo(NOVEL_ID, 99);
            assertEquals("第6章 薯片", chips.title);
            assertNull(f.db.purchase(chips.id));
            assertRecoveryPlan(f, 7);
            assertNoLedgerReadOrPayment(f);
        }
    }

    @Test
    public void overlappingPagesPreserveBothChapter81RowsAndTheirRemoteMapping()
            throws Exception {
        Fixture f = new Fixture();
        f.runner.paginatedCatalog = true;

        CatalogSync.Report report = f.sync();

        assertTrue(report.message, report.ok);
        assertEquals(99, f.db.chapters.size());
        assertTrue("普通目录和选择章节页都应翻过包含重叠行的多页列表",
                f.runner.catalogForwardMoves >= 16);
        assertTrue(f.events.contains("catalog-page:73-82"));
        assertTrue(f.events.contains("catalog-page:81-94"));
        Chapter heart = f.db.dao.chapterByNo(NOVEL_ID, 81);
        Chapter beach = f.db.dao.chapterByNo(NOVEL_ID, 82);
        Chapter last = f.db.dao.chapterByNo(NOVEL_ID, 83);
        assertEquals("第81章 心脏", heart.title);
        assertEquals("第81章 沙滩", beach.title);
        assertEquals("第83章 最后", last.title);
        assertTrue("两个第81章是两条不同章节，不能按作者标号合并", heart.id != beach.id);
        assertEquals("第81章心脏不属于这七笔远端付费", 0, f.db.purchase(heart.id).costVouchers);
        assertNull(f.db.purchase(beach.id));
        assertNull(f.db.purchase(last.id));
        assertEquals("第68 投影", f.db.dao.chapterByNo(NOVEL_ID, 68).title);
        assertRecoveryPlan(f, 7);
        assertNoLedgerReadOrPayment(f);
    }

    @Test
    public void shortOldCatalogIsExtendedAndItsExistingIdsArePreserved() throws Exception {
        Fixture f = new Fixture();
        List<Long> oldIds = new ArrayList<>();
        for (int no = 1; no <= 40; no++) {
            Chapter chapter = f.db.dao.ensureChapter(NOVEL_ID, no, "卷一章节" + no, 0);
            oldIds.add(chapter.id);
        }

        CatalogSync.Report report = f.sync();

        assertTrue(report.message, report.ok);
        assertEquals(59, report.added);
        assertEquals(99, f.db.chapters.size());
        for (int no = 1; no <= 40; no++) {
            Chapter chapter = f.db.dao.chapterByNo(NOVEL_ID, no);
            assertEquals(oldIds.get(no - 1).longValue(), chapter.id);
            assertEquals("第" + no + "章 卷一章节" + no, chapter.title);
        }
        assertRecoveryPlan(f, 7);
        assertNoLedgerReadOrPayment(f);
    }

    @Test
    public void realignmentPreservesThePaidFactAndLeavesSixTransactionsForTheAudit() throws Exception {
        Fixture f = new Fixture();
        f.runner.downloadedHistory = true;
        Chapter old = f.db.dao.ensureChapter(NOVEL_ID, 1, "65 薄暮", 0);
        Purchase paid = Purchase.of(ACCOUNT_ID, old.id, 0, 12, Purchase.SRC_AUTO);
        paid.purchasedAt = RemoteLedgerRecovery.date(DATES[2]);
        long purchaseId = f.db.dao.upsertPurchase(paid);

        CatalogSync.Report report = f.sync();

        assertTrue(report.message, report.ok);
        assertEquals(1, report.realignedCount);
        assertEquals(1, f.db.paidRows().size());
        assertEquals(old.id, f.db.dao.chapterByNo(NOVEL_ID, 65).id);
        assertEquals(purchaseId, f.db.purchase(old.id).id);
        assertEquals(Purchase.SRC_AUTO, f.db.purchase(old.id).source);
        assertEquals(12, f.db.purchase(old.id).costVouchers);
        assertEquals(paid.purchasedAt, f.db.purchase(old.id).purchasedAt);
        // 默认“从头开始”保持第 1 章，不能因为旧记录重排而跳过前 64 章。
        assertEquals(1, f.novel.startFrom());
        assertRecoveryPlan(f, 6);
        assertNoLedgerReadOrPayment(f);
    }

    @Test
    public void repeatingCatalogSyncKeepsChapterAndFreeOwnershipIds() throws Exception {
        Fixture f = new Fixture();
        f.runner.paginatedCatalog = true;
        CatalogSync.Report first = f.sync();
        assertTrue(first.message, first.ok);
        Map<Integer, Long> chapterIds = f.db.chapters.values().stream().collect(
                Collectors.toMap(chapter -> chapter.chapterNo, chapter -> chapter.id));
        Map<Long, Long> ownedIds = f.db.purchases.values().stream().collect(
                Collectors.toMap(purchase -> purchase.chapterId, purchase -> purchase.id));

        CatalogSync.Report second = f.sync();

        assertTrue(second.message, second.ok);
        assertEquals(0, second.added);
        assertEquals(0, second.backfilled);
        assertEquals(chapterIds, f.db.chapters.values().stream().collect(
                Collectors.toMap(chapter -> chapter.chapterNo, chapter -> chapter.id)));
        assertEquals(ownedIds, f.db.purchases.values().stream().collect(
                Collectors.toMap(purchase -> purchase.chapterId, purchase -> purchase.id)));
        assertTrue(f.db.paidRows().isEmpty());
        assertNoLedgerReadOrPayment(f);
    }

    @Test
    public void anIncompleteCatalogDoesNotWriteHistoryOrReachPayment() throws Exception {
        Fixture f = new Fixture();
        f.runner.catalogGap = true;

        CatalogSync.Report report = f.sync();
        assertFalse(report.message, report.ok);
        assertTrue(report.message, report.message.contains("目录没扫干净"));
        assertScanLeftEmpty(f);
    }

    @Test
    public void catalogTimeoutIsPropagatedBeforeAnyWriteOrPayment() throws Exception {
        Fixture f = new Fixture();
        f.runner.catalogFailure = new StepRunner.StepFailure(
                StepRunner.Kind.TIMEOUT, "目录翻页超时");

        try {
            f.sync();
            fail("目录翻页超时不能被当作扫完");
        } catch (StepRunner.StepFailure failure) {
            assertEquals(StepRunner.Kind.TIMEOUT, failure.kind);
            assertSame(f.runner.catalogFailure, failure);
        }
        assertScanLeftEmpty(f);
    }

    @Test
    public void catalogRuntimeFailureIsPropagatedBeforeAnyWriteOrPayment() throws Exception {
        Fixture f = new Fixture();
        f.runner.catalogRuntimeFailure = new IllegalStateException("目录节点读取失败");

        try {
            f.sync();
            fail("目录节点异常不能被当作扫完");
        } catch (IllegalStateException failure) {
            assertSame(f.runner.catalogRuntimeFailure, failure);
        }
        assertScanLeftEmpty(f);
    }

    private static void assertScanLeftEmpty(Fixture f) {
        assertTrue(f.db.chapters.isEmpty());
        assertTrue(f.db.purchases.isEmpty());
        assertNoLedgerReadOrPayment(f);
    }

    @Test
    public void ambiguousScannedTitlesStillCannotProduceARecoveryPlan() throws Exception {
        Fixture f = new Fixture();
        f.runner.ambiguousLast = true;

        CatalogSync.Report report = f.sync();
        assertTrue(report.message, report.ok);
        RemoteLedgerRecovery.Plan plan = f.recoveryPlan();
        assertFalse(plan.message, plan.ok);
        assertTrue(plan.message, plan.message.contains("不能唯一对应"));
        assertTrue(plan.purchases.isEmpty());
        assertFalse(f.db.chapters.isEmpty());
        assertTrue(f.db.paidRows().isEmpty());
        assertNoLedgerReadOrPayment(f);
    }

    @Test
    public void knownDownloadedPaidChaptersKeepTheirFactsWithoutAnUnknownOwnerWarning() throws Exception {
        Fixture f = new Fixture();
        f.runner.downloadedHistory = true;
        List<Purchase> before = new ArrayList<>();
        for (int i = 0; i < REMOTE_POSITIONS.length; i++) {
            int position = REMOTE_POSITIONS[i];
            Chapter chapter = f.db.dao.ensureChapter(NOVEL_ID, position,
                    f.runner.catalogTitles().get(position - 1), 0);
            Purchase paid = Purchase.of(ACCOUNT_ID, chapter.id, 0, AMOUNTS[i], Purchase.SRC_AUTO);
            paid.purchasedAt = RemoteLedgerRecovery.date(DATES[i]);
            paid.id = f.db.dao.upsertPurchase(paid);
            before.add(paid);
        }

        CatalogSync.Report report = f.sync();

        assertTrue(report.message, report.ok);
        assertTrue(report.foreign.toString(), report.foreign.isEmpty());
        assertTrue(report.contradictions.toString(), report.contradictions.isEmpty());
        assertEquals(7, f.db.paidRows().size());
        for (Purchase original : before) {
            Purchase after = f.db.purchase(original.chapterId);
            assertEquals(original.id, after.id);
            assertEquals(original.costCoupons, after.costCoupons);
            assertEquals(original.costVouchers, after.costVouchers);
            assertEquals(original.purchasedAt, after.purchasedAt);
            assertEquals(original.source, after.source);
        }
        assertNoLedgerReadOrPayment(f);
    }

    private static void assertRecoveryPlan(Fixture f, int missing) {
        RemoteLedgerRecovery.Plan plan = f.recoveryPlan();
        assertTrue(plan.message, plan.ok);
        assertEquals(missing, plan.purchases.size());
        assertEquals(78, plan.purchases.stream().mapToInt(row -> row.costVouchers).sum()
                + f.db.paidRows().stream().mapToInt(row -> row.costVouchers).sum());
        assertTrue(plan.purchases.stream().allMatch(row ->
                row.accountId == ACCOUNT_ID && row.costCoupons == 0
                        && Purchase.SRC_REMOTE_DETAIL.equals(row.source)));
        assertEquals(Arrays.stream(REMOTE_POSITIONS).boxed().sorted().collect(Collectors.toList()),
                plan.resolved.stream().map(row -> row.chapter.chapterNo)
                        .sorted().collect(Collectors.toList()));
        for (int i = 0; i < REMOTE_POSITIONS.length; i++) {
            long chapterId = f.db.dao.chapterByNo(NOVEL_ID, REMOTE_POSITIONS[i]).id;
            Purchase planned = plan.purchases.stream().filter(row -> row.chapterId == chapterId)
                    .findFirst().orElse(null);
            Purchase fact = planned == null ? f.db.purchase(chapterId) : planned;
            assertEquals(AMOUNTS[i], fact.costVouchers);
            assertEquals(RemoteLedgerRecovery.date(DATES[i]), fact.purchasedAt);
        }
        assertEquals("默认从头开始不能因作者重复标号或目录重排而改变", 1, f.novel.startFrom());
    }

    private static void assertNoLedgerReadOrPayment(Fixture f) {
        assertFalse(f.events.contains("ledger"));
        assertEquals(0, f.runner.paymentClicks);
        assertEquals(0, f.runner.chapterSelections);
    }

    private static final class Fixture {
        final List<String> events = new ArrayList<>();
        final MemoryLedger db = new MemoryLedger();
        final RecoveryRunner runner;
        final Novel novel = new Novel();

        Fixture() throws Exception {
            runner = new RecoveryRunner(events);
            novel.id = NOVEL_ID;
            novel.title = BOOK;
        }

        CatalogSync.Report sync() throws StepRunner.StepFailure {
            return CatalogSync.sync(runner, db.dao, novel, ACCOUNT_ID);
        }

        RemoteLedgerRecovery.Plan recoveryPlan() {
            List<SubscribedDetail.Entry> entries = new ArrayList<>();
            for (int i = 0; i < DESCRIPTIONS.length; i++) {
                entries.add(SubscribedDetail.parseRow(DESCRIPTIONS[i],
                        String.valueOf(AMOUNTS[i]), "代券", DATES[i]));
            }
            return RemoteLedgerRecovery.plan("测试账号", BOOK, ACCOUNT_ID, 7,
                    entries, db.dao.loadChapters(NOVEL_ID), db.paidRows());
        }
    }

    /**
     * 2026-09-14 两页目录核验需要保留导航状态；两页节点形状按用户 dump 适配，
     * 99 章标题、分页和坐标仍是原合成数据。独立扫描不得进入订阅清单或逐章购买。
     */
    private static final class RecoveryRunner extends OfflineRunner {
        private static final int[][] CATALOG_PAGES = {
                {1, 14}, {13, 26}, {25, 38}, {37, 50}, {49, 62}, {61, 74},
                {73, 82}, {81, 94}, {93, 99}
        };
        final List<String> events;
        String page = "home";
        boolean catalogGap;
        StepFailure catalogFailure;
        RuntimeException catalogRuntimeFailure;
        boolean ambiguousLast;
        boolean downloadedHistory;
        boolean paginatedCatalog;
        int catalogPage;
        int catalogForwardMoves;
        int paymentClicks;
        int chapterSelections;

        RecoveryRunner(List<String> events) throws Exception {
            this.events = events;
        }

        @Override public void launchTarget(long timeoutMs) throws StepFailure {
            super.launchTarget(timeoutMs);
            if ("home".equals(page)) showCatalog("directory");
        }

        @Override public void ensureHome(int maxBacks) throws StepFailure {
            checkCancelled();
            page = "home";
            active = FakeNode.node().add(FakeNode.node().withId("main_tab_container5"));
        }

        @Override public void back() throws StepFailure {
            super.back();
            if ("picker".equals(page)) showCatalog("directory");
            else if ("directory".equals(page)) ensureHome(1);
        }

        @Override public void clickNode(String label, NodeView node) throws StepFailure {
            super.clickNode(label, node);
            if (Keys.SUBSCRIBE_BUTTON.equals(label) || Keys.SUBSCRIBE_CONFIRM.equals(label)) {
                paymentClicks++;
                throw new AssertionError("同步目录绝不能付款");
            }
            NodeView parent = node == null ? null : node.parent();
            if ("catalog-row".equals(node.viewId()) || (parent != null
                    && ("list_view".equals(parent.viewId())
                    || "downloadRecycler".equals(parent.viewId())))) {
                chapterSelections++;
                throw new AssertionError("同步目录绝不能勾选购买章节");
            }
            if (Keys.MINE_TAB.equals(label) || Keys.VOUCHER_ENTRY.equals(label)
                    || Keys.SUBSCRIBED_LIST_ENTRY.equals(label)) {
                events.add("ledger");
                throw new AssertionError("同步目录不应读取订阅清单");
            } else if (Keys.DOWNLOAD_ENTRY.equals(label)
                    || "download_layout".equals(node.viewId())) {
                showCatalog("picker");
            } else if ("回到顶部".equals(label) || "goto_top".equals(node.viewId())) {
                catalogPage = 0;
                showCatalogPage();
            }
        }

        @Override public boolean scrollCatalogForward() throws StepFailure {
            scrollForward();
            return true; // 合成手势已完成；是否到底由独立容器探测回答。
        }

        @Override public CatalogScroll probeCatalogContainerForward() throws StepFailure {
            checkCancelled();
            if (!isCatalogPage()) return CatalogScroll.UNAVAILABLE;
            return scrollForward() ? CatalogScroll.ACCEPTED : CatalogScroll.BLOCKED;
        }

        @Override public CatalogScroll probeCatalogContainerBackward() throws StepFailure {
            checkCancelled();
            if (!isCatalogPage()) return CatalogScroll.UNAVAILABLE;
            return scrollBackward() ? CatalogScroll.ACCEPTED : CatalogScroll.BLOCKED;
        }

        @Override public boolean scrollBackward() throws StepFailure {
            checkCancelled();
            if (!isCatalogPage() || !paginatedCatalog || catalogPage <= 0) return false;
            catalogPage--;
            showCatalogPage();
            return true;
        }

        @Override public boolean scrollForward() throws StepFailure {
            checkCancelled();
            if (isCatalogPage()) {
                if (catalogFailure != null) throw catalogFailure;
                if (catalogRuntimeFailure != null) throw catalogRuntimeFailure;
                if (paginatedCatalog && catalogPage + 1 < CATALOG_PAGES.length) {
                    catalogPage++;
                    catalogForwardMoves++;
                    showCatalogPage();
                    return true;
                }
                events.add("catalog-complete");
                // 2026-09-14 到底判据要求内容不变且确实滚不动；替身明确返回不能再滚。
                return false;
            }
            return false;
        }

        private boolean isCatalogPage() {
            return "directory".equals(page) || "picker".equals(page);
        }

        private void showCatalog(String page) {
            this.page = page;
            catalogPage = 0;
            showCatalogPage();
        }

        private void showCatalogPage() {
            boolean directory = "directory".equals(page);
            List<String> titles = catalogTitles();
            int first = paginatedCatalog ? CATALOG_PAGES[catalogPage][0] : 1;
            int last = paginatedCatalog && catalogPage + 1 < CATALOG_PAGES.length
                    ? CATALOG_PAGES[catalogPage][1] : titles.size();
            int bottom = 140 + (last - first + 1) * 60;
            FakeNode root = FakeNode.node().withClass("android.widget.FrameLayout")
                    .withBounds(0, 0, 1080, bottom).add(
                            FakeNode.node().withClass("android.widget.ImageView").withId("goto_top")
                                    .clickable(true).withBounds(1000, 20, 1060, 80));
            if (directory) {
                root.add(FakeNode.text("目录列表").withClass("android.widget.TextView")
                                .withId("edit_title").withBounds(20, 20, 600, 80),
                        FakeNode.node().withClass("android.widget.RelativeLayout")
                                .withId("download_layout").clickable(true)
                                .withBounds(820, 20, 980, 80)
                                .add(FakeNode.text("下载").withClass("android.widget.TextView")
                                        .withBounds(840, 30, 960, 70)));
            } else {
                root.add(FakeNode.text("已选0章").withClass("android.widget.TextView")
                        .withId("tvSelect").withBounds(20, 20, 600, 80));
            }
            FakeNode list = FakeNode.node()
                    .withClass(directory ? "android.widget.ListView" : "androidx.recyclerview.widget.RecyclerView")
                    .withId(directory ? "list_view" : "downloadRecycler")
                    .scrollable(true).withBounds(0, 100, 1080, bottom);
            root.add(list);
            for (int position = first; position <= last; position++) {
                // 真正漏掉第66行：仍保留它的整行高度，65与67不再是画面上相邻的两行。
                if (catalogGap && position == 66) continue;
                boolean remote = false;
                for (int remotePosition : REMOTE_POSITIONS) {
                    if (remotePosition == position) remote = true;
                }
                boolean paid = remote || (ambiguousLast && position == titles.size());
                list.add(directory ? directoryRow(titles.get(position - 1), position - first)
                        : catalogRow(titles.get(position - 1), position - first, paid,
                        remote && downloadedHistory));
            }
            active = root;
            events.add((directory ? "directory-page:" : "catalog-page:") + first + "-" + last);
        }

        private List<String> catalogTitles() {
            List<String> titles = new ArrayList<>();
            // 作者号按截图保留：两个第81章之后是第83章；本地编号按全书实际行位置。
            for (int position = 1; position <= 99; position++) {
                int printed = position <= 93 ? position : position - 93;
                String title = position <= 93 ? "卷一章节" + position : "卷二章节" + printed;
                if (position == 65) title = "薄暮";
                if (position == 68) title = "投影";
                if (position == 71) title = "前夜";
                if (position == 81) title = "心脏";
                if (position == 82) {
                    printed = 81;
                    title = "沙滩";
                }
                if (position == 83) title = "最后";
                if (position == 91) title = "花叶";
                if (position == 92) title = "初次见面";
                if (position == 99) title = "薯片";
                titles.add("第" + printed + (position == 68 ? " " : "章 ") + title);
            }
            if (ambiguousLast) {
                for (int no = 1; no <= 83; no++) {
                    // 保持原始标题各行唯一，但83号和正文仍与第一卷那一行同义，恢复必须拒绝。
                    titles.add(no == 83 ? "第83 章 最后" : "第" + no + "章 卷三章节" + no);
                }
            }
            return titles;
        }

        private FakeNode catalogRow(String title, int screenIndex, boolean paid, boolean downloaded) {
            int top = 100 + screenIndex * 60;
            FakeNode row = FakeNode.node().withClass("android.widget.RelativeLayout")
                    .withId("catalog-row").clickable(true)
                    .withBounds(0, top, 1080, top + 60)
                    .add(FakeNode.text(title).withClass("android.widget.TextView")
                            .withId("title").withBounds(20, top + 8, 800, top + 52));
            if (paid) row.add(FakeNode.node().withId("title_lock")
                    .withBounds(840, top + 20, 864, top + 44));
            row.add(FakeNode.node().withId(downloaded ? "title_check" : "item_cb")
                    .withBounds(920, top + 16, 1040, top + 48));
            return row;
        }

        private FakeNode directoryRow(String title, int screenIndex) {
            int top = 100 + screenIndex * 60;
            return FakeNode.node().withClass("android.widget.RelativeLayout").clickable(true)
                    .withBounds(0, top, 1080, top + 60)
                    .add(FakeNode.text(title).withClass("android.widget.TextView")
                            .withId("title").withBounds(0, top + 8, 1080, top + 52));
        }
    }

    /** 沿用原存取替身验证目录重排；恢复事务由已有设备 DAO 测试覆盖，这里不再代演。 */
    private static final class MemoryLedger implements InvocationHandler {
        final Map<Long, Chapter> chapters = new LinkedHashMap<>();
        final Map<Long, Purchase> purchases = new LinkedHashMap<>();
        final SubscriptionDao dao;
        long nextChapterId = 1000;
        long nextPurchaseId = 2000;

        MemoryLedger() {
            dao = (SubscriptionDao) Proxy.newProxyInstance(SubscriptionDao.class.getClassLoader(),
                    new Class<?>[]{SubscriptionDao.class}, this);
        }

        @Override public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            Object[] args = arguments == null ? new Object[0] : arguments;
            if (method.isDefault()) {
                if ("restoreRemotePurchases".equals(method.getName())
                        || "insertRemotePurchase".equals(method.getName())) {
                    throw new AssertionError("同步目录不能提交远端付费补账");
                }
                return MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup())
                        .unreflectSpecial(method, method.getDeclaringClass()).bindTo(proxy)
                        .invokeWithArguments(args);
            }
            switch (method.getName()) {
                case "loadChapters":
                    return catalog();
                case "chapterById":
                    return copy(chapters.get((Long) args[0]));
                case "chapterByNo":
                    return catalog().stream().filter(c -> c.chapterNo == (Integer) args[1])
                            .findFirst().orElse(null);
                case "insertChapter": {
                    Chapter c = copy((Chapter) args[0]);
                    if (chapters.values().stream().anyMatch(old -> old.chapterNo == c.chapterNo)) return -1L;
                    c.id = nextChapterId++;
                    chapters.put(c.id, c);
                    return c.id;
                }
                case "updateChapter": {
                    Chapter c = copy((Chapter) args[0]);
                    chapters.put(c.id, c);
                    return null;
                }
                case "setChapterNo":
                    chapters.get((Long) args[0]).chapterNo = (Integer) args[1];
                    return null;
                case "deleteEmptyChapterById":
                    assertTrue("已购买的章节不能在重排时删除", purchases.values().stream()
                            .noneMatch(p -> p.chapterId == (Long) args[0]));
                    chapters.remove((Long) args[0]);
                    return null;
                case "setStartChapter":
                    return null; // CatalogSync 同时更新本轮 Novel 对象。
                case "realPurchasedChapterIds":
                    return purchases.values().stream().map(p -> p.chapterId).distinct()
                            .collect(Collectors.toList());
                case "countRealPurchase":
                    return (int) purchases.values().stream().filter(p -> p.accountId == (Long) args[0]
                            && p.chapterId == (Long) args[1]).count();
                case "loadPaidRowsOfNovel":
                    return paidRows();
                case "loadPurchasesOfNovel":
                    return purchases.values().stream().map(MemoryLedger::copy).collect(Collectors.toList());
                case "loadPurchasesForChapters": {
                    List<?> ids = (List<?>) args[0];
                    return purchases.values().stream().filter(p -> ids.contains(p.chapterId))
                            .map(MemoryLedger::copy).collect(Collectors.toList());
                }
                case "insertPurchaseOrAbort":
                    return savePurchase((Purchase) args[0]);
                case "toString":
                    return "memory subscription ledger";
                default:
                    throw new AssertionError("Unexpected DAO call: " + method.getName());
            }
        }

        private List<Chapter> catalog() {
            return chapters.values().stream().map(MemoryLedger::copy)
                    .sorted(Comparator.comparingInt(c -> c.chapterNo)).collect(Collectors.toList());
        }

        private long savePurchase(Purchase incoming) {
            Purchase old = purchase(incoming.chapterId);
            if (old != null) throw new AssertionError("重复插入同一购买事实");
            Purchase stored = copy(incoming);
            stored.id = nextPurchaseId++;
            purchases.put(stored.id, stored);
            return stored.id;
        }

        Purchase purchase(long chapterId) {
            return purchases.values().stream().filter(p -> p.accountId == ACCOUNT_ID
                    && p.chapterId == chapterId).findFirst().orElse(null);
        }

        List<PurchaseRow> paidRows() {
            List<PurchaseRow> rows = new ArrayList<>();
            for (Purchase p : purchases.values()) {
                if (p.costCoupons <= 0 && p.costVouchers <= 0) continue;
                Chapter chapter = Objects.requireNonNull(chapters.get(p.chapterId));
                PurchaseRow row = new PurchaseRow();
                row.purchaseId = p.id;
                row.accountId = p.accountId;
                row.chapterId = p.chapterId;
                row.costCoupons = p.costCoupons;
                row.costVouchers = p.costVouchers;
                row.purchasedAt = p.purchasedAt;
                row.source = p.source;
                row.chapterNo = chapter.chapterNo;
                row.chapterTitle = chapter.title;
                row.novelTitle = BOOK;
                row.accountNickname = "测试账号";
                rows.add(row);
            }
            rows.sort(Comparator.comparingInt(row -> row.chapterNo));
            return rows;
        }

        private static Chapter copy(Chapter source) {
            if (source == null) return null;
            Chapter copy = new Chapter();
            copy.id = source.id;
            copy.novelId = source.novelId;
            copy.chapterNo = source.chapterNo;
            copy.title = source.title;
            copy.volumeTitle = source.volumeTitle;
            copy.sfChapterId = source.sfChapterId;
            copy.priceCoupons = source.priceCoupons;
            return copy;
        }

        private static Purchase copy(Purchase source) {
            Purchase copy = new Purchase();
            copy.id = source.id;
            copy.accountId = source.accountId;
            copy.chapterId = source.chapterId;
            copy.costCoupons = source.costCoupons;
            copy.costVouchers = source.costVouchers;
            copy.purchasedAt = source.purchasedAt;
            copy.source = source.source;
            return copy;
        }
    }
}
