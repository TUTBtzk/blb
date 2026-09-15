package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.blb.data.Account;
import com.example.blb.data.Chapter;
import com.example.blb.data.Novel;
import com.example.blb.data.Purchase;
import com.example.blb.data.SubscriptionDao;
import com.example.blb.util.Texts;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 在纯 JVM 上执行真实购买决策，不发送任何界面点击。 */
public class SubscribeTaskTest {

    private static final long CATALOG_NOVEL_ID = 42;

    @Test public void unnumberedExtraUsesTheFullUniqueTitleWithoutInventingANumber() throws Exception {
        String title = "藏在地下室的恶鬼（上）";
        Chapter chapter = catalogChapter(612, 612, title);
        SubscribeTask.requireUniqueChapter(catalogNovel(), chapter, Arrays.asList(chapter));
        assertEquals(title, SubscribeTask.exactLookupTitle(chapter.title));
        assertEquals("藏在地下室的恶鬼 (下)", SubscribeTask.exactLookupTitle("  藏在地下室的恶鬼 (下)  "));
        assertEquals(-1, Texts.rowChapterNo(chapter.title));
    }

    @Test public void theSameUnnumberedTitleInTwoVolumesStillStopsBeforePurchase() throws Exception {
        Chapter first = catalogChapter(612, 612, "藏在地下室的恶鬼（上）");
        Chapter second = catalogChapter(613, 613, first.title);
        first.volumeTitle = "番外";
        second.volumeTitle = "另一卷";
        try {
            SubscribeTask.requireUniqueChapter(catalogNovel(), second, Arrays.asList(first, second));
            fail("全标题不唯一时不能靠卷名放松购买行定位");
        } catch (StepRunner.StepFailure expected) {
            assertEquals(StepRunner.Kind.MONEY_UNCLEAR, expected.kind);
        }
    }

    /** 只显示当前屏的一行；即使另一个同名行不在屏内，全本目录门槛也必须拦住。 */
    private static final class CatalogRunner extends OfflineRunner {
        int launches;

        CatalogRunner(String title) throws Exception {
            active = FakeNode.node().add(
                    FakeNode.text("已选 0 章").withId("com.sfacg:id/tvSelect"),
                    FakeNode.node().clickable(true).add(
                            FakeNode.text(title).withId("com.sfacg:id/title")));
        }

        @Override public void launchTarget(long timeoutMs) throws StepFailure {
            checkCancelled();
            launches++;
        }
    }

    private static Novel catalogNovel() {
        Novel novel = new Novel();
        novel.id = CATALOG_NOVEL_ID;
        novel.title = "测试小说";
        return novel;
    }

    private static Chapter catalogChapter(long id, int position, String title) {
        Chapter chapter = new Chapter();
        chapter.id = id;
        chapter.novelId = CATALOG_NOVEL_ID;
        chapter.chapterNo = position;
        chapter.title = title;
        return chapter;
    }

    private static String assertCatalogBlocked(CatalogRunner runner, Chapter target,
                                               List<Chapter> catalog) throws Exception {
        try {
            SubscribeTask.run(runner, catalogNovel(), target, -1, catalog);
            fail("无法唯一定位章节时不得进入购买界面");
            return null;
        } catch (StepRunner.StepFailure expected) {
            assertTrue("所有账号都必须停止", SubscribeQueue.isGlobal(expected.kind));
            assertTrue(expected.getMessage(), expected.getMessage().contains("未勾选章节"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("未点击付款"));
            assertFalse(expected.getMessage(), expected.getMessage().contains("券可能已经扣了"));
            assertEquals(0, runner.launches);
            assertTrue("连章节勾选都不能发生", runner.presses.isEmpty());
            return expected.getMessage();
        }
    }

    @Test
    public void sameRawTitleInAnotherVolumeBlocksBeforeAnyUiAction() throws Exception {
        Chapter earlier = catalogChapter(1, 1, "第1章 归来");
        Chapter target = catalogChapter(80, 80, "第1章 归来");
        CatalogRunner runner = new CatalogRunner(earlier.title);

        String message = assertCatalogBlocked(runner, target, Arrays.asList(earlier, target));

        assertTrue(message, message.contains("2 行"));
        assertTrue(message, message.contains("完整标题都是「第1章 归来」"));
    }

    @Test
    public void titleUniquenessUsesTheSameTrimmingAsExactUiMatching() throws Exception {
        Chapter earlier = catalogChapter(1, 1, "  第1章 归来  ");
        Chapter target = catalogChapter(80, 80, "第1章 归来");

        assertCatalogBlocked(new CatalogRunner(earlier.title), target,
                Arrays.asList(earlier, target));
    }

    @Test
    public void differentRawTitlesWithTheSamePrintedNumberRemainReachable() throws Exception {
        Chapter heart = catalogChapter(81, 81, "第81章 心脏");
        Chapter beach = catalogChapter(82, 82, "第81章 沙滩");
        CatalogRunner runner = new CatalogRunner(beach.title);

        SubscribeTask.Result result = SubscribeTask.run(runner, catalogNovel(), beach, -1,
                Arrays.asList(heart, beach));

        assertEquals(SubscribeTask.Status.ALREADY, result.status);
        assertEquals(1, runner.launches);
        assertTrue(runner.presses.isEmpty());
    }

    @Test
    public void theSameRawTitleInAnotherBookDoesNotCauseFalseAmbiguity() throws Exception {
        Chapter target = catalogChapter(1, 1, "第1章 归来");
        Chapter anotherBook = catalogChapter(2, 1, target.title);
        anotherBook.novelId = CATALOG_NOVEL_ID + 1;
        CatalogRunner runner = new CatalogRunner(target.title);

        SubscribeTask.Result result = SubscribeTask.run(runner, catalogNovel(), target, -1,
                Arrays.asList(target, anotherBook));

        assertEquals(SubscribeTask.Status.ALREADY, result.status);
    }

    @Test
    public void aMissingCatalogCannotAuthorizeTitleOnlyPurchase() throws Exception {
        Chapter target = catalogChapter(1, 1, "第1章 归来");
        CatalogRunner runner = new CatalogRunner(target.title);

        assertCatalogBlocked(runner, target, null);
        try {
            SubscribeTask.run(runner, catalogNovel(), target);
            fail("旧入口缺少整本目录也必须停止");
        } catch (StepRunner.StepFailure expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("缺少同步后的完整目录"));
        }
        assertEquals(0, runner.launches);
        assertTrue(runner.presses.isEmpty());
    }

    @Test
    public void aUniqueTitleWithADifferentChapterIdOrPositionIsRejected() throws Exception {
        Chapter target = catalogChapter(1, 1, "第1章 归来");
        Chapter replaced = catalogChapter(2, 1, target.title);
        assertCatalogBlocked(new CatalogRunner(target.title), target, Arrays.asList(replaced));

        Chapter moved = catalogChapter(1, 2, target.title);
        assertCatalogBlocked(new CatalogRunner(target.title), target, Arrays.asList(moved));
    }

    private static final class Session implements SubscribeTask.PurchaseSession {
        boolean cancelled;
        boolean cancelOnSubmit;
        boolean cancelOnConfirm;
        boolean cancelWhileFindingConfirmation;
        boolean showConfirmation;
        StepRunner.Kind guardFailure;
        int submits;
        int confirmations;
        int guardCalls;
        int reads;
        long clock;
        final List<SubscribeTask.PurchaseObservation> observations = new ArrayList<>();

        Session(SubscribeTask.PurchaseObservation... observations) {
            this.observations.addAll(Arrays.asList(observations));
        }

        @Override public void checkCancelled() throws StepRunner.StepFailure {
            if (cancelled) throw new StepRunner.StepFailure(StepRunner.Kind.CANCELLED, "已取消");
        }
        @Override public void submit() throws StepRunner.StepFailure {
            checkCancelled();
            submits++;
            if (cancelOnSubmit) cancelled = true;
        }
        @Override public NodeView confirmation() {
            if (cancelWhileFindingConfirmation) cancelled = true;
            return showConfirmation ? FakeNode.text("确认购买") : null;
        }
        @Override public void confirm(NodeView node) throws StepRunner.StepFailure {
            checkCancelled();
            confirmations++;
            if (cancelOnConfirm) cancelled = true;
        }
        @Override public void guardCaptcha() throws StepRunner.StepFailure {
            guardCalls++;
            if (guardFailure != null) throw new StepRunner.StepFailure(guardFailure, "验证已停止");
        }
        @Override public SubscribeTask.PurchaseObservation observe() {
            int at = Math.min(reads++, observations.size() - 1);
            return observations.get(at);
        }
        @Override public long now() {
            return clock;
        }
        @Override public void waitMillis(long millis) {
            clock += millis;
        }
    }

    private static SubscribeTask.PurchaseObservation observed(int vouchers) {
        return new SubscribeTask.PurchaseObservation(false, false, false,
                Texts.balance(0, vouchers));
    }

    private static SubscribeTask.Result buy(Session session, int actualPrice, int remaining)
            throws StepRunner.StepFailure {
        return SubscribeTask.buy(session, "第50章",
                Texts.parsePayment("实付0火券+" + actualPrice + "代券"),
                Texts.balance(0, 100), remaining);
    }

    @Test
    public void aRealPriceAboveTheRemainingDailyCapNeverClicksBuy() throws Exception {
        // 预估 10 券可放行，但页面实际是 20 券；最后付款门槛必须认实际的数。
        assertNull(SubscribeRun.stopBecauseBroke("甲", 100, 10, 5, 20, 50));
        Session session = new Session(observed(100));
        SubscribeTask.Result result = buy(session, 20,
                SubscribeRun.remainingDailyVouchers(20, 5));
        assertEquals(SubscribeTask.Status.DAILY_LIMIT, result.status);
        assertEquals(0, session.submits);
        assertEquals(0, session.confirmations);
    }

    @Test
    public void anUnknownCatalogPriceCannotBypassAnExhaustedAllowance() throws Exception {
        Session session = new Session(observed(100));
        SubscribeTask.Result result = buy(session, 20,
                SubscribeRun.remainingDailyVouchers(20, 20));
        assertEquals(SubscribeTask.Status.DAILY_LIMIT, result.status);
        assertEquals(0, session.submits);
    }

    @Test
    public void theExactRemainingAllowanceCanBeSpent() throws Exception {
        Session session = new Session(observed(80));
        SubscribeTask.Result result = buy(session, 20, 20);
        assertEquals(SubscribeTask.Status.BOUGHT, result.status);
        assertEquals(20, result.costVouchers);
        assertEquals(1, session.submits);
        assertFalse(result.abortRun);
    }

    @Test
    public void unlimitedDailySpendingStillRequiresVouchersOnly() throws Exception {
        Session session = new Session(observed(80));
        SubscribeTask.Result refused = SubscribeTask.buy(session, "第50章",
                Texts.parsePayment("实付5火券+20代券"), Texts.balance(100, 100), -1);
        assertEquals(SubscribeTask.Status.INSUFFICIENT, refused.status);
        assertEquals(0, session.submits);
        assertEquals(SubscribeTask.Status.BOUGHT, buy(session, 20, -1).status);
    }

    @Test
    public void cancellationBeforeSubmissionDoesNotSpend() throws Exception {
        Session session = new Session(observed(100));
        session.cancelled = true;
        try {
            buy(session, 20, -1);
            fail("应在购买点击前响应停止");
        } catch (StepRunner.StepFailure expected) {
            assertEquals(StepRunner.Kind.CANCELLED, expected.kind);
        }
        assertEquals(0, session.submits);
    }

    @Test
    public void cancellationWhileConfirmationAppearsNeverConfirms() throws Exception {
        Session session = new Session(observed(100));
        session.showConfirmation = true;
        session.cancelWhileFindingConfirmation = true;
        SubscribeTask.Result result = buy(session, 20, -1);
        assertEquals(1, session.submits);
        assertEquals(0, session.confirmations);
        assertEquals(SubscribeTask.Status.FAILED, result.status);
        assertTrue("点击后仍无结果必须整趟停止，不能交给其它号重买", result.abortRun);
    }

    @Test
    public void aPaymentThatArrivesAfterCancellationIsStillReturnedForRecording() throws Exception {
        Session session = new Session(observed(100), observed(80));
        session.cancelOnSubmit = true;
        session.showConfirmation = true;
        SubscribeTask.Result result = buy(session, 20, -1);
        assertTrue(session.cancelled);
        assertEquals(SubscribeTask.Status.BOUGHT, result.status);
        assertEquals(20, result.costVouchers);
        assertEquals(0, session.confirmations);
        assertTrue(session.reads >= 2);
    }

    @Test
    public void confirmationIsClickedOnceWhenTheRunIsActive() throws Exception {
        Session session = new Session(observed(100), observed(80));
        session.showConfirmation = true;
        assertEquals(SubscribeTask.Status.BOUGHT, buy(session, 20, -1).status);
        assertEquals(1, session.confirmations);
    }

    @Test
    public void cancellationAfterConfirmationStillCapturesTheCharge() throws Exception {
        Session session = new Session(observed(100), observed(80));
        session.showConfirmation = true;
        session.cancelOnConfirm = true;
        SubscribeTask.Result result = buy(session, 20, -1);
        assertEquals(SubscribeTask.Status.BOUGHT, result.status);
        assertEquals(20, result.costVouchers);
        assertEquals(1, session.confirmations);
        assertEquals("停止后不再打开验证等待", 1, session.guardCalls);
    }

    @Test
    public void insufficientFooterAfterChargingDoesNotDiscardThePurchase() throws Exception {
        Session session = new Session(new SubscribeTask.PurchaseObservation(false, false, true,
                Texts.balance(0, 80)));
        assertEquals(SubscribeTask.Status.BOUGHT, buy(session, 20, -1).status);
    }

    @Test
    public void aPaymentAfterAnInterruptedCaptchaIsRecordedBeforeStopping() throws Exception {
        Session session = new Session(observed(100), observed(80));
        session.guardFailure = StepRunner.Kind.CAPTCHA;
        session.showConfirmation = true;
        SubscribeTask.Result result = buy(session, 20, -1);
        assertEquals(SubscribeTask.Status.BOUGHT, result.status);
        assertTrue(result.abortRun);
        assertEquals(0, session.confirmations);
    }

    private static final StepRunner.Host STOPPED_HOST = new StepRunner.Host() {
        @Override public boolean isCancelled() { return true; }
        @Override public void log(String message) { }
        @Override public StepRunner.Decision awaitUser(String reason) {
            return StepRunner.Decision.ABORT;
        }
    };

    private static SubscriptionDao recordingDao(List<Purchase> stored, boolean failWrite) {
        return (SubscriptionDao) Proxy.newProxyInstance(SubscriptionDao.class.getClassLoader(),
                new Class<?>[]{SubscriptionDao.class}, (proxy, method, args) -> {
                    if ("upsertPurchase".equals(method.getName())) {
                        if (failWrite) throw new IllegalStateException("账本不可写");
                        stored.add((Purchase) args[0]);
                        return 1L;
                    }
                    throw new AssertionError("意外调用 " + method.getName());
                });
    }

    private static boolean record(SubscribeTask.Result result, List<Purchase> stored,
                                  boolean failWrite) throws StepRunner.StepFailure {
        Account account = new Account();
        account.id = 7;
        Chapter chapter = new Chapter();
        chapter.id = 123;
        chapter.chapterNo = 50;
        return SubscribeRun.apply(recordingDao(stored, failWrite), STOPPED_HOST,
                new SubscribeRun.Tally(new ArrayList<>()), account, chapter, result);
    }

    @Test
    public void confirmedPaymentIsPersistedEvenIfTheHostHasStopped() throws Exception {
        Session session = new Session(observed(80));
        session.cancelOnSubmit = true;
        List<Purchase> stored = new ArrayList<>();
        assertTrue(record(buy(session, 20, -1), stored, false));
        assertEquals(1, stored.size());
        assertEquals(7, stored.get(0).accountId);
        assertEquals(123, stored.get(0).chapterId);
        assertEquals(20, stored.get(0).costVouchers);
        assertEquals(Purchase.SRC_AUTO, stored.get(0).source);
    }

    @Test
    public void anUnexpectedLargerChargeIsRecordedBeforeAborting() throws Exception {
        List<Purchase> stored = new ArrayList<>();
        SubscribeTask.Result result = buy(new Session(observed(70)), 20, 20);
        try {
            record(result, stored, false);
            fail("实际扣款越限后必须停止");
        } catch (StepRunner.StepFailure expected) {
            assertEquals(StepRunner.Kind.MONEY_UNCLEAR, expected.kind);
        }
        assertEquals(1, stored.size());
        assertEquals(30, stored.get(0).costVouchers);
    }

    @Test
    public void ledgerWriteFailureAfterChargingAbortsAllAccounts() throws Exception {
        List<Purchase> stored = new ArrayList<>();
        try {
            record(buy(new Session(observed(80)), 20, -1), stored, true);
            fail("钱已扣但写账失败不能换号继续买");
        } catch (StepRunner.StepFailure expected) {
            assertEquals(StepRunner.Kind.MONEY_UNCLEAR, expected.kind);
        }
        assertTrue(stored.isEmpty());
    }

    private static final class Locator implements SubscribeTask.ChapterLocator {
        final int targetScreen;
        final StepRunner.Outcome target = new StepRunner.Outcome("第600章", FakeNode.text("600 结尾"));
        int screen;

        Locator(int targetScreen) { this.targetScreen = targetScreen; }
        @Override public void checkCancelled() { }
        @Override public StepRunner.Outcome find() {
            return screen == targetScreen ? target : null;
        }
        @Override public boolean scrollForward() {
            screen++;
            return true;
        }
        @Override public void settle() { }
    }

    @Test
    public void chaptersBeyondFortyScreensRemainReachable() throws Exception {
        Locator locator = new Locator(50);
        assertSame(locator.target, SubscribeTask.locateChapter(locator));
        assertEquals(50, locator.screen);
    }

    @Test
    public void theCatalogScanBoundaryIsAlsoThePurchaseSearchBoundary() throws Exception {
        Locator last = new Locator(CatalogScanner.DEFAULT_MAX_SCROLLS);
        assertNotNull(SubscribeTask.locateChapter(last));
        Locator tooFar = new Locator(CatalogScanner.DEFAULT_MAX_SCROLLS + 1);
        assertNull(SubscribeTask.locateChapter(tooFar));
        assertEquals(CatalogScanner.DEFAULT_MAX_SCROLLS, tooFar.screen);
    }
}
