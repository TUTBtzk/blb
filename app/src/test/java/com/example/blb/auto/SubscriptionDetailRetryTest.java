package com.example.blb.auto;

import com.example.blb.data.Chapter;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class SubscriptionDetailRetryTest {
    private static VoucherLedger.Reading aggregate(int count) {
        return new VoucherLedger.Reading(true, count, 0, -1, null, count + "章节 - 0火券");
    }

    private static final class Attempts implements SubscriptionAuditQueue.DetailAttemptReader {
        final List<SubscribedDetailPipelineTest.Reader> readers;
        final List<Integer> expected, seenAttempts = new ArrayList<>();
        final List<SubscriptionAuditQueue.DetailAttempt> seen = new ArrayList<>();
        int reads;

        Attempts(List<Integer> expected, SubscribedDetailPipelineTest.Reader... readers) {
            this.expected = expected;
            this.readers = Arrays.asList(readers);
        }

        @Override public SubscriptionAuditQueue.DetailAttempt read(int attempt) throws StepRunner.StepFailure {
            reads++;
            SubscribedDetail.ReadResult result = SubscribedDetail.collectWithEvidence(readers.get(attempt - 1),
                    Collections.<Chapter>emptyList(), expected.get(attempt - 1));
            return new SubscriptionAuditQueue.DetailAttempt(aggregate(expected.get(attempt - 1)),
                    result, attempt * 100L, attempt * 100L + 1);
        }

        @Override public void observed(int attempt, SubscriptionAuditQueue.DetailAttempt result) {
            seenAttempts.add(attempt);
            seen.add(result);
        }
    }

    @Test public void incompleteFirstReadReopensWithItsOwnFreshAggregateAndStartsOver() throws Exception {
        SubscribedDetailPipelineTest.Reader first = new SubscribedDetailPipelineTest.Reader(
                SubscribedDetailPipelineTest.window(15, 0, 5));
        first.failForward = true;
        Attempts source = new Attempts(Arrays.asList(15, 20), first, SubscribedDetailPipelineTest.twenty());
        SubscriptionAuditQueue.DetailAttempt result = SubscriptionAuditQueue.readDetailWithRetry(source);
        assertEquals(Arrays.asList(1, 2), source.seenAttempts);
        assertEquals(15, source.seen.get(0).detail.expectedChapters);
        assertEquals(20, result.aggregate.chapters);
        assertEquals(20, result.detail.expectedChapters);
        assertEquals(20, result.detail.entries.size());
        assertTrue(result.detail.describe(), result.detail.complete());
        assertEquals(200, result.aggregateAt);
        assertEquals(1, first.backwardProbes);
        assertEquals(1, source.readers.get(1).backwardProbes);
    }

    @Test public void twoIncompleteAttemptsKeepBothReasonsAndDoNotMergeRecords() throws Exception {
        SubscribedDetailPipelineTest.Reader first = new SubscribedDetailPipelineTest.Reader(
                SubscribedDetailPipelineTest.window(20, 0, 5));
        first.failForward = true;
        SubscribedDetailPipelineTest.Reader second = new SubscribedDetailPipelineTest.Reader(
                SubscribedDetailPipelineTest.window(20, 5, 10));
        Attempts source = new Attempts(Arrays.asList(20, 20), first, second);
        SubscriptionAuditQueue.DetailAttempt result = SubscriptionAuditQueue.readDetailWithRetry(source);
        assertEquals(2, source.seen.size());
        assertFalse(source.seen.get(0).detail.complete());
        assertFalse(result.detail.complete());
        assertTrue(source.seen.get(0).detail.stopReason.contains("未完成"));
        assertTrue(result.detail.stopReason.contains("首屏"));
        assertEquals(6, result.detail.entries.size());
    }

    @Test public void cancellationDoesNotStartAnotherAttempt() throws Exception {
        SubscribedDetailPipelineTest.Reader first = new SubscribedDetailPipelineTest.Reader(
                SubscribedDetailPipelineTest.window(20, 0, 5));
        first.cancelForward = true;
        Attempts source = new Attempts(Arrays.asList(20, 20), first, SubscribedDetailPipelineTest.twenty());
        try { SubscriptionAuditQueue.readDetailWithRetry(source); fail("应传播取消"); }
        catch (StepRunner.StepFailure failure) { assertEquals(StepRunner.Kind.CANCELLED, failure.kind); }
        assertEquals(1, source.reads);
    }

    @Test public void aSuccessfulFirstReadDoesNotNeedASecondAttempt() throws Exception {
        Attempts source = new Attempts(Collections.singletonList(20), SubscribedDetailPipelineTest.twenty());
        assertTrue(SubscriptionAuditQueue.readDetailWithRetry(source).detail.complete());
        assertEquals(1, source.reads);
    }

    @Test public void firstAttemptFireCannotBeErasedByASuccessfulRetry() throws Exception {
        FakeNode page = SubscribedDetailPipelineTest.window(20, 0, 5);
        ((FakeNode) page.child(1).child(1).child(2)).withText("火券");
        SubscribedDetailPipelineTest.Reader first = new SubscribedDetailPipelineTest.Reader(page);
        first.failForward = true;
        Attempts source = new Attempts(Arrays.asList(20, 20), first, SubscribedDetailPipelineTest.twenty());
        SubscriptionAuditQueue.DetailAttempt result = SubscriptionAuditQueue.readDetailWithRetry(source);
        assertEquals(1, source.reads);
        assertTrue(result.detail.fireObserved);
        assertTrue(SubscriptionAuditPolicy.hasFireEvidence(1, result.aggregate, result.detail,
                Collections.emptyList()));
    }

    @Test public void fireWithoutAReadableDescriptionAlsoPreventsTheSecondAttempt() throws Exception {
        for (boolean missingId : new boolean[]{true, false}) {
            FakeNode page = SubscribedDetailPipelineTest.window(20, 0, 5);
            FakeNode row = (FakeNode) page.child(1).child(1);
            if (missingId) ((FakeNode) row.child(0)).withId("unreadable_desc");
            else ((FakeNode) row.child(0)).withBounds(0, 0, 0, 0);
            ((FakeNode) row.child(2)).withText(" 火券 ");
            SubscribedDetailPipelineTest.Reader first = new SubscribedDetailPipelineTest.Reader(page);
            first.failForward = true;
            Attempts source = new Attempts(Arrays.asList(20, 20), first, SubscribedDetailPipelineTest.twenty());
            SubscriptionAuditQueue.DetailAttempt result = SubscriptionAuditQueue.readDetailWithRetry(source);
            assertEquals(1, source.reads);
            assertTrue(result.detail.fireObserved);
            for (SubscribedDetail.Entry retained : result.detail.entries) assertFalse(retained.fireSpent());
            assertTrue(SubscriptionAuditPolicy.hasFireEvidence(1, result.aggregate, result.detail,
                    Collections.emptyList()));
        }
    }

    @Test public void logsDistinguishPrintedAndGlobalPositionsWithoutGuessing() {
        SubscribedDetail.Entry entry = SubscribedDetail.parseRow("星彩 第6章 薯片", "10", "代券", "2026-09-08");
        Chapter chapter = new Chapter();
        chapter.id = 990; chapter.novelId = 1; chapter.chapterNo = 99;
        chapter.title = "第6章 薯片"; chapter.volumeTitle = "星彩";
        String message = SubscriptionAuditQueue.mappedDescription(entry, Collections.singletonList(chapter));
        assertTrue(message.contains("星彩"));
        assertTrue(message.contains("卷内第6章"));
        assertTrue(message.contains("全书第 99 章"));
        assertTrue(message.contains("10代券"));
        assertTrue(SubscriptionAuditQueue.mappedDescription(entry, Collections.emptyList()).contains("未能唯一映射"));
    }
}
