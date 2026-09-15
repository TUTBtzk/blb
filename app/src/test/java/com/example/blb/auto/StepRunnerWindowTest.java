package com.example.blb.auto;

import static org.junit.Assert.*;

import org.junit.Test;

public class StepRunnerWindowTest {
    @Test public void ensureHomeClosesActiveOverlayBeforeAcceptingBackgroundTabs() throws Exception {
        NodeView home = FakeNode.node().withId("main_tab");
        OfflineRunner runner = new OfflineRunner() {
            @Override public void back() throws StepFailure {
                super.back();
                active = home;
                others = null;
            }
        };
        runner.others = home;
        runner.ensureHome(2);
        assertEquals(1, runner.backs);
    }

    @Test public void activeMineTabIsEnoughWhenHomeContainerIsUnavailable() throws Exception {
        OfflineRunner runner = new OfflineRunner();
        runner.active = FakeNode.node().add(FakeNode.node().withId("main_tab_container5"));

        assertNull(runner.findAny(Keys.HOME_READY));
        assertNotNull(runner.findAny(Keys.MINE_TAB));
        runner.ensureHome(6);

        assertEquals("活动窗口已经有我的入口，不应再按六次返回", 0, runner.backs);
    }

    @Test public void delayedNavigationIsAllowedToAppearBeforeBack() throws Exception {
        NodeView home = FakeNode.node().add(FakeNode.node().withId("main_tab_container5"));
        OfflineRunner runner = new OfflineRunner() {
            @Override public void waitMillis(long ms) {
                super.waitMillis(ms);
                if (now >= 1_800) active = home;
            }
        };

        runner.ensureHome(6);

        assertEquals("导航稍晚出现时应等待，不应先按返回", 0, runner.backs);
        assertSame(home, runner.active);
    }

    @Test public void transientMissingRootDoesNotCauseAnExtraBack() throws Exception {
        NodeView home = FakeNode.node().add(FakeNode.node().withId("main_tab_container5"));
        OfflineRunner runner = new OfflineRunner() {
            int reads;

            @Override NodeView activeRoot() {
                return reads++ == 0 ? null : home;
            }
        };

        runner.ensureHome(6);

        assertEquals("第一次无树、随后已有导航时不能继续按返回", 0, runner.backs);
    }

    @Test public void navigationMayAppearAfterClosingAnOverlayWithoutAnotherBack() throws Exception {
        NodeView home = FakeNode.node().add(FakeNode.node().withId("main_tab_container5"));
        OfflineRunner runner = new OfflineRunner() {
            long navigationAt = Long.MAX_VALUE;

            @Override public void back() throws StepFailure {
                super.back();
                active = null;
                others = null;
                navigationAt = now + 800;
            }

            @Override public void waitMillis(long ms) {
                super.waitMillis(ms);
                if (now >= navigationAt) active = home;
            }
        };
        runner.active = FakeNode.text("当前弹窗");
        runner.others = home;

        runner.ensureHome(6);

        assertEquals("先关闭弹窗，再等活动窗口导航出现，只需一次返回", 1, runner.backs);
        assertSame(home, runner.active);
    }

    @Test public void cancellationDuringHomeWaitStopsBeforeBack() throws Exception {
        OfflineRunner runner = new OfflineRunner() {
            @Override public void waitMillis(long ms) {
                super.waitMillis(ms);
                testHost.cancelled = true;
            }
        };

        try {
            runner.ensureHome(6);
            fail("等待首页期间的取消必须立即终止流程");
        } catch (StepRunner.StepFailure failure) {
            assertEquals(StepRunner.Kind.CANCELLED, failure.kind);
        }

        assertEquals(0, runner.backs);
        assertTrue(runner.presses.isEmpty());
    }

    @Test public void foregroundChangeDuringHomeWaitRelaunchesBeforeBack() throws Exception {
        NodeView home = FakeNode.node().add(FakeNode.node().withId("main_tab_container5"));
        int[] launches = {0};
        OfflineRunner runner = new OfflineRunner() {
            boolean foreground = true;

            @Override public boolean isTargetForeground() { return foreground; }

            @Override public void launchTarget(long timeoutMs) throws StepFailure {
                checkCancelled();
                if (++launches[0] > 1) {
                    foreground = true;
                    active = home;
                }
            }

            @Override public void waitMillis(long ms) {
                super.waitMillis(ms);
                foreground = false;
                active = null;
            }
        };

        runner.ensureHome(6);

        assertEquals("等待时离开菠萝包，应重新拉回前台", 2, launches[0]);
        assertEquals(0, runner.backs);
        assertSame(home, runner.active);
    }

    @Test public void aPersistentlyMissingNavigationKeepsTheBackLimitBounded() throws Exception {
        OfflineRunner runner = new OfflineRunner();
        long started = runner.now;

        runner.ensureHome(2);

        assertEquals(2, runner.backs);
        assertTrue("导航始终不可读时也不能无限等待", runner.now - started < 10_000);
        assertTrue(runner.testHost.logs.stream().anyMatch(
                message -> message.contains("按了 2 次返回仍未识别到首页导航")));
    }

    @Test public void backgroundMineTabCannotCountAsHomeBeforeOverlayCloses() throws Exception {
        NodeView home = FakeNode.node().add(FakeNode.node().withId("main_tab_container5"));
        OfflineRunner runner = new OfflineRunner() {
            @Override public void back() throws StepFailure {
                super.back();
                active = home;
                others = null;
            }
        };
        runner.active = FakeNode.text("当前弹窗");
        runner.others = home;

        assertNull(runner.findAny(Keys.HOME_READY, Keys.MINE_TAB));
        runner.ensureHome(6);

        assertEquals("先关闭活动弹窗，直到我的入口真正出现在活动窗口才可停止", 1, runner.backs);
    }

    @Test public void crossWindowButtonsAreIgnoredWhenTheActiveWindowIsNotTheTarget()
            throws Exception {
        OfflineRunner runner = new OfflineRunner();
        runner.active = null; // 真实 root() 对其他 App 的活动窗口返回 null。
        runner.others = FakeNode.node().withId("main_tab_container5");
        assertNull(runner.findAnyAcrossWindows(Keys.MINE_TAB));
    }

    @Test public void backgroundBalanceIsNeverPaymentEvidence() throws Exception {
        OfflineRunner runner = new OfflineRunner();
        runner.others = FakeNode.text("账户余额：0火券/30代券").withId("tvAccount");
        assertFalse(runner.peekBalance().known());

        runner.active = FakeNode.text("账户余额：0火券/12代券").withId("tvAccount");
        runner.testHost.cancelled = true;
        assertEquals("取消后仍须读取实际扣款证据", 12, runner.peekBalance().voucher);
    }

    @Test public void cancelledActionsStopBeforeReachingAndroidService() throws Exception {
        OfflineRunner.Host host = new OfflineRunner.Host();
        host.cancelled = true;
        StepRunner runner = new StepRunner(OfflineRunner.bundled(), host);
        Action[] actions = {
                () -> runner.clickNode("button", FakeNode.node()),
                () -> runner.pressOrLog("button", FakeNode.node()),
                runner::back, runner::scrollForward, runner::scrollBackward, runner::scrollCatalogForward,
                () -> runner.launchTarget(1000)
        };
        for (Action action : actions) {
            try {
                action.run();
                fail("取消后不应访问服务或发出动作");
            } catch (StepRunner.StepFailure failure) {
                assertEquals(StepRunner.Kind.CANCELLED, failure.kind);
            }
        }
    }

    private interface Action { void run() throws StepRunner.StepFailure; }
}
