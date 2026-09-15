package com.example.blb.auto;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class RunGateTest {
    @Test
    public void simultaneousManualAndScheduledStartsHaveExactlyOneOwner() throws Exception {
        RunGate gate = new RunGate();
        ExecutorService threads = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                attempts.add(threads.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return gate.tryStart(new Object());
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int started = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get(5, TimeUnit.SECONDS)) started++;
            }
            assertEquals(1, started);
            assertTrue(gate.isRunning());
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    public void rejectedAndStaleOwnersCannotUnlockAnotherRun() {
        RunGate gate = new RunGate();
        Object first = new Object();
        Object next = new Object();
        assertTrue(gate.tryStart(first));
        assertFalse(gate.tryStart(next));
        assertFalse(gate.finish(next));
        assertTrue(gate.finish(first));
        assertTrue(gate.tryStart(next));
        assertFalse(gate.finish(first));
        assertTrue(gate.owns(next));
    }

    @Test
    public void cancellationKeepsOwnershipUntilPaidResultsHaveBeenSaved() {
        RunGate gate = new RunGate();
        Object paying = new Object();
        assertTrue(gate.tryStart(paying));
        gate.cancel();
        assertTrue(gate.isCancelled());
        assertFalse(gate.tryStart(new Object()));
        assertTrue(gate.finish(paying));
        assertTrue(gate.tryStart(new Object()));
        assertFalse(gate.isCancelled());
    }

    @Test
    public void importingLedgerBlocksAutomationWithoutShowingAStoppableRun() {
        RunGate gate = new RunGate();
        Object edit = new Object();
        assertTrue(gate.tryStartEdit(edit));
        assertTrue(gate.isBusy());
        assertFalse(gate.isRunning());
        assertFalse(gate.tryStart(new Object()));
        assertFalse(gate.tryStartEdit(new Object()));
        gate.cancel();
        assertFalse(gate.isCancelled());
        assertTrue(gate.finish(edit));
        assertTrue(gate.tryStart(new Object()));
    }

    @Test
    public void automationBlocksLedgerDeletionUntilItFinishes() {
        RunGate gate = new RunGate();
        Object run = new Object();
        assertTrue(gate.tryStart(run));
        assertFalse(gate.tryStartEdit(new Object()));
        assertTrue(gate.finish(run));
        assertTrue(gate.tryStartEdit(new Object()));
    }
}
