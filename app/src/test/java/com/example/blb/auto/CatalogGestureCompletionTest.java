package com.example.blb.auto;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

public class CatalogGestureCompletionTest {
    @Test public void acceptanceWaitsForTheActualCompletionCallback() throws Exception {
        CatalogGestureCompletion completion = new CatalogGestureCompletion();
        CountDownLatch entered = new CountDownLatch(1);
        FutureTask<Boolean> result = new FutureTask<>(() -> {
            entered.countDown();
            return completion.await(true, 5_000);
        });
        Thread worker = new Thread(result);
        worker.setDaemon(true);
        try {
            worker.start();
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertFalse("系统接受手势后仍须等待完成回调", result.isDone());
            completion.completed();
            assertTrue(result.get(1, TimeUnit.SECONDS));
        } finally {
            worker.interrupt();
            worker.join(1_000);
        }
    }

    @Test public void anAcceptedButCancelledGestureIsNotSuccessful() {
        CatalogGestureCompletion completion = new CatalogGestureCompletion();
        completion.cancelled();
        completion.completed();

        assertFalse("迟到的完成通知不能覆盖取消结果", completion.await(true, 0));
    }

    @Test public void aRejectedDispatchCannotBeReportedAsSuccessful() {
        CatalogGestureCompletion completion = new CatalogGestureCompletion();
        completion.completed();

        assertFalse(completion.await(false, 0));
    }

    @Test public void aMissingCallbackTimesOutAndLateCompletionCannotReviveIt() {
        CatalogGestureCompletion completion = new CatalogGestureCompletion();

        assertFalse("只有接受、没有完成回调时必须失败", completion.await(true, 0));
        completion.completed();
        assertFalse(completion.await(true, 0));
    }

    @Test public void interruptionStopsWaitingAndPreservesTheInterruptFlag() throws Exception {
        CatalogGestureCompletion completion = new CatalogGestureCompletion();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        FutureTask<Boolean> result = new FutureTask<>(() -> {
            entered.countDown();
            boolean success = completion.await(true, 5_000);
            interruptPreserved.set(Thread.currentThread().isInterrupted());
            return success;
        });
        Thread worker = new Thread(result);
        worker.setDaemon(true);
        try {
            worker.start();
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            worker.interrupt();
            assertFalse(result.get(1, TimeUnit.SECONDS));
            assertTrue(interruptPreserved.get());
            completion.completed();
            assertFalse("取消后的迟到回调不能成为成功证据", completion.await(true, 0));
        } finally {
            worker.interrupt();
            worker.join(1_000);
        }
    }

    @Test public void anAlreadyInterruptedCallerDoesNotConsumeASuccessfulCallback() {
        CatalogGestureCompletion completion = new CatalogGestureCompletion();
        completion.completed();
        Thread.currentThread().interrupt();
        try {
            assertFalse(completion.await(true, 0));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
