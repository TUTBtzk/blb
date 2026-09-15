package com.example.blb.auto;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class ReturnWatchdogGuardTest {
    @Test public void cancellingAfterBroadcastInvalidatesItsDelayedLaunch() {
        ReturnWatchdogGuard guard = new ReturnWatchdogGuard();
        AtomicInteger actions = new AtomicInteger();
        guard.activate(1);
        assertTrue(guard.runIfCurrent(1, actions::incrementAndGet)); // 广播已经按过返回。
        Runnable delayedLaunch = () -> guard.runIfCurrent(1, actions::incrementAndGet);
        guard.cancel();
        delayedLaunch.run();
        assertEquals(1, actions.get());
        assertFalse("已取消的广播即使重复送达，也不能复活", guard.activate(1));
    }

    @Test public void oldCallbackCannotLaunchDuringANewerWatchdog() {
        ReturnWatchdogGuard guard = new ReturnWatchdogGuard();
        AtomicInteger actions = new AtomicInteger();
        guard.activate(1);
        guard.cancel();
        guard.activate(2);
        assertFalse(guard.runIfCurrent(1, actions::incrementAndGet));
        assertTrue(guard.runIfCurrent(2, actions::incrementAndGet));
        assertEquals(1, actions.get());
    }

    @Test public void cancellationWaitsForAnActionThatAlreadyStartedAndBlocksTheNextOne()
            throws Exception {
        ReturnWatchdogGuard guard = new ReturnWatchdogGuard();
        guard.activate(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        Thread action = new Thread(() -> guard.runIfCurrent(1, () -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        Thread stop = new Thread(() -> {
            guard.cancel();
            cancelled.countDown();
        });
        action.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        stop.start();
        release.countDown();
        assertTrue(cancelled.await(5, TimeUnit.SECONDS));
        action.join(5_000);
        stop.join(5_000);
        assertFalse(guard.runIfCurrent(1, () -> fail("停止返回后不能再开始动作")));
    }
}
