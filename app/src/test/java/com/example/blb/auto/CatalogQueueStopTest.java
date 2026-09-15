package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 目录是八个号共用的购买依据；同步或返回首页没走通，不能让下一号继续用它花券。 */
public class CatalogQueueStopTest {
    @Test
    public void aCatalogOrCleanupTimeoutStopsBothDailyAndSubscribeQueues() {
        StepRunner.Kind kind = CatalogQueue.stopKind(StepRunner.Kind.TIMEOUT);
        assertEquals(StepRunner.Kind.MONEY_UNCLEAR, kind);
        assertTrue("每日流程必须整趟停止，不能只跳过第一个号", CheckInQueue.isGlobal(kind));
        assertTrue("单独订阅也必须整趟停止", SubscribeQueue.isGlobal(kind));
    }

    @Test
    public void launchLoginCaptchaOrSkipFailuresCannotMoveOnToAnotherAccount() {
        for (StepRunner.Kind failure : new StepRunner.Kind[]{StepRunner.Kind.NEEDS_LAUNCH,
                StepRunner.Kind.LOGIN_FAILED, StepRunner.Kind.CAPTCHA, StepRunner.Kind.SKIPPED}) {
            StepRunner.Kind stopped = CatalogQueue.stopKind(failure);
            assertEquals(failure.name(), StepRunner.Kind.MONEY_UNCLEAR, stopped);
            assertTrue(failure.name(), CheckInQueue.isGlobal(stopped));
            assertTrue(failure.name(), SubscribeQueue.isGlobal(stopped));
        }
    }

    @Test
    public void existingGlobalFailuresKeepTheirSpecificCause() {
        for (StepRunner.Kind failure : new StepRunner.Kind[]{StepRunner.Kind.CANCELLED,
                StepRunner.Kind.NO_SERVICE, StepRunner.Kind.CONFIG, StepRunner.Kind.MONEY_UNCLEAR}) {
            assertEquals("取消与配置错误的原因不能被改写", failure, CatalogQueue.stopKind(failure));
            assertTrue(CheckInQueue.isGlobal(CatalogQueue.stopKind(failure)));
            assertTrue(SubscribeQueue.isGlobal(CatalogQueue.stopKind(failure)));
        }
    }

    @Test
    public void anUnknownFailureKindAlsoStopsTheWholeQueue() {
        assertEquals(StepRunner.Kind.MONEY_UNCLEAR, CatalogQueue.stopKind(null));
    }
}
