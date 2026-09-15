package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** 目录改成独立按钮后，空账本不能再被说成已经订完，本机下载也不能冒充购买归属。 */
public class SubscribeReadinessTest {
    @Test
    public void anEmptyCatalogStopsWithAnActionableConfigurationFailure() {
        StepRunner.StepFailure failure = catalogFailure(0);
        assertEquals(StepRunner.Kind.CONFIG, failure.kind);
        assertTrue(failure.getMessage().contains("还没有章节"));
        assertTrue(failure.getMessage().contains("同步目录"));
        assertFalse(failure.getMessage().contains("没有待订阅"));
    }

    @Test
    public void anUnknownCatalogCountNeverMeansAnEmptyOrFinishedBook() {
        for (int unknown : new int[]{-1, Integer.MIN_VALUE}) {
            StepRunner.StepFailure failure = catalogFailure(unknown);
            assertEquals(StepRunner.Kind.CONFIG, failure.kind);
            assertTrue(failure.getMessage().contains("读不到"));
            assertTrue(failure.getMessage().contains("同步目录"));
            assertFalse(failure.getMessage().contains("已经"));
        }
    }

    @Test
    public void aKnownNonemptyCatalogPassesTheCountGuard() throws Exception {
        SubscribeRun.requireCatalogCount(1);
        SubscribeRun.requireCatalogCount(612);
    }

    @Test
    public void aDeviceDownloadWithoutAGlobalOwnerStopsAtThatExactChapter() {
        String problem = SubscribeRun.deviceOwnershipProblem(false, 83);
        assertNotNull(problem);
        assertTrue(problem, problem.contains("第83章"));
        assertTrue(problem, problem.contains("账本没有买家"));
        assertTrue(problem, problem.contains("整本停止"));
        assertTrue(problem, problem.contains("不能跳过此章"));
        assertTrue(problem, problem.contains("核对订阅清单"));
    }

    @Test
    public void anExistingGlobalOwnerAllowsTheNextChapterToBeRecomputed() {
        assertNull(SubscribeRun.deviceOwnershipProblem(true, 83));
    }

    private static StepRunner.StepFailure catalogFailure(int count) {
        try {
            SubscribeRun.requireCatalogCount(count);
            fail("目录未知或为空时，不得进入购买");
            return null;
        } catch (StepRunner.StepFailure failure) {
            return failure;
        }
    }
}
