package com.example.blb.auto;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 目录手势必须收到完成回调；拒绝、取消、超时和中断均不能作为页尾证据。 */
final class CatalogGestureCompletion {
    private enum State { PENDING, COMPLETED, FAILED }

    private final CountDownLatch done = new CountDownLatch(1);
    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);

    void completed() { finish(State.COMPLETED); }

    void cancelled() { finish(State.FAILED); }

    private void finish(State result) {
        if (state.compareAndSet(State.PENDING, result)) done.countDown();
    }

    boolean await(boolean accepted, long timeoutMillis) {
        if (!accepted || Thread.currentThread().isInterrupted()) {
            cancelled();
            return false;
        }
        try {
            if (!done.await(Math.max(0, timeoutMillis), TimeUnit.MILLISECONDS)) {
                cancelled();
                return false;
            }
            return !Thread.currentThread().isInterrupted() && state.get() == State.COMPLETED;
        } catch (InterruptedException e) {
            cancelled();
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
